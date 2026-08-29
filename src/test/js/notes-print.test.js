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

test('document.title stays the note title after print() returns; restore on afterprint', () => {
    const listeners = [];
    const doc = {
        title: 'Notes — KJV Bible Reader',
        defaultView: {
            addEventListener(type, fn) { listeners.push({ type, fn }); },
            removeEventListener(type, fn) {
                const i = listeners.findIndex(l => l.type === type && l.fn === fn);
                if (i >= 0) listeners.splice(i, 1);
            }
        }
    };
    notes.runPrintWithTitle(doc, 'The New Birth', () => {
        assert.equal(doc.title, 'The New Birth');
    });
    assert.equal(doc.title, 'The New Birth', 'Safari Save-as-PDF still needs the note title');
    const after = listeners.find(l => l.type === 'afterprint');
    assert.ok(after, 'afterprint listener registered');
    after.fn();
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

test('Edit during pending hydrate then save-same-note does not enable Print until current body is ready', () => {
    let liveRun = 0;
    liveRun = notes.startHydrationRun(liveRun);
    const firstOpen = liveRun;
    assert.equal(notes.isLiveHydrationRun(firstOpen, liveRun), true);

    // Same note: Edit does not bump openNoteGen; save replaces the view body
    // and starts a second hydrate.
    liveRun = notes.startHydrationRun(liveRun);
    const afterSave = liveRun;
    assert.equal(notes.isLiveHydrationRun(firstOpen, liveRun), false);
    assert.equal(notes.isLiveHydrationRun(afterSave, liveRun), true);

    const staleFinish = notes.printStateAfterHydrationFinish({
        startedRun: firstOpen,
        liveRun,
        inView: true,
        currentEmbedsPending: true,
        alreadyDone: false
    });
    assert.equal(staleFinish.disabled, true);
    assert.equal(staleFinish.title, notes.TITLE_LOADING);

    const liveInFlight = notes.printButtonState({
        inView: true,
        hydrationDone: false,
        embedsPending: true
    });
    assert.equal(liveInFlight.disabled, true);

    const liveReady = notes.printStateAfterHydrationFinish({
        startedRun: afterSave,
        liveRun,
        inView: true,
        currentEmbedsPending: false,
        alreadyDone: false
    });
    assert.equal(liveReady.disabled, false);
    assert.equal(liveReady.title, notes.TITLE_PRINT);
});
