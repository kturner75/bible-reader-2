'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const notes = require('../../main/resources/static/notes.js');

const notesSrcPath = path.join(__dirname, '../../main/resources/static/notes.js');
const notesSrc = fs.readFileSync(notesSrcPath, 'utf8');

/**
 * f8736f2 behavior: totalNotes only written when an unfiltered refresh completes
 * with a still-current searchSeq. Boot left total at 0, so a raced filter paint
 * read "N of 0".
 */
function applyFinderTotalNotesLegacy(prevTotal, { filtered, length, seqStale }) {
    if (seqStale || filtered) return prevTotal;
    return length;
}

test('filtered finder count stays N of total after searchSeq race (not N of 0)', () => {
    const bootNotes = [{ id: 1 }, { id: 2 }, { id: 3 }, { id: 4 }, { id: 5 }];

    // --- f8736f2 path would paint "3 of 0" ---
    let legacyTotal = 0; // boot never seeded
    // showFinder starts unfiltered refresh; a quick filter bumps searchSeq first
    legacyTotal = applyFinderTotalNotesLegacy(legacyTotal, {
        filtered: false,
        length: bootNotes.length,
        seqStale: true // discarded — the only write never lands
    });
    legacyTotal = applyFinderTotalNotesLegacy(legacyTotal, {
        filtered: true,
        length: 3,
        seqStale: false
    });
    const legacyLabel = notes.formatFinderCount({
        filtered: true,
        shown: 3,
        total: legacyTotal
    });
    assert.equal(legacyLabel, '3 of 0 notes', 'documents the f8736f2 failure mode');

    // --- fixed path ---
    let total = notes.seedFinderTotalNotes(bootNotes);
    assert.equal(total, 5);

    // Unfiltered response still writes the denominator even when seq is stale
    total = notes.applyFinderTotalNotes(total, { filtered: false, length: 5 });
    assert.equal(total, 5);

    // Filtered response must not clobber the denominator
    total = notes.applyFinderTotalNotes(total, { filtered: true, length: 3 });
    assert.equal(total, 5);

    assert.equal(
        notes.formatFinderCount({ filtered: true, shown: 3, total }),
        '3 of 5 notes'
    );
});

test('deleting while filtered lowers the denominator locally', () => {
    // Safe to apply locally because the delete bump runs *before* any post-delete fetch;
    // the create case cannot do the same, see below.
    let total = notes.seedFinderTotalNotes(4);
    total = notes.bumpFinderTotalAfterDelete(total);
    assert.equal(total, 3);
    total = notes.bumpFinderTotalAfterDelete(0);
    assert.equal(total, 0);
});

test('creating a note does not double-count the denominator', () => {
    // refreshNotes fires the unfiltered total in parallel, and post-create that fetch
    // already counts the new note. A local +1 on top reported "N of M" with M one too
    // high whenever the total landed first — reachable once a filter survives opening
    // the editor, so the create path awaits the authoritative total instead.
    assert.doesNotMatch(notesSrc, /bumpFinderTotalAfterCreate/,
        'no local +1 may be applied on top of the authoritative unfiltered total');

    const refresh = notesSrc.match(/async function refreshNotes\([\s\S]*?^    \}/m);
    assert.ok(refresh, 'refreshNotes present');
    assert.match(refresh[0], /const totalPending = filtered \? refreshFinderTotal\(\) : null/,
        'the parallel total is captured so a caller can wait for it');
    assert.match(refresh[0], /opts && opts\.awaitTotal && totalPending/,
        'refreshNotes can await the total for callers that read it straight after');

    const save = notesSrc.match(/async function saveSermonNote\([\s\S]*?^    \}/m);
    assert.ok(save, 'saveSermonNote present');
    assert.match(save[0], /awaitTotal: true/,
        'the create path waits for the authoritative post-create total');
});

test('boot and refreshNotes keep totalNotes independent of filtered searchSeq', () => {
    assert.match(
        notesSrc,
        /seedFinderTotalNotes\(sermonNotes\)/,
        'boot must seed totalNotes from the unfiltered fetch'
    );
    assert.doesNotMatch(
        notesSrc,
        /let totalNotes\s*=\s*0;/,
        'boot must not leave totalNotes at 0'
    );
    const refresh = notesSrc.match(/async function refreshNotes\([\s\S]*?^    \}/m);
    assert.ok(refresh, 'refreshNotes present');
    const body = refresh[0];
    const applyAt = body.indexOf('applyFinderTotalNotes');
    // The guard is a nested if rather than an early return, so the trailing
    // awaitTotal still runs; the ordering it protects is unchanged.
    const seqGuardAt = body.indexOf('if (seq === searchSeq)');
    assert.ok(applyAt !== -1, 'refreshNotes uses applyFinderTotalNotes');
    assert.ok(seqGuardAt !== -1, 'refreshNotes still seq-guards the list paint');
    assert.ok(applyAt < seqGuardAt, 'unfiltered total must apply before seq discard');
});

test('the finder remembers its query rather than clearing it on leave', () => {
    // Deliberate exception to "persist arrangement; reset queries" — the reader asked for
    // filters to be remembered, with the Clear control as the mitigation. See CLAUDE.md.
    assert.match(notesSrc, /const FILTERS_KEY = 'kjv_notes_filters'/,
        'the query is stored under its own KjvViewPrefs key');
    assert.match(notesSrc, /function storeFilters\(/, 'storeFilters helper must exist');
    assert.match(notesSrc, /function loadStoredFilters\(/, 'loadStoredFilters helper must exist');
    assert.match(notesSrc, /let finderQuery\s*=\s*storedFilters\.q/,
        'boot state comes from the stored query');

    for (const [name, re] of [
        ['showWorkspace', /function showWorkspace\(\)[\s\S]*?^    \}/m],
        ['showPaneEmpty', /function showPaneEmpty\(\)[\s\S]*?^    \}/m],
        ['openSermonNote', /async function openSermonNote\([\s\S]*?^    \}/m]
    ]) {
        const fn = notesSrc.match(re);
        assert.ok(fn, name + ' present');
        assert.doesNotMatch(fn[0], /clearFinderQuery\(\)/,
            name + ' must not clear the query — leaving the finder keeps it');
    }

    const clearFilters = notesSrc.match(/function clearFinderFilters\(\)[\s\S]*?^    \}/m);
    assert.ok(clearFilters, 'clearFinderFilters present');
    assert.match(clearFilters[0], /clearFinderQuery\(\)/,
        'the explicit Clear filters action is what drops the query');
});

test('stored filter values are validated on the way in', () => {
    const load = notesSrc.match(/function loadStoredFilters\(\)[\s\S]*?^    \}/m);
    assert.ok(load, 'loadStoredFilters present');
    assert.match(load[0], /KNOWN_WINDOWS/,
        'an unknown updated-window must fall back rather than wedge the query');
    assert.match(load[0], /\[0-9\]\{1,2\}/,
        'a stored book id must look like a book id');
    assert.match(load[0], /typeof stored\.q === 'string'/,
        'a non-string search value must not reach the query');
});

test('the Clear filters control appears only while something is active', () => {
    assert.match(notesSrc, /function syncClearButton\(/, 'syncClearButton helper must exist');
    const sync = notesSrc.match(/function syncClearButton\(\)[\s\S]*?^    \}/m);
    assert.match(sync[0], /clearBtn\.hidden = !finderIsFiltered\(\)/,
        'the control is the visible signal that results are narrowed');
    assert.match(notesSrc, /clearBtn\.addEventListener\('click', clearFinderFilters\)/,
        'the control is wired to the clear action');
});

test('filtered refreshNotes fires a separate unfiltered total fetch', () => {
    assert.match(notesSrc, /async function refreshFinderTotal\(/,
        'refreshFinderTotal helper must exist');
    assert.match(notesSrc, /let totalSeq\s*=/,
        'totalSeq must be independent of searchSeq');

    const totalFn = notesSrc.match(/async function refreshFinderTotal\(\)[\s\S]*?^    \}/m);
    assert.ok(totalFn, 'refreshFinderTotal present');
    assert.match(totalFn[0], /\+\+totalSeq/, 'total fetch uses totalSeq');
    assert.doesNotMatch(totalFn[0], /searchSeq/,
        'total fetch must not share filtered searchSeq');
    assert.match(totalFn[0], /fetch\('\/api\/sermon-notes'/,
        'total fetch is unfiltered');

    const refresh = notesSrc.match(/async function refreshNotes\([\s\S]*?^    \}/m);
    assert.ok(refresh, 'refreshNotes present');
    assert.match(refresh[0], /filtered \? refreshFinderTotal\(\) : null/,
        'filtered refreshNotes must fire the parallel total fetch, and keep the promise');
});

test('clearing the query drops cached rows and holds the finder blank', () => {
    const reset = notesSrc.match(/function clearFinderQuery\(\)[\s\S]*?^    \}/m);
    assert.ok(reset, 'clearFinderQuery present');
    assert.match(reset[0], /wasFiltered/, 'must detect whether anything was active');
    assert.match(reset[0], /sermonNotes\s*=\s*\[\]/,
        'must drop the filtered rows so they cannot paint as the full corpus');
    assert.match(reset[0], /storeFilters\(\)/, 'clearing is remembered too');
    assert.match(reset[0], /return wasFiltered/,
        'returns whether an unfiltered refresh is needed');

    const showFinder = notesSrc.match(/function showFinder\(\)[\s\S]*?^    \}/m);
    assert.ok(showFinder, 'showFinder present');
    assert.match(showFinder[0], /sermonNotesFiltered !== finderIsFiltered\(\)/,
        'a restored filter at boot must not paint the unfiltered boot list');
    assert.match(showFinder[0], /finderBlank\.hidden\s*=\s*true/,
        'hold must not flash the empty-library blank while refresh is in flight');
    assert.match(showFinder[0], /refreshNotes\(/,
        'showFinder still revalidates with refreshNotes');

    const refresh = notesSrc.match(/async function refreshNotes\([\s\S]*?^    \}/m);
    assert.match(refresh[0], /sermonNotesFiltered = filtered/,
        'refreshNotes records which filter state the rows came from');
});

test('entering workspace loads an unfiltered gutter without clearing remembered filters', () => {
    // Medium on #79: openSermonNote / showWorkspace used to paint the filtered subset in the
    // workspace gutter for the whole edit, with no in-workspace Clear. Prefs must stay so
    // leave-keep still restores the finder query on return.
    const showWorkspace = notesSrc.match(/function showWorkspace\(\)[\s\S]*?^    \}/m);
    assert.ok(showWorkspace, 'showWorkspace present');
    assert.doesNotMatch(showWorkspace[0], /clearFinderQuery\(\)/,
        'entering workspace must not clear remembered filters');
    assert.match(showWorkspace[0], /finderIsFiltered\(\) \|\| sermonNotesFiltered/,
        'gutter refresh runs when prefs are filtered even before sermonNotesFiltered flips');
    assert.match(showWorkspace[0], /refreshWorkspaceGutter\(/,
        'showWorkspace refreshes the gutter from the unfiltered corpus');

    const gutter = notesSrc.match(/async function refreshWorkspaceGutter\(\)[\s\S]*?^    \}/m);
    assert.ok(gutter, 'refreshWorkspaceGutter present');
    assert.doesNotMatch(gutter[0], /clearFinderQuery/,
        'gutter refresh must not clear the remembered query');
    assert.doesNotMatch(gutter[0], /finderQuery\s*=/,
        'gutter refresh must not wipe finderQuery');
    assert.doesNotMatch(gutter[0], /storeFilters\(/,
        'gutter refresh must not rewrite prefs');
    assert.match(gutter[0], /sermonNotesFiltered\s*=\s*false/,
        'gutter rows are recorded as unfiltered so showFinder revalidates on return');
    assert.match(gutter[0], /renderSermonNotesList\(/,
        'gutter repaints from the unfiltered list');
    assert.match(gutter[0], /!finderEl\.hidden/,
        'a bounce back to the finder must not overwrite its pending refresh');
});

test('clearFinderQuery marks the emptied cache as unfiltered', () => {
    const reset = notesSrc.match(/function clearFinderQuery\(\)[\s\S]*?^    \}/m);
    assert.ok(reset, 'clearFinderQuery present');
    assert.match(reset[0], /sermonNotesFiltered\s*=\s*false/,
        'clearing must not leave sermonNotesFiltered true against an empty list');
});

test('entering the workspace cancels a pending debounced search', () => {
    // Typing then opening a card inside the 200ms window used to let refreshNotes fire
    // mid-edit, supersede refreshWorkspaceGutter's request, and leave the gutter showing
    // the filtered subset for the whole edit.
    const showWorkspace = notesSrc.match(/function showWorkspace\(\)[\s\S]*?^    \}/m);
    assert.ok(showWorkspace, 'showWorkspace present');
    assert.match(showWorkspace[0], /clearTimeout\(searchTimer\)/,
        'a pending debounced search must not survive into the workspace');
    // clearTimeout is not enough on its own: once the timer has fired the request is
    // already in flight and stays sequence-current, so its response would still repaint
    // the gutter mid-edit. The sequence bump retires that one too.
    assert.match(showWorkspace[0], /searchSeq\+\+/,
        'a search that already escaped the debounce must be retired by sequence');
    const cancelAt = showWorkspace[0].indexOf('clearTimeout(searchTimer)');
    const gutterAt = showWorkspace[0].indexOf('refreshWorkspaceGutter()');
    assert.ok(gutterAt !== -1, 'showWorkspace still refreshes the gutter when filtered');
    assert.ok(cancelAt < gutterAt,
        'cancel before starting the gutter refresh, or the timer can still supersede it');

    // The query itself is committed synchronously on input, so cancelling the timer
    // drops the pending fetch and not the remembered filter.
    const onInput = notesSrc.match(/searchInput\.addEventListener\('input'[\s\S]*?\}\);/m);
    assert.ok(onInput, 'search input handler present');
    assert.match(onInput[0], /finderQuery = searchInput\.value/, 'query committed on input');
    assert.match(onInput[0], /storeFilters\(\)/, 'query persisted on input');
});

test('in-flight filtered refreshNotes must not paint the gutter after showWorkspace', () => {
    // Prefs already filtered, debounce fired, refreshNotes outstanding, flag still false.
    // Opening a card used to skip the gutter refresh; when the filtered fetch landed it
    // painted via renderSermonNotesList into the workspace for the whole edit.
    const showWorkspace = notesSrc.match(/function showWorkspace\(\)[\s\S]*?^    \}/m);
    assert.ok(showWorkspace, 'showWorkspace present');
    assert.match(showWorkspace[0], /searchSeq\+\+/,
        'must invalidate in-flight refreshNotes so a late filtered paint cannot land');
    assert.match(showWorkspace[0], /finderIsFiltered\(\) \|\| sermonNotesFiltered/,
        'must refresh the unfiltered gutter when prefs are filtered even if the flag lags');

    const bumpAt = showWorkspace[0].indexOf('searchSeq++');
    const gutterAt = showWorkspace[0].indexOf('refreshWorkspaceGutter()');
    assert.ok(bumpAt !== -1 && gutterAt !== -1);
    assert.ok(bumpAt < gutterAt,
        'seq bump before gutter refresh so the in-flight list work is already discarded');

    const refresh = notesSrc.match(/async function refreshNotes\([\s\S]*?^    \}/m);
    assert.ok(refresh, 'refreshNotes present');
    assert.match(refresh[0], /if \(seq === searchSeq\)/,
        'refreshNotes still seq-guards list + gutter paints');
    assert.match(refresh[0], /renderSermonNotesList\(/,
        'the paint path that would trap the gutter is the one being seq-guarded');
});
