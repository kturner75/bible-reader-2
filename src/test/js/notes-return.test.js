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
    assert.deepEqual(first, { href: '/notes?id=note-42', historyPushed: false });
    assert.equal(store.getItem(ret.STORAGE_KEY), null);

    const second = ret.consume(ORIGIN, store);
    assert.equal(second, null);
});

test('stage with no id stores /notes; invalid stored value is consumed and dropped', () => {
    const store = memStorage();
    ret.stage(null, store);
    assert.deepEqual(ret.consume(ORIGIN, store), { href: '/notes', historyPushed: false });

    const bad = memStorage({ [ret.STORAGE_KEY]: 'https://evil.example/notes' });
    assert.equal(ret.consume(ORIGIN, bad), null);
    assert.equal(bad.getItem(ret.STORAGE_KEY), null);
});
