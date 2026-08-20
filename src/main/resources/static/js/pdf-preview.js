/**
 * pdf-preview.js — custom PDF.js viewer, self-hosted (see
 * static/vendor/pdfjs/), rendering pages to <canvas> in our own chrome
 * instead of the browser's built-in PDF viewer or PDF.js's own bundled
 * viewer UI. The point (see roadmap Phase 8's explicit instruction) is
 * that no native save/print/download toolbar is ever on screen - a viewer
 * has to go through the "Request Access" flow (Phase 9) or an existing
 * direct-download permission to get the original file, not a one-click
 * button sitting on top of the preview.
 *
 * HONEST SCOPE - same as preview-guard.js: this does not and cannot
 * prevent screenshots, a phone camera pointed at the screen, or a
 * sufficiently motivated person using devtools to pull rendered canvas
 * pixels or intercept the fetch() below. Removing the one-click download
 * button and the native toolbar is real, meaningful friction - it is not,
 * and does not pretend to be, DRM.
 *
 * init() renders an inline (in-page) viewer. initFullscreen() opens a
 * second, full-viewport instance in a modal overlay - built on the same
 * buildViewer()/renderer internals so both share zoom/page-nav behavior
 * and the same right-click guard; the PDF bytes are re-fetched for the
 * fullscreen instance rather than shared with the inline one, trading a
 * second network round trip for two fully independent viewer/render
 * states (simpler than threading page/scale/pdfDoc between two chrome
 * layouts, and the file is already cached by the browser from the first
 * fetch so the second one is effectively free).
 */
(function (window, document) {
    'use strict';

    async function loadPdfDoc(previewUrl, libUrl, workerUrl) {
        const pdfjsLib = await import(libUrl);
        pdfjsLib.GlobalWorkerOptions.workerSrc = workerUrl;
        // credentials: 'same-origin' so the session cookie backing
        // requireViewable's auth check goes along with the request - this
        // endpoint is not a public URL.
        const response = await fetch(previewUrl, { credentials: 'same-origin' });
        if (!response.ok) {
            throw new Error('HTTP ' + response.status);
        }
        const data = await response.arrayBuffer();
        return pdfjsLib.getDocument({ data }).promise;
    }

    /** Builds the toolbar + canvas chrome into `container` and returns render-loop controls. Shared by the inline and fullscreen viewers. */
    function buildViewer(container, { canvasWrapClass, extraToolbarButtons } = {}) {
        container.innerHTML = '';
        container.classList.add('flex', 'flex-col');

        const toolbar = buildToolbar(container, extraToolbarButtons);
        const canvasWrap = document.createElement('div');
        canvasWrap.className = canvasWrapClass || 'flex-1 min-h-0 overflow-auto flex justify-center bg-eac-bg/60 rounded-b-xl p-4';
        const canvas = document.createElement('canvas');
        canvas.className = 'shadow-md bg-white';
        canvasWrap.appendChild(canvas);
        container.appendChild(canvasWrap);

        // No right-click "Save Image As..." on the rendered page.
        canvas.addEventListener('contextmenu', (e) => e.preventDefault());

        let pdfDoc = null;
        let currentPage = 1;
        let scale = 1.2;
        let renderTask = null;

        async function renderPage(pageNumber) {
            if (!pdfDoc) return;
            if (renderTask) {
                renderTask.cancel();
            }
            const page = await pdfDoc.getPage(pageNumber);
            // CSS-size viewport (what the page should look like on screen)
            // is kept separate from the canvas's actual pixel buffer, which
            // is rendered at devicePixelRatio times bigger and then scaled
            // back down via canvas.style.width/height. Without this split,
            // canvas.width/height WAS the CSS size (1 canvas px = 1 CSS
            // px), so on any high-DPI screen the browser stretched a
            // lower-resolution bitmap to fill the same box - the blur/
            // "stretched" look, worse at higher zoom since the buffer grew
            // but still had no DPI headroom.
            const cssViewport = page.getViewport({ scale });
            const outputScale = window.devicePixelRatio || 1;
            const pixelViewport = page.getViewport({ scale: scale * outputScale });

            const context = canvas.getContext('2d');
            canvas.width = Math.ceil(pixelViewport.width);
            canvas.height = Math.ceil(pixelViewport.height);
            canvas.style.width = Math.ceil(cssViewport.width) + 'px';
            canvas.style.height = Math.ceil(cssViewport.height) + 'px';

            renderTask = page.render({ canvasContext: context, viewport: pixelViewport });
            try {
                await renderTask.promise;
            } catch (err) {
                if (err && err.name === 'RenderingCancelledException') return;
                throw err;
            }
            toolbar.pageLabel.textContent = pageNumber + ' / ' + pdfDoc.numPages;
        }

        toolbar.prevBtn.addEventListener('click', () => {
            if (currentPage <= 1) return;
            currentPage -= 1;
            renderPage(currentPage);
        });
        toolbar.nextBtn.addEventListener('click', () => {
            if (!pdfDoc || currentPage >= pdfDoc.numPages) return;
            currentPage += 1;
            renderPage(currentPage);
        });
        toolbar.zoomInBtn.addEventListener('click', () => {
            scale = Math.min(scale + 0.2, 3);
            renderPage(currentPage);
        });
        toolbar.zoomOutBtn.addEventListener('click', () => {
            scale = Math.max(scale - 0.2, 0.4);
            renderPage(currentPage);
        });

        return {
            toolbar,
            setDoc(doc) {
                pdfDoc = doc;
                currentPage = 1;
            },
            renderFirstPage: () => renderPage(1)
        };
    }

    async function initPdfPreview(container, options) {
        const { previewUrl, workerUrl, libUrl, onOpenFullscreen } = options;

        const fullscreenBtn = onOpenFullscreen ? iconButton('⤢', 'Open fullscreen') : null;
        const viewer = buildViewer(container, {
            extraToolbarButtons: fullscreenBtn ? [fullscreenBtn] : []
        });

        if (fullscreenBtn) {
            fullscreenBtn.addEventListener('click', () => onOpenFullscreen());
        }

        try {
            viewer.toolbar.status.textContent = 'Loading preview…';
            const pdfDoc = await loadPdfDoc(previewUrl, libUrl, workerUrl);
            viewer.setDoc(pdfDoc);
            viewer.toolbar.status.textContent = '';
            viewer.toolbar.controls.classList.remove('hidden');
            await viewer.renderFirstPage();
        } catch (err) {
            viewer.toolbar.status.textContent = 'Could not load the preview. Please try again later.';
            viewer.toolbar.controls.classList.add('hidden');
        }
    }

    /**
     * Opens a full-viewport modal overlay (dark backdrop, the document
     * filling essentially the whole screen, Google-Docs-viewer style) with
     * its own independent PDF.js render loop. Closes on the × button,
     * clicking the backdrop, or Escape. Returns a close() function.
     */
    async function openFullscreenPdfPreview(options) {
        const { previewUrl, workerUrl, libUrl, title } = options;

        const overlay = document.createElement('div');
        overlay.className = 'fixed inset-0 z-50 bg-black/80 flex flex-col p-3 sm:p-6';
        overlay.setAttribute('role', 'dialog');
        overlay.setAttribute('aria-modal', 'true');

        const header = document.createElement('div');
        header.className = 'flex items-center justify-between text-white pb-3 shrink-0';
        const titleEl = document.createElement('span');
        titleEl.className = 'font-medium text-sm sm:text-base truncate pr-4';
        titleEl.textContent = title || 'Document preview';
        const closeBtn = document.createElement('button');
        closeBtn.type = 'button';
        closeBtn.setAttribute('aria-label', 'Close fullscreen preview');
        closeBtn.className = 'shrink-0 w-9 h-9 flex items-center justify-center rounded-full hover:bg-white/10 transition text-xl leading-none';
        closeBtn.textContent = '×';
        header.append(titleEl, closeBtn);

        const viewerHost = document.createElement('div');
        viewerHost.className = 'flex-1 min-h-0 bg-white rounded-xl overflow-hidden flex flex-col';

        overlay.append(header, viewerHost);
        document.body.appendChild(overlay);
        document.body.classList.add('overflow-hidden');

        function close() {
            document.body.classList.remove('overflow-hidden');
            document.removeEventListener('keydown', onKeydown);
            overlay.remove();
        }

        function onKeydown(e) {
            if (e.key === 'Escape') close();
        }

        closeBtn.addEventListener('click', close);
        overlay.addEventListener('click', (e) => {
            if (e.target === overlay) close();
        });
        document.addEventListener('keydown', onKeydown);

        const viewer = buildViewer(viewerHost, {
            canvasWrapClass: 'flex-1 min-h-0 overflow-auto flex justify-center bg-eac-bg/60 p-4 sm:p-8'
        });

        try {
            viewer.toolbar.status.textContent = 'Loading preview…';
            const pdfDoc = await loadPdfDoc(previewUrl, libUrl, workerUrl);
            viewer.setDoc(pdfDoc);
            viewer.toolbar.status.textContent = '';
            viewer.toolbar.controls.classList.remove('hidden');
            await viewer.renderFirstPage();
        } catch (err) {
            viewer.toolbar.status.textContent = 'Could not load the preview. Please try again later.';
            viewer.toolbar.controls.classList.add('hidden');
        }

        return close;
    }

    function buildToolbar(container, extraButtons) {
        const bar = document.createElement('div');
        bar.className = 'flex items-center justify-between gap-3 px-4 py-2.5 border-b border-eac-border bg-white text-sm shrink-0';

        const status = document.createElement('span');
        status.className = 'text-eac-text-light';

        const controls = document.createElement('div');
        controls.className = 'hidden flex items-center gap-1.5';

        const prevBtn = iconButton('‹', 'Previous page');
        const pageLabel = document.createElement('span');
        pageLabel.className = 'text-eac-text-light px-1 tabular-nums min-w-[4.5rem] text-center';
        const nextBtn = iconButton('›', 'Next page');
        const divider = document.createElement('span');
        divider.className = 'w-px h-5 bg-eac-border mx-1';
        const zoomOutBtn = iconButton('−', 'Zoom out');
        const zoomInBtn = iconButton('+', 'Zoom in');

        controls.append(prevBtn, pageLabel, nextBtn, divider, zoomOutBtn, zoomInBtn);
        if (extraButtons && extraButtons.length > 0) {
            const extraDivider = document.createElement('span');
            extraDivider.className = 'w-px h-5 bg-eac-border mx-1';
            controls.append(extraDivider, ...extraButtons);
        }
        bar.append(status, controls);
        container.appendChild(bar);

        return { bar, status, controls, prevBtn, nextBtn, pageLabel, zoomInBtn, zoomOutBtn };
    }

    function iconButton(label, ariaLabel) {
        const button = document.createElement('button');
        button.type = 'button';
        button.setAttribute('aria-label', ariaLabel);
        button.textContent = label;
        button.className = 'w-7 h-7 flex items-center justify-center rounded-md text-eac-text hover:bg-eac-bg transition font-medium';
        return button;
    }

    window.PdfPreview = { init: initPdfPreview, openFullscreen: openFullscreenPdfPreview };
})(window, document);
