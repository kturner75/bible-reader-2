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

test('whole-line insert: [e=] sits alone on its line with no surrounding spaces', () => {
    const empty = links.applyTokenInsert('', 0, 0, '[e=26137]');
    assert.equal(empty.next, '[e=26137]');

    const afterText = links.applyTokenInsert('Intro', 5, 5, '[e=26137]');
    assert.equal(afterText.next, 'Intro\n[e=26137]');
    assert.doesNotMatch(afterText.next, / \[/);

    const midLine = links.applyTokenInsert('hello world', 5, 5, '[e=26137]');
    assert.equal(midLine.next, 'hello\n[e=26137]\nworld');

    const alreadyLined = links.applyTokenInsert('a\n', 2, 2, '[e=26137]');
    assert.equal(alreadyLined.next, 'a\n[e=26137]');

    // [v=] still space-pads inline
    const vInline = links.applyTokenInsert('see', 3, 3, '[v=26137]');
    assert.equal(vInline.next, 'see [v=26137]');
});

test('trailing junk on a bound is not a real range and does not render as an embed', () => {
    assert.throws(() => links.parseToken('[e=1-13junk]'));
    assert.throws(() => links.parseToken('[e=13junk]'));
    assert.throws(() => links.parseRangeBody('1-13junk'));
    assert.throws(() => links.parseRangeBody('13junk'));
    assert.throws(() => links.renderEmbedHtml('[e=1-13junk]'));
    assert.throws(() => links.renderEmbedHtml('[e=13junk]'));

    const rangeHtml = links.renderFlowWithEmbeds('See [e=1-13junk] today', s => s);
    assert.doesNotMatch(rangeHtml, /note-scripture-embed/);
    assert.match(rangeHtml, /1-13junk/);
    assert.match(rangeHtml, /See/);
    assert.match(rangeHtml, /today/);

    const singleHtml = links.renderFlowWithEmbeds('See [e=13junk] today', s => s);
    assert.doesNotMatch(singleHtml, /note-scripture-embed/);
    assert.match(singleHtml, /13junk/);

    // Same skip as Java requireNoteEmbedCap: junk is not a 13-verse embed.
    assert.equal(links.refuseOversizedEmbeds('[e=1-13junk]').ok, true);
    assert.equal(links.refuseOversizedEmbeds('[e=13junk]').ok, true);
});

test('pasted [e=] over 12 verses is refused and not truncated', () => {
    const pasted = 'Notes\n[e=1-13]\nmore';
    const refused = links.refuseOversizedEmbeds(pasted);
    assert.equal(refused.ok, false);
    assert.match(refused.error, /12/);
    assert.match(refused.error, /13/);
    assert.equal(pasted, 'Notes\n[e=1-13]\nmore');

    const okTwelve = links.refuseOversizedEmbeds('[e=1-12]');
    assert.equal(okTwelve.ok, true);
    const vUncapped = links.refuseOversizedEmbeds('[v=1-13]');
    assert.equal(vUncapped.ok, true);
});

test('mid-line pasted [e=] promotes the quote out of the paragraph', () => {
    const html = links.renderFlowWithEmbeds('See [e=26137] today', s => s);
    assert.doesNotMatch(html, /<p>[^<]*<blockquote/);
    assert.doesNotMatch(html, /<blockquote[\s\S]*<\/blockquote>\s*<\/p>/);
    assert.match(html, /<p>See<\/p>/);
    assert.match(html, /<p>today<\/p>/);
    assert.match(html, /note-scripture-embed/);
    assert.match(html, /data-v="26137"/);
    assert.match(html, /See/);
    assert.match(html, /today/);

    const whole = links.renderFlowWithEmbeds('[e=26137]', s => s);
    assert.match(whole, /note-scripture-embed/);
    assert.doesNotMatch(whole, /<p>/);

    const two = links.renderFlowWithEmbeds('A [e=1] and [e=2] B', s => s);
    assert.equal((two.match(/class="note-scripture-embed"/g) || []).length, 2);
    assert.match(two, /<p>A<\/p>/);
    assert.match(two, /<p>and<\/p>/);
    assert.match(two, /<p>B<\/p>/);
    assert.doesNotMatch(two, /<p>[^<]*<blockquote/);

    const heading = links.renderHeadingWithEmbeds('Title [e=26137]', s => s, 'h4');
    assert.match(heading, /<h4>Title<\/h4>/);
    assert.match(heading, /note-scripture-embed/);
    assert.doesNotMatch(heading, /<h4>[^<]*<blockquote/);
});

test('list/heading [e=] keeps surrounding structure', () => {
    const item1 = links.renderListItemWithEmbeds('first', s => s);
    const item2 = links.renderListItemWithEmbeds('See [e=26137] today', s => s);
    const item3 = links.renderListItemWithEmbeds('third', s => s);
    const list = '<ul>' + item1 + item2 + item3 + '</ul>';
    assert.match(list, /<ul><li>first<\/li>/);
    assert.match(list, /<li>See today<blockquote[\s\S]*<\/blockquote><\/li>/);
    assert.match(list, /<li>third<\/li><\/ul>/);
    assert.equal((list.match(/<ul>/g) || []).length, 1);
    assert.equal((list.match(/<\/ul>/g) || []).length, 1);
    assert.doesNotMatch(list, /<p>/);
    assert.doesNotMatch(list, /<li>[^<]*<p>/);
    assert.match(list, /data-v="26137"/);
    assert.match(list, /See/);
    assert.match(list, /today/);

    const heading = links.renderHeadingWithEmbeds('Title [e=26137] amen', s => s, 'h4');
    assert.match(heading, /<h4>Title amen<\/h4>/);
    assert.match(heading, /<\/h4><blockquote/);
    assert.match(heading, /note-scripture-embed/);
    assert.doesNotMatch(heading, /<h4>[^<]*<blockquote/);
    assert.doesNotMatch(heading, /<h4>Title<\/h4>[\s\S]*<h4>amen<\/h4>/);
});

test('hydrate does not Passage-title an [e=] cite', () => {
    const embedLabel = links.rangeLinkDisplayLabel({
        embedCite: true,
        passageTitle: 'The New Birth',
        reference: 'John 3:16',
        body: '26137'
    });
    assert.equal(embedLabel, 'John 3:16');
    assert.doesNotMatch(embedLabel, /The New Birth/);

    const vLabel = links.rangeLinkDisplayLabel({
        embedCite: false,
        passageTitle: 'The New Birth',
        reference: 'John 3:16',
        body: '26137'
    });
    assert.equal(vLabel, 'The New Birth');
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
