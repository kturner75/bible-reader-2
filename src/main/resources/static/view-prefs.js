/**
 * View preferences — the app's one place for remembering how the reader likes
 * the screen arranged.
 *
 * Project rule (see CLAUDE.md "Remembering what the reader chose"):
 *
 *   Trivial, per-device view state — a collapsed section, a chosen tab, a sort
 *   order, font size, playback speed — lives in localStorage and is restored on
 *   the next visit. The reader should never have to redo the same clicks to get
 *   back to the view they already asked for.
 *
 *   Content — notes, bookmarks, tags, collections, reading position, review
 *   schedules — lives in the database, because it has to follow the reader to
 *   another device. localStorage is a cache for those, never the only copy.
 *
 * The dividing question: if this were lost when they opened the app on another
 * machine, would they be *mildly annoyed* or would they have *lost work*?
 * Annoyed → here. Lost work → the database.
 *
 * Values are JSON-encoded, so booleans and strings round-trip as themselves.
 * Every access is wrapped: Safari private mode throws on setItem, and a
 * forgotten preference must degrade to the default, never break the page.
 */
(function () {
    'use strict';

    function get(key, fallback) {
        try {
            const raw = localStorage.getItem(key);
            if (raw === null) return fallback;
            return JSON.parse(raw);
        } catch (_) {
            return fallback;
        }
    }

    function set(key, value) {
        try {
            localStorage.setItem(key, JSON.stringify(value));
        } catch (_) {
            /* private mode / quota — the session still works, it just forgets */
        }
    }

    /**
     * Wire a <details> to its remembered open/closed state.
     *
     * `fallbackOpen` is a *first-visit* default, not standing policy — a rule like
     * "open All lanes when nothing is scheduled today" applies only until the
     * reader has expressed a preference, after which their choice wins on every
     * visit. Requires an `id` (that is the storage key).
     *
     * Safe to call on every render, and it must be called on every render:
     * renderers that re-derive `open` would otherwise stomp the stored choice.
     * Never assign `.open` directly on a bound element.
     *
     * Programmatic changes are deliberately not recorded — a derived default must
     * not masquerade as a decision the reader made. That is what
     * `pendingProgrammatic` guards: `toggle` fires asynchronously, so a flag reset
     * on the next line would happen long before the event ever arrives.
     */
    function bindDisclosure(el, fallbackOpen) {
        if (!el || !el.id) return;
        const key = 'kjv_disclosure_' + el.id;

        if (!el.dataset.disclosureBound) {
            el.dataset.disclosureBound = '1';
            el.addEventListener('toggle', () => {
                if (el.pendingProgrammatic) {
                    el.pendingProgrammatic = false;
                    return;
                }
                set(key, el.open);
            });
        }

        const stored  = get(key, null);
        const desired = typeof stored === 'boolean' ? stored : fallbackOpen;
        if (el.open !== desired) {
            el.pendingProgrammatic = true;
            el.open = desired;
        }
    }

    window.KjvViewPrefs = { get, set, bindDisclosure };
})();
