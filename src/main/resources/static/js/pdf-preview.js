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
        const reader = document.createElement('div');
        reader.className = 'flex-1 min-h-0 bg-eac-bg/70 grid md:grid-cols-[128px_1fr]';
        const thumbnailRail = document.createElement('div');
        thumbnailRail.className = 'hidden md:block border-r border-eac-border bg-white/70 overflow-auto p-4';
        const thumbnailList = document.createElement('div');
        thumbnailList.className = 'space-y-4';
        thumbnailRail.appendChild(thumbnailList);
        const canvasWrap = document.createElement('div');
        canvasWrap.className = canvasWrapClass || 'min-h-[620px] max-h-[76vh] overflow-auto flex justify-center items-start bg-eac-bg/70 p-5 sm:p-8';
        const canvas = document.createElement('canvas');
        canvas.className = 'shadow-xl shadow-black/10 bg-white border border-eac-border';
        canvasWrap.appendChild(canvas);
        reader.append(thumbnailRail, canvasWrap);
        container.appendChild(reader);

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
            toolbar.pageInput.value = pageNumber;
            setActiveThumbnail(pageNumber);
        }

        async function renderThumbnails() {
            if (!pdfDoc || !thumbnailList) return;
            thumbnailList.innerHTML = '';
            const maxPages = Math.min(pdfDoc.numPages, 24);
            for (let pageNumber = 1; pageNumber <= maxPages; pageNumber++) {
                const thumbButton = document.createElement('button');
                thumbButton.type = 'button';
                thumbButton.className = 'block w-full text-center group';
                thumbButton.dataset.page = String(pageNumber);
                thumbButton.setAttribute('aria-label', 'Go to page ' + pageNumber);

                const thumbCanvas = document.createElement('canvas');
                thumbCanvas.className = 'mx-auto bg-white border border-eac-border shadow-sm group-hover:border-eac-red transition';
                const label = document.createElement('span');
                label.className = 'inline-flex items-center justify-center mt-1 min-w-6 h-5 rounded text-xs font-bold text-eac-text-light group-hover:text-eac-red';
                label.textContent = String(pageNumber);
                thumbButton.append(thumbCanvas, label);
                thumbnailList.appendChild(thumbButton);

                thumbButton.addEventListener('click', () => {
                    currentPage = pageNumber;
                    renderPage(currentPage);
                });

                const page = await pdfDoc.getPage(pageNumber);
                const viewport = page.getViewport({ scale: 0.16 });
                const context = thumbCanvas.getContext('2d');
                thumbCanvas.width = Math.ceil(viewport.width);
                thumbCanvas.height = Math.ceil(viewport.height);
                await page.render({ canvasContext: context, viewport }).promise;
            }
        }

        function setActiveThumbnail(pageNumber) {
            thumbnailList.querySelectorAll('button[data-page]').forEach((button) => {
                const active = button.dataset.page === String(pageNumber);
                const canvas = button.querySelector('canvas');
                const label = button.querySelector('span');
                if (canvas) {
                    canvas.classList.toggle('border-eac-red', active);
                    canvas.classList.toggle('border-eac-border', !active);
                }
                if (label) {
                    label.classList.toggle('bg-eac-red', active);
                    label.classList.toggle('text-white', active);
                    label.classList.toggle('text-eac-text-light', !active);
                }
            });
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
        toolbar.pageInput.addEventListener('change', () => {
            if (!pdfDoc) return;
            const requestedPage = Number.parseInt(toolbar.pageInput.value, 10);
            if (Number.isNaN(requestedPage)) {
                toolbar.pageInput.value = currentPage;
                return;
            }
            currentPage = Math.min(Math.max(requestedPage, 1), pdfDoc.numPages);
            renderPage(currentPage);
        });
        toolbar.zoomInBtn.addEventListener('click', () => {
            scale = Math.min(scale + 0.2, 3);
            toolbar.zoomLabel.textContent = Math.round(scale * 100) + '%';
            renderPage(currentPage);
        });
        toolbar.zoomOutBtn.addEventListener('click', () => {
            scale = Math.max(scale - 0.2, 0.4);
            toolbar.zoomLabel.textContent = Math.round(scale * 100) + '%';
            renderPage(currentPage);
        });

        return {
            toolbar,
            setDoc(doc) {
                pdfDoc = doc;
                currentPage = 1;
                toolbar.pageInput.max = doc.numPages;
                toolbar.zoomLabel.textContent = Math.round(scale * 100) + '%';
            },
            renderFirstPage: async () => {
                await renderThumbnails();
                await renderPage(1);
            }
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
            canvasWrapClass: 'min-h-0 max-h-none overflow-auto flex justify-center items-start bg-eac-bg/70 p-4 sm:p-8'
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
        bar.className = 'flex flex-col lg:flex-row lg:items-center justify-between gap-3 px-4 sm:px-5 py-3 border-b border-eac-border bg-white text-sm shrink-0';

        const status = document.createElement('span');
        status.className = 'text-eac-text-light';

        const controls = document.createElement('div');
        controls.className = 'hidden flex flex-wrap items-center gap-2';

        const prevBtn = iconButton('‹', 'Previous page');
        const pageLabel = document.createElement('span');
        pageLabel.className = 'text-eac-text px-3 py-1.5 tabular-nums min-w-[4.5rem] text-center rounded-lg border border-eac-border bg-white font-semibold';
        const nextBtn = iconButton('›', 'Next page');
        const divider = document.createElement('span');
        divider.className = 'w-px h-5 bg-eac-border mx-1';
        const zoomOutBtn = iconButton('−', 'Zoom out');
        const zoomLabel = document.createElement('span');
        zoomLabel.className = 'text-eac-text tabular-nums min-w-[3.5rem] text-center font-semibold';
        const zoomInBtn = iconButton('+', 'Zoom in');
        const pageInput = document.createElement('input');
        pageInput.type = 'number';
        pageInput.min = '1';
        pageInput.value = '1';
        pageInput.setAttribute('aria-label', 'Current page');
        pageInput.className = 'w-14 h-9 text-center rounded-lg border border-eac-border text-eac-text font-semibold focus:outline-none focus:ring-2 focus:ring-eac-red/20 focus:border-eac-red';

        controls.append(zoomOutBtn, zoomLabel, zoomInBtn, divider, prevBtn, pageInput, nextBtn, pageLabel);
        if (extraButtons && extraButtons.length > 0) {
            const extraDivider = document.createElement('span');
            extraDivider.className = 'w-px h-5 bg-eac-border mx-1';
            controls.append(extraDivider, ...extraButtons);
        }
        bar.append(status, controls);
        container.appendChild(bar);

        return { bar, status, controls, prevBtn, nextBtn, pageLabel, pageInput, zoomLabel, zoomInBtn, zoomOutBtn };
    }

    function iconButton(label, ariaLabel) {
        const button = document.createElement('button');
        button.type = 'button';
        button.setAttribute('aria-label', ariaLabel);
        button.textContent = label;
        button.className = 'w-9 h-9 flex items-center justify-center rounded-lg border border-eac-border text-eac-red hover:bg-eac-red-soft transition font-bold text-lg';
        return button;
    }

    window.PdfPreview = { init: initPdfPreview, openFullscreen: openFullscreenPdfPreview };
})(window, document);
