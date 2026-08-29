'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const links = require('../../main/resources/static/note-links.js');

test('parse/serialize e-token is a twin of v-token', () => {
    const parsed = links.parseToken('[e=14625-14627,14630]');
    assert.equal(parsed.embed, true);
    assert.deepEqual(parsed.ranges, [
        { from: 14625, to: 14627 },
        { from: 14630, to: 14630 }
    ]);
    assert.equal(links.serializeEToken(parsed.ranges), '[e=14625-14627,14630]');
    assert.equal(links.serializeVToken(parsed.ranges), '[v=14625-14627,14630]');
    assert.deepEqual(links.parseToken('[v=14625-14627,14630]').ranges, parsed.ranges);
    assert.equal(links.parseToken('[v=14625-14627,14630]').embed, false);
    assert.equal(links.isEmbedToken('[e=14625]'), true);
    assert.equal(links.isEmbedToken('[v=14625]'), false);
});

test('render stores ids only — no KJV text in the markup until hydrate', () => {
    const html = links.renderEmbedHtml('14625-14627');
    assert.match(html, /data-v="14625-14627"/);
    assert.match(html, /note-scripture-embed/);
    assert.match(html, /note-range-link/);
    assert.match(html, /note-scripture-embed-text/);
    assert.doesNotMatch(html, /For God so loved/);
    assert.doesNotMatch(html, /In the beginning/);
    const emptyText = html.match(/note-scripture-embed-text"><\/div>/);
    assert.ok(emptyText, 'embed text slot starts empty');
});

test('insert checkbox flag writes [e=] or [v=]', () => {
    const ranges = [{ from: 26136, to: 26136 }];
    const off = links.tokenFromRanges(ranges, false);
    assert.deepEqual(off, { ok: true, token: '[v=26136]' });
    const on = links.tokenFromRanges(ranges, true);
    assert.deepEqual(on, { ok: true, token: '[e=26136]' });
    const fromKey = links.tokenFromNaturalKey('26136:26138', true);
    assert.deepEqual(fromKey, { ok: true, token: '[e=26136-26138]' });
});

test('save-normalize uses the same flag — typed refs become [e=] when embed is on', () => {
    const john316 = [{ from: 26136, to: 26136 }];
    const john316to18 = [{ from: 26136, to: 26138 }];
    assert.equal(links.tokenFromRanges(john316, false).token, '[v=26136]');
    assert.equal(links.tokenFromRanges(john316, true).token, '[e=26136]');
    assert.equal(links.tokenFromRanges(john316to18, true).token, '[e=26136-26138]');
    assert.equal(links.isStoredPointerInner('e=26136'), true);
    assert.equal(links.isStoredPointerInner('v=26136'), true);
    assert.equal(links.isStoredPointerInner('John 3:16'), false);
});

test('12-verse cap refuses embed and does not truncate', () => {
    const twelve = [{ from: 1, to: 12 }];
    const thirteen = [{ from: 1, to: 13 }];
    const splitThirteen = [{ from: 1, to: 10 }, { from: 20, to: 22 }];
    assert.equal(links.tokenFromRanges(twelve, true).ok, true);
    assert.equal(links.tokenFromRanges(twelve, true).token, '[e=1-12]');

    const refused = links.tokenFromRanges(thirteen, true);
    assert.equal(refused.ok, false);
    assert.match(refused.error, /12/);
    assert.match(refused.error, /13/);
    assert.equal(refused.token, undefined);
    assert.deepEqual(thirteen, [{ from: 1, to: 13 }]);

    const refusedSplit = links.tokenFromRanges(splitThirteen, true);
    assert.equal(refusedSplit.ok, false);
    assert.match(refusedSplit.error, /13/);

    // [v=] is not capped at 12
    assert.equal(links.tokenFromRanges(thirteen, false).token, '[v=1-13]');
});

test('hydrate fills verse text from the API payload, not from the stored token', () => {
    const html = links.renderEmbedHtml('[e=26136]');
    assert.doesNotMatch(html, /For God so loved the world/);
    const filled = links.verseTextsHtml([
        { verse: 16, text: 'For God so loved the world' }
    ]);
    assert.match(filled, /For God so loved the world/);
    assert.match(filled, /<sup>16<\/sup>/);
    assert.doesNotMatch(html, /For God so loved the world/);
});
