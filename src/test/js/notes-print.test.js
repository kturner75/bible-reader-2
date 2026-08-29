'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const notes = require('../../main/resources/static/notes.js');

const htmlPath = path.join(__dirname, '../../main/resources/static/notes.html');
const notesHtml = fs.readFileSync(htmlPath, 'utf8');

function mockEmbed({ ready, text }) {
    return {
        dataset: { embedReady: ready ? '1' : '' },
        querySelector: (sel) => (sel.includes('text') ? { textContent: text || '' } : null)
    };
}

function mockRoot(embeds) {
    return { querySelectorAll: () => embeds };
}

test('Print button is present in the sermon-note view actions', () => {
    const actions = notesHtml.match(
        /id="sermon-note-view-actions"[\s\S]*?<\/div>/
    );
    assert.ok(actions, 'view actions exist');
    assert.match(actions[0], /id="sermon-note-print-btn"/);
    assert.match(actions[0], /type="button"/);
    assert.match(actions[0], />Print<\/button>/);
    assert.match(actions[0], /id="sermon-note-edit-btn"/);
    assert.match(actions[0], /id="sermon-note-delete-btn"/);
    assert.doesNotMatch(notesHtml, /id="sermon-note-edit"[\s\S]*id="sermon-note-print-btn"/);
});

test('Print is disabled until embeds are ready', () => {
    const pending = notes.viewEmbedsPending(mockRoot([
        mockEmbed({ ready: false, text: '' })
    ]));
    assert.equal(pending, true);

    const loading = notes.printButtonState({
        inView: true,
        hydrationDone: false,
        embedsPending: true
    });
    assert.equal(loading.disabled, true);
    assert.equal(loading.title, notes.TITLE_LOADING);

    const ready = notes.printButtonState({
        inView: true,
        hydrationDone: true,
        embedsPending: false
    });
    assert.equal(ready.disabled, false);
    assert.equal(ready.title, notes.TITLE_PRINT);

    const notInView = notes.printButtonState({
        inView: false,
        hydrationDone: true,
        embedsPending: false
    });
    assert.equal(notInView.disabled, true);
});

test('document.title restores in finally after print, not only afterprint', () => {
    const doc = { title: 'Notes — KJV Bible Reader' };
    let seenDuringPrint = '';
    notes.runPrintWithTitle(doc, 'The New Birth', () => {
        seenDuringPrint = doc.title;
    });
    assert.equal(seenDuringPrint, 'The New Birth');
    assert.equal(doc.title, 'Notes — KJV Bible Reader');

    const throwing = { title: 'Notes — KJV Bible Reader' };
    assert.throws(() => {
        notes.runPrintWithTitle(throwing, 'Psalm 23', () => {
            throw new Error('print failed');
        });
    }, /print failed/);
    assert.equal(throwing.title, 'Notes — KJV Bible Reader');
});

test('failed ranges does not leave Print still-loading forever', () => {
    const failed = notes.printButtonState({
        inView: true,
        hydrationDone: true,
        embedsPending: true
    });
    assert.equal(failed.disabled, false);
    assert.notEqual(failed.title, notes.TITLE_LOADING);
    assert.equal(failed.title, notes.TITLE_UNAVAILABLE);

    const stillEmpty = notes.viewEmbedsPending(mockRoot([
        mockEmbed({ ready: false, text: '' })
    ]));
    assert.equal(stillEmpty, true, 'unready shells stay pending so print CSS can hide them');
});
