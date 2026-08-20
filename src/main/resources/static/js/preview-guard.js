/**
 * preview-guard.js — casual-copying deterrents for file preview pages.
 *
 * HONEST SCOPE, read this before changing anything:
 *   - This raises friction for the common "right click -> Save As" /
 *     "Ctrl+P -> Save as PDF" / "open devtools -> grab the blob URL" moves.
 *   - It does NOT and CANNOT prevent screenshots (Print Screen, Snipping
 *     Tool, Windows+Shift+S, or a phone camera pointed at the monitor are
 *     all invisible to a web page - no browser exposes that event to JS).
 *   - The devtools check is a window-size heuristic. It is beatable (open
 *     devtools undocked before the page loads, resize timing, a different
 *     browser, disabling JS first) and can false-positive on unusual
 *     window/zoom setups. It reloads the page, not "locks" anything - a
 *     false positive costs a refresh, nothing more.
 *   - Once pixels are on someone's screen, nothing client-side can stop
 *     them from being captured. Nobody's script actually does - this one
 *     doesn't pretend to either. Treat this as a speed bump, not a wall.
 *
 * Usage: include this script on a preview page and call:
 *   PreviewGuard.init({ exempt: false });
 * "exempt: true" (server-decided, e.g. admin/moderator) skips the
 * devtools-reload reaction only - right-click/print/select guards still
 * apply everywhere, since they cost legitimate staff nothing.
 */
(function (window, document) {
    'use strict';

    function blockContextMenuAndSelection(root) {
        root.addEventListener('contextmenu', function (e) {
            e.preventDefault();
        });
        root.style.userSelect = 'none';
        root.style.webkitUserSelect = 'none';
        root.addEventListener('dragstart', function (e) {
            e.preventDefault();
        });
    }

    function blockPrintAndSaveShortcuts(root) {
        window.addEventListener('beforeprint', function (e) {
            root.classList.add('preview-guard-printing');
        });
        window.addEventListener('afterprint', function () {
            root.classList.remove('preview-guard-printing');
        });

        document.addEventListener('keydown', function (e) {
            var key = (e.key || '').toLowerCase();
            var isCtrlOrCmd = e.ctrlKey || e.metaKey;

            // Ctrl/Cmd+P (print) and Ctrl/Cmd+S (save page)
            if (isCtrlOrCmd && (key === 'p' || key === 's')) {
                e.preventDefault();
                showToast('Printing and saving this page is disabled. Request access to download the original file.');
                return false;
            }
        }, true);

        var originalPrint = window.print;
        window.print = function () {
            showToast('Printing is disabled for this preview.');
        };
    }

    var toastTimer = null;
    function showToast(message) {
        var toast = document.getElementById('preview-guard-toast');
        if (!toast) {
            toast = document.createElement('div');
            toast.id = 'preview-guard-toast';
            toast.setAttribute('role', 'status');
            toast.style.cssText = [
                'position:fixed', 'left:50%', 'bottom:24px', 'transform:translateX(-50%)',
                'background:#1a1a1a', 'color:#fff', 'padding:10px 18px', 'border-radius:8px',
                'font:500 14px/1.4 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif',
                'box-shadow:0 4px 16px rgba(0,0,0,.25)', 'z-index:2147483647', 'max-width:90vw',
                'text-align:center', 'transition:opacity .2s', 'opacity:0'
            ].join(';');
            document.body.appendChild(toast);
        }
        toast.textContent = message;
        requestAnimationFrame(function () { toast.style.opacity = '1'; });
        clearTimeout(toastTimer);
        toastTimer = setTimeout(function () {
            toast.style.opacity = '0';
        }, 3000);
    }

    // Best-effort devtools-open heuristic: compares outer/inner window size
    // deltas, which grow when a docked devtools panel eats viewport space.
    // Not reliable for undocked/separate-window devtools, and can misfire
    // on heavily zoomed browsers or unusual window managers - that's why
    // it reloads (cheap, recoverable) instead of doing anything destructive.
    function watchForDevtools(onSuspectedOpen) {
        var threshold = 160;
        var wasOpen = false;
        setInterval(function () {
            var widthDelta = window.outerWidth - window.innerWidth;
            var heightDelta = window.outerHeight - window.innerHeight;
            var isOpen = widthDelta > threshold || heightDelta > threshold;
            if (isOpen && !wasOpen) {
                onSuspectedOpen();
            }
            wasOpen = isOpen;
        }, 800);
    }

    function init(options) {
        options = options || {};
        var root = document.body;

        blockContextMenuAndSelection(root);
        blockPrintAndSaveShortcuts(root);

        if (!options.exempt) {
            watchForDevtools(function () {
                // Reload rather than anything harsher - a false positive
                // (unusual zoom, split-screen window manager, etc.) just
                // costs the viewer a refresh, not their work or a lockout.
                window.location.reload();
            });
        }
    }

    window.PreviewGuard = { init: init };
})(window, document);
