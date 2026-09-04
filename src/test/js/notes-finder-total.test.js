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

test('mutations while filtered refresh the denominator', () => {
    let total = notes.seedFinderTotalNotes(4);
    total = notes.bumpFinderTotalAfterCreate(total);
    assert.equal(total, 5);
    total = notes.bumpFinderTotalAfterDelete(total);
    assert.equal(total, 4);
    total = notes.bumpFinderTotalAfterDelete(0);
    assert.equal(total, 0);
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
    const refresh = notesSrc.match(/async function refreshNotes\(\)[\s\S]*?^    \}/m);
    assert.ok(refresh, 'refreshNotes present');
    const body = refresh[0];
    const applyAt = body.indexOf('applyFinderTotalNotes');
    const seqReturnAt = body.indexOf('if (seq !== searchSeq) return');
    assert.ok(applyAt !== -1, 'refreshNotes uses applyFinderTotalNotes');
    assert.ok(seqReturnAt !== -1, 'refreshNotes still seq-guards the list paint');
    assert.ok(applyAt < seqReturnAt, 'unfiltered total must apply before seq discard');
});

test('leaving the finder clears search/book/updated filter state', () => {
    assert.match(notesSrc, /function resetFinderFiltersOnLeave\(/,
        'resetFinderFiltersOnLeave helper must exist');
    assert.match(notesSrc, /finderQuery\s*=\s*''/,
        'reset clears finderQuery');
    assert.match(notesSrc, /finderBookId\s*=\s*''/,
        'reset clears finderBookId');
    assert.match(notesSrc, /finderWindow\s*=\s*''/,
        'reset clears finderWindow');

    const showWorkspace = notesSrc.match(/function showWorkspace\(\)[\s\S]*?^    \}/m);
    assert.ok(showWorkspace, 'showWorkspace present');
    assert.match(showWorkspace[0], /resetFinderFiltersOnLeave\(\)/,
        'showWorkspace clears filters on leave');

    const showPaneEmpty = notesSrc.match(/function showPaneEmpty\(\)[\s\S]*?^    \}/m);
    assert.ok(showPaneEmpty, 'showPaneEmpty present');
    assert.match(showPaneEmpty[0], /resetFinderFiltersOnLeave\(\)/,
        'showPaneEmpty clears filters when returning to finder');

    const openSermonNote = notesSrc.match(/async function openSermonNote\([\s\S]*?^    \}/m);
    assert.ok(openSermonNote, 'openSermonNote present');
    assert.match(openSermonNote[0], /resetFinderFiltersOnLeave\(\)/,
        'openSermonNote clears filters when leaving the finder');
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

    const refresh = notesSrc.match(/async function refreshNotes\(\)[\s\S]*?^    \}/m);
    assert.ok(refresh, 'refreshNotes present');
    assert.match(refresh[0], /if \(filtered\) refreshFinderTotal\(\)/,
        'filtered refreshNotes must fire the parallel total fetch');
});

