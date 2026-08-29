/**
 * Omitted-verse cue for scoped/range lists.
 *
 * Skips are detected from verse ids (range mode is one blob, passageStarts:[0]).
 * Consecutive ids across a chapter boundary are not a skip. Collection member
 * starts already have a passage heading — those are not "omitted verses."
 */
(function (global) {
    'use strict';

    function isVerseRangeSkip(prevVerse, verse) {
        return !!(prevVerse && verse && prevVerse.id + 1 !== verse.id);
    }

    function isCollectionMemberStart(col, verse) {
        if (!col || col.kind !== 'collection' || !verse || verse._ci == null) {
            return false;
        }
        const starts = col.passageStarts || [];
        return starts.indexOf(verse._ci) !== -1;
    }

    /**
     * Prior verse in the full scoped list. The first verse of the session
     * (_ci === 0) has none — never a gap there.
     */
    function scopedPredecessor(col, verse) {
        if (!col || !col.verses || !verse || verse._ci == null || verse._ci <= 0) {
            return null;
        }
        return col.verses[verse._ci - 1] || null;
    }

    /**
     * Verse to compare against for verses[index]. In-page neighbor wins;
     * otherwise the prior collection verse so a later page still sees a skip
     * that began at the previous page's last verse.
     */
    function predecessorForRender(col, verses, index) {
        if (!verses || index < 0 || index >= verses.length) return null;
        if (index > 0) return verses[index - 1];
        return scopedPredecessor(col, verses[0]);
    }

    function shouldInsertOmissionGap(prevVerse, verse, col) {
        if (!isVerseRangeSkip(prevVerse, verse)) return false;
        if (isCollectionMemberStart(col, verse)) return false;
        return true;
    }

    /** Markup both the linear header path and the scoped column path emit. */
    function omissionGapHtml(prevVerse, verse, col) {
        if (!shouldInsertOmissionGap(prevVerse, verse, col)) return '';
        return '<div class="verse-range-gap" role="separator" aria-label="Omitted verses"></div>';
    }

    const api = {
        isVerseRangeSkip,
        isCollectionMemberStart,
        scopedPredecessor,
        predecessorForRender,
        shouldInsertOmissionGap,
        omissionGapHtml
    };

    if (typeof module !== 'undefined' && module.exports) {
        module.exports = api;
    }
    global.KjvVerseRangeGap = api;
})(typeof window !== 'undefined' ? window : globalThis);
