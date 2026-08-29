'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const gap = require('../../main/resources/static/verse-range-gap.js');

function verse(id, ci) {
    return { id, _ci: ci };
}

function rangeCol(ids) {
    const verses = ids.map((id, i) => verse(id, i));
    return { kind: 'range', passageStarts: [0], verses };
}

function collectionCol(members) {
    const verses = [];
    const passageStarts = [];
    members.forEach(ids => {
        passageStarts.push(verses.length);
        ids.forEach(id => verses.push(verse(id, verses.length)));
    });
    return { kind: 'collection', passageStarts, verses };
}

test('consecutive ids are not a skip — including across a chapter', () => {
    assert.equal(gap.isVerseRangeSkip(verse(100, 0), verse(101, 1)), false);
    assert.equal(gap.shouldInsertOmissionGap(verse(100, 0), verse(101, 1), rangeCol([100, 101])), false);
});

test('first verse of a range still has no gap', () => {
    const col = rangeCol([29348, 29349, 29351]);
    const page = col.verses.slice(0);
    const prev = gap.predecessorForRender(col, page, 0);
    assert.equal(prev, null);
    assert.equal(gap.shouldInsertOmissionGap(prev, page[0], col), false);
});

test('in-page skip within a focused range emits the gap', () => {
    const col = rangeCol([29348, 29349, 29351, 29352]);
    const prev = gap.predecessorForRender(col, col.verses, 2);
    assert.equal(prev.id, 29349);
    assert.equal(gap.shouldInsertOmissionGap(prev, col.verses[2], col), true);
});

test('P2-1: later page that starts at a skipped span still emits the gap', () => {
    const col = rangeCol([29348, 29349, 29351, 29352]);
    const page = col.verses.slice(2);
    assert.equal(page[0]._ci, 2);
    const prev = gap.predecessorForRender(col, page, 0);
    assert.equal(prev.id, 29349);
    assert.equal(gap.shouldInsertOmissionGap(prev, page[0], col), true);
});

test('P2-1: later page that continues consecutive ids does not emit a gap', () => {
    const col = rangeCol([29348, 29349, 29350, 29351]);
    const page = col.verses.slice(2);
    const prev = gap.predecessorForRender(col, page, 0);
    assert.equal(prev.id, 29349);
    assert.equal(gap.shouldInsertOmissionGap(prev, page[0], col), false);
});

test('P2-2: collection member boundary is not an omitted-verses cue', () => {
    const col = collectionCol([[10, 11], [50, 51]]);
    assert.deepEqual(col.passageStarts, [0, 2]);
    const prev = gap.predecessorForRender(col, col.verses, 2);
    assert.equal(prev.id, 11);
    assert.equal(gap.isCollectionMemberStart(col, col.verses[2]), true);
    assert.equal(gap.shouldInsertOmissionGap(prev, col.verses[2], col), false);
});

test('P2-2: skip inside one collection passage still emits the gap', () => {
    const col = collectionCol([[10, 11, 13, 14]]);
    assert.deepEqual(col.passageStarts, [0]);
    const prev = gap.predecessorForRender(col, col.verses, 2);
    assert.equal(prev.id, 11);
    assert.equal(gap.isCollectionMemberStart(col, col.verses[2]), false);
    assert.equal(gap.shouldInsertOmissionGap(prev, col.verses[2], col), true);
});

test('P2-2: repeated or reverse-ordered members are not omitted verses', () => {
    const reverse = collectionCol([[50], [10]]);
    assert.equal(gap.shouldInsertOmissionGap(reverse.verses[0], reverse.verses[1], reverse), false);

    const repeat = collectionCol([[10, 11], [10, 11]]);
    assert.equal(gap.shouldInsertOmissionGap(repeat.verses[1], repeat.verses[2], repeat), false);
});

test('P2-2: page starting at a collection member still suppresses the cue', () => {
    const col = collectionCol([[10, 11], [50, 51]]);
    const page = col.verses.slice(2);
    const prev = gap.predecessorForRender(col, page, 0);
    assert.equal(prev.id, 11);
    assert.equal(gap.shouldInsertOmissionGap(prev, page[0], col), false);
});

test('range kind does not treat passageStarts[0] as a reason to drop later skips', () => {
    const col = rangeCol([29348, 29349, 29351]);
    assert.equal(col.kind, 'range');
    assert.deepEqual(col.passageStarts, [0]);
    const page = col.verses.slice(2);
    const prev = gap.predecessorForRender(col, page, 0);
    assert.equal(gap.isCollectionMemberStart(col, page[0]), false);
    assert.equal(gap.shouldInsertOmissionGap(prev, page[0], col), true);
});

const GAP_MARKUP = /class="verse-range-gap"[^>]*aria-label="Omitted verses"/;

test('linear header path: skip emits the same omission markup as the column path', () => {
    const linear = gap.omissionGapHtml(verse(29349), verse(29351), null);
    const column = gap.omissionGapHtml(verse(29349), verse(29351), rangeCol([29349, 29351]));
    assert.match(linear, GAP_MARKUP);
    assert.equal(linear, column);
    assert.equal(gap.omissionGapHtml(verse(100), verse(101), null), '');
    assert.equal(gap.omissionGapHtml(null, verse(29348, 0), null), '');
});

test('linear header path: collection member start does not emit Omitted verses', () => {
    const col = collectionCol([[10, 11], [50, 51]]);
    const html = gap.omissionGapHtml(col.verses[1], col.verses[2], col);
    assert.equal(html, '');
    assert.equal(gap.shouldInsertOmissionGap(col.verses[1], col.verses[2], col), false);
});

test('linear header path: in-passage skip still emits Omitted verses', () => {
    const col = collectionCol([[10, 11, 13, 14]]);
    const html = gap.omissionGapHtml(col.verses[1], col.verses[2], col);
    assert.match(html, GAP_MARKUP);
    assert.equal(gap.shouldInsertOmissionGap(col.verses[1], col.verses[2], col), true);
});
