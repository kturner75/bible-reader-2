/**
 * Same-origin return from /notes into the reader.
 *
 * /notes navigates with a full page load (location.href = /read/range?…).
 * The reader boots that as a deep link (push:false), so rememberScopedReturn
 * would otherwise clear. This key carries the note URL across that load;
 * the reader consumes it once so a later reload of /read/range is a normal
 * deep link.
 *
 * Only /notes or /notes?… on this origin are accepted — never an open redirect.
 * In-app Back pops the reader when the previous document was /notes; otherwise
 * it replaces the current entry. Never assign — that stacks a second /notes
 * on top of the range.
 */
(function (global) {
    'use strict';

    const STORAGE_KEY = 'kjv_notes_return';

    function defaultStorage() {
        return typeof sessionStorage !== 'undefined' ? sessionStorage : null;
    }

    function notesHrefFor(editingNoteId) {
        if (editingNoteId == null || editingNoteId === '') return '/notes';
        return '/notes?' + new URLSearchParams({ id: String(editingNoteId) }).toString();
    }

    /**
     * Allow only a same-origin /notes path (optional query). Returns the
     * path + search to return to, or null. Hash, credentials, and other hosts
     * are rejected.
     */
    function parseHref(raw, origin) {
        if (typeof raw !== 'string') return null;
        const trimmed = raw.trim();
        if (!trimmed) return null;
        const base = origin || (typeof location !== 'undefined' && location.origin);
        if (!base) return null;
        let url;
        try {
            url = new URL(trimmed, base);
        } catch (_) {
            return null;
        }
        if (url.origin !== base) return null;
        if (url.username || url.password) return null;
        if (url.pathname !== '/notes') return null;
        return url.pathname + url.search;
    }

    function stage(editingNoteId, storage) {
        const store = storage || defaultStorage();
        if (!store) return;
        try {
            store.setItem(STORAGE_KEY, notesHrefFor(editingNoteId));
        } catch (_) {
            /* Safari private mode / quota — navigation still works */
        }
    }

    /** Peek without consuming — true only when a valid /notes return is staged. */
    function hasStaged(origin, storage) {
        const store = storage || defaultStorage();
        if (!store) return false;
        try {
            return parseHref(store.getItem(STORAGE_KEY), origin) != null;
        } catch (_) {
            return false;
        }
    }

    /**
     * Read and clear the staged return. Invalid or missing values yield null.
     * Always clears so a later /read/range reload is a normal deep link.
     */
    function consume(origin, storage) {
        const store = storage || defaultStorage();
        let raw = null;
        if (store) {
            try {
                raw = store.getItem(STORAGE_KEY);
                store.removeItem(STORAGE_KEY);
            } catch (_) {
                /* private mode */
            }
        }
        const href = parseHref(raw, origin);
        return href ? { href: href, historyPushed: false } : null;
    }

    /**
     * True when this reader entry was reached from /notes and history can
     * pop back there. Deep-link / reload / typed URL have no notes referrer
     * (or a single history entry) — do not history.back() in those cases.
     */
    function canUnwindToNotes(referrer, origin, historyLength) {
        if (typeof historyLength === 'number' && historyLength < 2) return false;
        return parseHref(referrer, origin) != null;
    }

    /**
     * Leave the reader for a same-origin /notes URL.
     * @returns {'back'|'replace'|null}
     */
    function returnToNotes(href, opts) {
        opts = opts || {};
        const notesHref = parseHref(href, opts.origin);
        if (!notesHref) return null;
        const back = opts.back || function () { history.back(); };
        const replace = opts.replace || function (u) { location.replace(u); };
        if (canUnwindToNotes(opts.referrer, opts.origin, opts.historyLength)) {
            back();
            return 'back';
        }
        replace(notesHref);
        return 'replace';
    }

    const api = {
        STORAGE_KEY,
        notesHrefFor,
        parseHref,
        stage,
        hasStaged,
        consume,
        canUnwindToNotes,
        returnToNotes
    };

    if (typeof module !== 'undefined' && module.exports) {
        module.exports = api;
    }
    global.KjvNotesReturn = api;
})(typeof window !== 'undefined' ? window : globalThis);
