'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const notes = require('../../main/resources/static/notes.js');

const htmlPath = path.join(__dirname, '../../main/resources/static/notes.html');
const cssPath = path.join(__dirname, '../../main/resources/static/notes.css');
const notesHtml = fs.readFileSync(htmlPath, 'utf8');
const notesCss = fs.readFileSync(cssPath, 'utf8');

function mockEmbed({ ready, text }) {
    return {
        dataset: { embedReady: ready ? '1' : '' },
        querySelector: (sel) => (sel.includes('text') ? { textContent: text || '' } : null)
    };
}

function mockRoot(embeds) {
    return { querySelectorAll: () => embeds };
}

function mockRangeLink({ body, text, ready, embedCite }) {
    return {
        dataset: ready ? { v: body, labelReady: '1' } : { v: body },
        textContent: text,
        href: '#',
        closest: (sel) => (embedCite && String(sel).includes('embed') ? {} : null)
    };
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

test('Print stays disabled while [v=] labels hydrate, even with no embeds', () => {
    assert.equal(notes.viewEmbedsPending(mockRoot([])), false);
    assert.equal(notes.viewRangeLabelsUnresolved(mockRoot([
        mockRangeLink({ body: '26136-26138', text: '26136-26138' })
    ])), true);

    const hydratingLabels = notes.printButtonState({
        inView: true,
        hydrationDone: false,
        embedsPending: false
    });
    assert.equal(hydratingLabels.disabled, true);
    assert.equal(hydratingLabels.title, notes.TITLE_LOADING);

    const labelsReady = notes.printButtonState({
        inView: true,
        hydrationDone: true,
        embedsPending: false
    });
    assert.equal(labelsReady.disabled, false);
    assert.equal(labelsReady.title, notes.TITLE_PRINT);
});

function mockPrintHost(state) {
    const listeners = [];
    const host = {
        addEventListener(type, fn) { listeners.push({ type, fn }); },
        removeEventListener(type, fn) {
            const i = listeners.findIndex(l => l.type === type && l.fn === fn);
            if (i >= 0) listeners.splice(i, 1);
        },
        hasFocus() { return !!state.focused; }
    };
    const doc = {
        title: 'Notes — KJV Bible Reader',
        get hidden() { return !!state.hidden; },
        get visibilityState() { return state.hidden ? 'hidden' : 'visible'; },
        addEventListener(type, fn) { listeners.push({ type, fn }); },
        removeEventListener: host.removeEventListener,
        defaultView: host
    };
    return {
        doc,
        listeners,
        fire(type) {
            listeners.filter(l => l.type === type).forEach(l => l.fn());
        }
    };
}

test('document.title stays the note title after print() returns; restore on afterprint', () => {
    const afterprintHost = mockPrintHost({ hidden: false, focused: true });
    notes.runPrintWithTitle(afterprintHost.doc, 'The New Birth', () => {
        assert.equal(afterprintHost.doc.title, 'The New Birth');
    });
    assert.equal(afterprintHost.doc.title, 'The New Birth', 'Safari Save-as-PDF still needs the note title');
    const after = afterprintHost.listeners.find(l => l.type === 'afterprint');
    assert.ok(after, 'afterprint listener registered');
    after.fn();
    assert.equal(afterprintHost.doc.title, 'Notes — KJV Bible Reader');

    const state = { hidden: true, focused: false };
    const fallback = mockPrintHost(state);
    const timerHost = mockPrintHost({ hidden: true, focused: false });
    const timers = [];
    timerHost.doc.defaultView.setTimeout = (fn, ms) => { timers.push(ms); return 1; };
    notes.runPrintWithTitle(timerHost.doc, 'Psalm 23', () => {});
    assert.equal(timers.length, 0, 'no wall-clock title restore that can beat a stuck dialog');
    assert.equal(timerHost.doc.title, 'Psalm 23');

    notes.runPrintWithTitle(fallback.doc, 'Psalm 23', () => {});
    assert.equal(fallback.doc.title, 'Psalm 23', 'held after print() if afterprint never comes');
    fallback.fire('visibilitychange');
    fallback.fire('focus');
    fallback.fire('pageshow');
    assert.equal(fallback.doc.title, 'Psalm 23', 'fallback does not restore while print is still open');
    state.hidden = false;
    state.focused = true;
    fallback.fire('focus');
    assert.equal(fallback.doc.title, 'Notes — KJV Bible Reader');

    const throwing = { title: 'Notes — KJV Bible Reader' };
    assert.throws(() => {
        notes.runPrintWithTitle(throwing, 'Psalm 23', () => {
            throw new Error('print failed');
        });
    }, /print failed/);
    assert.equal(throwing.title, 'Notes — KJV Bible Reader');
});

test('failed /api/ranges keeps unresolved [v=] labels out of print', () => {
    const failedLink = mockRangeLink({
        body: '26136-26138',
        text: '26136-26138'
    });
    assert.equal(notes.viewRangeLabelsUnresolved(mockRoot([failedLink])), true);
    notes.applyRangeLabelHydration(failedLink, { ok: false });
    assert.equal(failedLink.dataset.labelFailed, '1');
    assert.notEqual(failedLink.dataset.labelReady, '1');
    assert.equal(failedLink.textContent, '26136-26138', 'on-screen [v=] text stays');
    assert.equal(failedLink.dataset.v, '26136-26138', 'click still has the range body');
    assert.equal(failedLink.href, '#');
    assert.equal(notes.viewRangeLabelsUnresolved(mockRoot([failedLink])), true);

    const readyLink = mockRangeLink({
        body: '26136-26138',
        text: '26136-26138'
    });
    notes.applyRangeLabelHydration(readyLink, { ok: true, label: 'John 3:16–18' });
    assert.equal(readyLink.dataset.labelReady, '1');
    assert.equal(readyLink.textContent, 'John 3:16–18');
    assert.equal(notes.viewRangeLabelsUnresolved(mockRoot([readyLink])), false);

    const afterFail = notes.printButtonState({
        inView: true,
        hydrationDone: true,
        embedsPending: true
    });
    assert.equal(afterFail.disabled, false);
    assert.equal(afterFail.title, notes.TITLE_UNAVAILABLE);

    assert.match(
        notesCss,
        /@media print[\s\S]*\.note-range-link\[data-v\]:not\(\[data-label-ready="1"\]\)/
    );
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
