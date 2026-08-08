/**
 * Shared calendar-day helpers.
 *
 * Loaded by every page that decides whether something is "due" or "today".
 * It exists as a shared file rather than a copy per page because the two
 * memorization entry points drifted apart exactly once: the dashboard moved to
 * the reader's local day while the reader kept a UTC boundary, so the same
 * passage could be "Scheduled for later" in one place and "due today" in the
 * other. One definition makes that divergence impossible rather than merely
 * unlikely.
 */
(function (global) {
    'use strict';

    /**
     * Calendar date of a Date in the *browser's* zone, as YYYY-MM-DD.
     *
     * toISOString() converts to UTC first, so west of UTC an evening rolls the
     * date forward and east of UTC a local midnight rolls it back. Server dates
     * we compare against (memorization nextReviewAt, activity heatmap buckets)
     * are calendar dates in the reader's zone, so these must be built the same way.
     */
    function localIsoDate(date) {
        const d = date || new Date();
        const m = String(d.getMonth() + 1).padStart(2, '0');
        const day = String(d.getDate()).padStart(2, '0');
        return `${d.getFullYear()}-${m}-${day}`;
    }

    /** The reader's today, as YYYY-MM-DD. */
    function todayIso() {
        return localIsoDate(new Date());
    }

    /**
     * Whether a memorization entry is due. A null nextReviewAt means never
     * reviewed, which counts as due — the dashboard and the reader must agree.
     */
    function isEntryDue(entry, today) {
        if (!entry || !entry.nextReviewAt) return true;
        return entry.nextReviewAt <= (today || todayIso());
    }

    global.KjvDate = { localIsoDate, todayIso, isEntryDue };
})(window);
