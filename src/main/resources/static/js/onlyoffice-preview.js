/**
 * onlyoffice-preview.js — Office document (DOCX/DOC/PPTX/PPT/XLSX/XLS)
 * preview via an ONLYOFFICE Document Server, a separate service this app
 * talks to over HTTP (see OnlyOfficeController/OnlyOfficeService) - not
 * bundled into this app's own WAR/Tomcat deployment.
 *
 * Same "view, don't touch" intent as pdf-preview.js: the config this
 * fetches from the backend is always view-mode with editing/downloading
 * disabled (see OnlyOfficeService.createPreviewConfig) - this file never
 * constructs or trusts any config value itself, it only asks the backend
 * for one and hands it to ONLYOFFICE's own viewer.
 *
 * init(container, { fileId, title }) - fetches /files/{fileId}/onlyoffice-config,
 * dynamically loads the Document Server's viewer script (only once, even if
 * called more than once on the same page), and mounts DocsAPI.DocEditor
 * into `container` (which must have an id - ONLYOFFICE's API takes a
 * container id string, not an element reference). Returns a destroy()
 * function that tears the editor instance down cleanly.
 */
(function (window, document) {
    'use strict';

    let scriptLoadPromise = null;

    function loadOnlyOfficeApi(serverUrl) {
        // Cache the load promise (not just "has it loaded") so opening a
        // second preview on the same page - e.g. closing and reopening the
        // fullscreen modal - reuses the same script tag instead of injecting
        // it twice, which is what actually causes DocsAPI to misbehave.
        if (scriptLoadPromise) {
            return scriptLoadPromise;
        }
        scriptLoadPromise = new Promise((resolve, reject) => {
            if (window.DocsAPI) {
                resolve();
                return;
            }
            const script = document.createElement('script');
            script.src = serverUrl.replace(/\/$/, '') + '/web-apps/apps/api/documents/api.js';
            script.onload = () => resolve();
            script.onerror = () => reject(new Error('Could not load the ONLYOFFICE viewer script from ' + serverUrl));
            document.head.appendChild(script);
        });
        return scriptLoadPromise;
    }

    function renderStatus(container, message) {
        container.innerHTML = '';
        const p = document.createElement('p');
        p.className = 'text-sm text-eac-text-light p-6';
        p.textContent = message;
        container.appendChild(p);
    }

    async function initOnlyOfficePreview(container, options) {
        const { fileId } = options;

        if (!container.id) {
            throw new Error('onlyoffice-preview.js: container element must have an id');
        }

        renderStatus(container, 'Loading preview…');

        let response;
        try {
            // credentials: 'same-origin' - this is an authenticated request
            // (see OnlyOfficeController.config's requireViewable check), not
            // a public URL.
            response = await fetch('/files/' + fileId + '/onlyoffice-config', { credentials: 'same-origin' });
        } catch (err) {
            renderStatus(container, 'Could not reach the preview service. Please try again later.');
            return { destroy() {} };
        }

        if (!response.ok) {
            renderStatus(container, response.status === 404
                ? 'Preview not available for this file type.'
                : 'Could not load the preview. Please try again later.');
            return { destroy() {} };
        }

        const payload = await response.json();

        try {
            await loadOnlyOfficeApi(payload.serverUrl);
        } catch (err) {
            renderStatus(container, 'Could not load the ONLYOFFICE viewer. Please try again later.');
            return { destroy() {} };
        }

        if (!window.DocsAPI || typeof window.DocsAPI.DocEditor !== 'function') {
            renderStatus(container, 'The ONLYOFFICE viewer did not load correctly.');
            return { destroy() {} };
        }

        container.innerHTML = '';
        let editor;
        try {
            editor = new window.DocsAPI.DocEditor(container.id, payload.config);
        } catch (err) {
            renderStatus(container, 'Could not open this document for preview.');
            return { destroy() {} };
        }

        return {
            destroy() {
                if (editor && typeof editor.destroyEditor === 'function') {
                    try {
                        editor.destroyEditor();
                    } catch (err) {
                        // Best-effort cleanup - a failed destroy shouldn't
                        // throw back into whatever caller is closing this
                        // preview (e.g. a modal close handler).
                    }
                }
            }
        };
    }

    /**
     * Opens a full-viewport modal overlay (dark backdrop, the document
     * filling essentially the whole screen) and mounts the ONLYOFFICE
     * viewer into it - same chrome/behavior as PdfPreview.openFullscreen
     * (see pdf-preview.js), and the ONLY way an Office document is shown at
     * all: unlike the PDF viewer, there's no separate inline mode here -
     * ONLYOFFICE's own toolbar/thumbnail rail/status bar need real room to
     * be usable, an inline box on the file-detail page just cramps them.
     * Closes on the × button, clicking the backdrop, or Escape, and always
     * destroys the editor instance on close. Returns a close() function.
     */
    function openFullscreenOnlyOfficePreview(options) {
        const { fileId, title } = options;

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
        closeBtn.setAttribute('aria-label', 'Close preview');
        closeBtn.className = 'shrink-0 w-9 h-9 flex items-center justify-center rounded-full hover:bg-white/10 transition text-xl leading-none';
        closeBtn.textContent = '×';
        header.append(titleEl, closeBtn);

        const viewerHost = document.createElement('div');
        viewerHost.id = 'onlyofficeFullscreenViewer-' + fileId;
        viewerHost.className = 'flex-1 min-h-0 bg-white rounded-xl overflow-hidden';

        overlay.append(header, viewerHost);
        document.body.appendChild(overlay);
        document.body.classList.add('overflow-hidden');

        let instance = null;
        initOnlyOfficePreview(viewerHost, { fileId }).then((result) => {
            instance = result;
        });

        function close() {
            document.body.classList.remove('overflow-hidden');
            document.removeEventListener('keydown', onKeydown);
            if (instance) {
                instance.destroy();
            }
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

        return close;
    }

    window.OnlyOfficePreview = { init: initOnlyOfficePreview, openFullscreen: openFullscreenOnlyOfficePreview };
})(window, document);
