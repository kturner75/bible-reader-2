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
     * path + search to assign, or null. Hash, credentials, and other hosts
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

    const api = {
        STORAGE_KEY,
        notesHrefFor,
        parseHref,
        stage,
        consume
    };

    if (typeof module !== 'undefined' && module.exports) {
        module.exports = api;
    }
    global.KjvNotesReturn = api;
})(typeof window !== 'undefined' ? window : globalThis);
