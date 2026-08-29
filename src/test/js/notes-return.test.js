'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const ret = require('../../main/resources/static/notes-return.js');

const ORIGIN = 'http://localhost:8081';

function memStorage(initial) {
    const data = { ...(initial || {}) };
    return {
        getItem: (k) => (Object.prototype.hasOwnProperty.call(data, k) ? data[k] : null),
        setItem: (k, v) => { data[k] = String(v); },
        removeItem: (k) => { delete data[k]; },
        _data: data
    };
}

test('notesHrefFor uses /notes or /notes?id=', () => {
    assert.equal(ret.notesHrefFor(null), '/notes');
    assert.equal(ret.notesHrefFor(''), '/notes');
    assert.equal(ret.notesHrefFor('abc-123'), '/notes?id=abc-123');
    assert.equal(ret.notesHrefFor('a b'), '/notes?id=a+b');
});

test('parseHref allows /notes and /notes?… on this origin', () => {
    assert.equal(ret.parseHref('/notes', ORIGIN), '/notes');
    assert.equal(ret.parseHref('/notes?id=abc-123', ORIGIN), '/notes?id=abc-123');
    assert.equal(ret.parseHref('  /notes?id=x  ', ORIGIN), '/notes?id=x');
    assert.equal(
        ret.parseHref(ORIGIN + '/notes?id=abc-123', ORIGIN),
        '/notes?id=abc-123'
    );
    assert.equal(ret.parseHref('/notes?id=x#ignored', ORIGIN), '/notes?id=x');
});

test('parseHref rejects open redirects and non-notes paths', () => {
    assert.equal(ret.parseHref(null, ORIGIN), null);
    assert.equal(ret.parseHref('', ORIGIN), null);
    assert.equal(ret.parseHref('https://evil.example/notes', ORIGIN), null);
    assert.equal(ret.parseHref('//evil.example/notes', ORIGIN), null);
    assert.equal(ret.parseHref('javascript:alert(1)', ORIGIN), null);
    assert.equal(ret.parseHref('/read', ORIGIN), null);
    assert.equal(ret.parseHref('/notes.html', ORIGIN), null);
    assert.equal(ret.parseHref('/notes/', ORIGIN), null);
    assert.equal(ret.parseHref('/NOTES', ORIGIN), null);
    assert.equal(ret.parseHref('/notes/../read', ORIGIN), null);
    assert.equal(ret.parseHref('/read?vid=1', ORIGIN), null);
    assert.equal(ret.parseHref('http://evil.example/notes?id=1', ORIGIN), null);
    assert.equal(ret.parseHref('https://localhost:8081/notes', ORIGIN), null);
});

test('stage then consume yields href + historyPushed false and clears the key', () => {
    const store = memStorage();
    ret.stage('note-42', store);
    assert.equal(store.getItem(ret.STORAGE_KEY), '/notes?id=note-42');

    const first = ret.consume(ORIGIN, store);
    assert.deepEqual(first, { href: '/notes?id=note-42', historyPushed: false, fromNotes: true });
    assert.equal(store.getItem(ret.STORAGE_KEY), null);

    const second = ret.consume(ORIGIN, store);
    assert.equal(second, null);
});

test('stage with no id stores /notes; invalid stored value is consumed and dropped', () => {
    const store = memStorage();
    ret.stage(null, store);
    assert.deepEqual(ret.consume(ORIGIN, store), { href: '/notes', historyPushed: false, fromNotes: true });

    const bad = memStorage({ [ret.STORAGE_KEY]: 'https://evil.example/notes' });
    assert.equal(ret.consume(ORIGIN, bad), null);
    assert.equal(bad.getItem(ret.STORAGE_KEY), null);
});

test('hasStaged peeks without consuming; invalid values are not staged', () => {
    const store = memStorage();
    assert.equal(ret.hasStaged(ORIGIN, store), false);
    ret.stage('n1', store);
    assert.equal(ret.hasStaged(ORIGIN, store), true);
    assert.equal(store.getItem(ret.STORAGE_KEY), '/notes?id=n1');
    ret.consume(ORIGIN, store);
    assert.equal(ret.hasStaged(ORIGIN, store), false);

    const leakedVid = memStorage({ [ret.STORAGE_KEY]: '/read?vid=26136' });
    assert.equal(ret.hasStaged(ORIGIN, leakedVid), false);
    assert.equal(ret.consume(ORIGIN, leakedVid), null);
    assert.equal(leakedVid.getItem(ret.STORAGE_KEY), null);
});

test('canUnwindToNotes only when previous document was /notes and history can pop', () => {
    assert.equal(ret.canUnwindToNotes(ORIGIN + '/notes?id=1', ORIGIN, 2), true);
    assert.equal(ret.canUnwindToNotes('/notes', ORIGIN, 2), true);
    assert.equal(ret.canUnwindToNotes('/notes?id=1', ORIGIN, 1), false);
    assert.equal(ret.canUnwindToNotes('', ORIGIN, 2), false);
    assert.equal(ret.canUnwindToNotes(null, ORIGIN, 2), false);
    assert.equal(ret.canUnwindToNotes(ORIGIN + '/read/range?v=1', ORIGIN, 2), false);
    assert.equal(ret.canUnwindToNotes('https://evil.example/notes', ORIGIN, 2), false);
    assert.equal(ret.canUnwindToNotes('', ORIGIN, 2, true), true);
    assert.equal(ret.canUnwindToNotes('', ORIGIN, 1, true), false);
});

test('in-app Back from a notes-pushed reader unwinds; does not stack the range', () => {
    const history = ['/notes?id=1', '/read/range?v=1'];
    const calls = [];
    const nav = ret.returnToNotes('/notes?id=1', {
        origin: ORIGIN,
        referrer: ORIGIN + '/notes?id=1',
        historyLength: history.length,
        back: () => { calls.push('back'); history.pop(); },
        replace: (u) => { calls.push('replace:' + u); history[history.length - 1] = u; }
    });
    assert.equal(nav, 'back');
    assert.deepEqual(calls, ['back']);
    assert.deepEqual(history, ['/notes?id=1']);
});

test('deep-link /notes return with no notes history entry replaces, never assigns', () => {
    const history = ['/read/range?v=1'];
    const calls = [];
    const nav = ret.returnToNotes('/notes?id=1', {
        origin: ORIGIN,
        referrer: '',
        historyLength: 1,
        back: () => { calls.push('back'); history.pop(); },
        replace: (u) => { calls.push('replace:' + u); history[history.length - 1] = u; }
    });
    assert.equal(nav, 'replace');
    assert.deepEqual(calls, ['replace:/notes?id=1']);
    assert.deepEqual(history, ['/notes?id=1']);

    assert.equal(ret.returnToNotes('https://evil.example/notes', {
        origin: ORIGIN,
        referrer: '',
        historyLength: 1,
        back: () => { calls.push('back'); },
        replace: (u) => { calls.push('replace:' + u); }
    }), null);
    assert.deepEqual(calls, ['replace:/notes?id=1']);
});

test('stripped referrer still unwinds when fromNotes; does not stack notes, notes', () => {
    const history = ['/notes?id=1', '/read/range?v=1'];
    const calls = [];
    const nav = ret.returnToNotes('/notes?id=1', {
        origin: ORIGIN,
        referrer: '',
        historyLength: 2,
        fromNotes: true,
        back: () => { calls.push('back'); history.pop(); },
        replace: (u) => { calls.push('replace:' + u); history[history.length - 1] = u; }
    });
    assert.equal(nav, 'back');
    assert.deepEqual(calls, ['back']);
    assert.deepEqual(history, ['/notes?id=1']);
});

test('stripped referrer without fromNotes replaces once (deep-link, no notes entry)', () => {
    const history = ['/read/range?v=1'];
    const calls = [];
    const nav = ret.returnToNotes('/notes?id=1', {
        origin: ORIGIN,
        referrer: '',
        historyLength: 1,
        fromNotes: true,
        back: () => { calls.push('back'); history.pop(); },
        replace: (u) => { calls.push('replace:' + u); history[history.length - 1] = u; }
    });
    assert.equal(nav, 'replace');
    assert.deepEqual(calls, ['replace:/notes?id=1']);
    assert.deepEqual(history, ['/notes?id=1']);

    const prior = ['/dashboard', '/read/range?v=1'];
    const calls2 = [];
    const nav2 = ret.returnToNotes('/notes?id=1', {
        origin: ORIGIN,
        referrer: '',
        historyLength: 2,
        fromNotes: false,
        back: () => { calls2.push('back'); prior.pop(); },
        replace: (u) => { calls2.push('replace:' + u); prior[prior.length - 1] = u; }
    });
    assert.equal(nav2, 'replace');
    assert.deepEqual(calls2, ['replace:/notes?id=1']);
    assert.deepEqual(prior, ['/dashboard', '/notes?id=1']);
});

test('sessionStorage getter SecurityError degrades to no-return', () => {
    const desc = Object.getOwnPropertyDescriptor(globalThis, 'sessionStorage');
    Object.defineProperty(globalThis, 'sessionStorage', {
        configurable: true,
        get() {
            const err = new Error('The operation is insecure.');
            err.name = 'SecurityError';
            throw err;
        }
    });
    try {
        assert.doesNotThrow(() => ret.stage('blocked'));
        assert.equal(ret.hasStaged(ORIGIN), false);
        assert.equal(ret.consume(ORIGIN), null);
    } finally {
        if (desc) Object.defineProperty(globalThis, 'sessionStorage', desc);
        else delete globalThis.sessionStorage;
    }
});


