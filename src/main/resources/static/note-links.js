/**
 * Portable note scripture tokens — [v=…] links and [e=…] quoted embeds.
 *
 * One grammar (mirrors VerseRangeParser). The prefix is a render-mode flag;
 * stored bodies keep verse ids only and never paste KJV text.
 */
(function (global) {
    'use strict';

    const MIN_VERSE_ID = 1;
    const MAX_VERSE_ID = 31102;
    const EMBED_VERSE_CAP = 12;

    function escapeHtml(str) {
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;');
    }

    function escapeAttr(str) {
        return escapeHtml(str).replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    function normalizeRanges(ranges) {
        if (!ranges || !ranges.length) {
            throw new Error('Range list must not be empty');
        }
        const sorted = ranges
            .map(r => {
                const from = Math.min(r.from, r.to);
                const to = Math.max(r.from, r.to);
                if (from < MIN_VERSE_ID || to > MAX_VERSE_ID) {
                    throw new Error('Verse id out of bounds: ' + from + '-' + to);
                }
                return { from, to };
            })
            .sort((a, b) => a.from - b.from || a.to - b.to);
        const merged = [];
        let cur = sorted[0];
        for (let i = 1; i < sorted.length; i++) {
            const next = sorted[i];
            if (next.from <= cur.to + 1) {
                cur = { from: cur.from, to: Math.max(cur.to, next.to) };
            } else {
                merged.push(cur);
                cur = next;
            }
        }
        merged.push(cur);
        return merged;
    }

    function parseVerseId(raw) {
        const s = String(raw == null ? '' : raw).trim();
        if (!/^\d+$/.test(s)) throw new Error('bad id');
        const v = Number(s);
        if (!Number.isSafeInteger(v)) throw new Error('bad id');
        return v;
    }

    function parseRangeBody(body) {
        const s = String(body || '').trim();
        if (!s) throw new Error('Range body must not be blank');
        const ranges = [];
        for (const part of s.split(',')) {
            const p = part.trim();
            if (!p) throw new Error('Empty range segment');
            if (p.includes('-')) {
                const bounds = p.split('-', 2);
                const a = parseVerseId(bounds[0]);
                const b = parseVerseId(bounds[1]);
                ranges.push({ from: Math.min(a, b), to: Math.max(a, b) });
            } else {
                const v = parseVerseId(p);
                ranges.push({ from: v, to: v });
            }
        }
        return normalizeRanges(ranges);
    }

    /**
     * Parses [v=…], [e=…], v=…, e=…, or a bare range body.
     * @returns {{ embed: boolean, ranges: Array<{from:number,to:number}> }}
     */
    function parseToken(raw) {
        let s = String(raw == null ? '' : raw).trim();
        if (!s) throw new Error('token must not be blank');
        let embed = false;
        const m = s.match(/^\[?([ve])=([^\]]+)\]?$/i);
        if (m) {
            embed = m[1].toLowerCase() === 'e';
            s = m[2].trim();
        }
        return { embed, ranges: parseRangeBody(s) };
    }

    function serializeRangeBody(ranges) {
        return normalizeRanges(ranges).map(r =>
            r.from === r.to ? String(r.from) : `${r.from}-${r.to}`
        ).join(',');
    }

    function serializeVToken(ranges) {
        return `[v=${serializeRangeBody(ranges)}]`;
    }

    function serializeEToken(ranges) {
        return `[e=${serializeRangeBody(ranges)}]`;
    }

    function verseCount(ranges) {
        return normalizeRanges(ranges).reduce((n, r) => n + (r.to - r.from + 1), 0);
    }

    function embedCapMessage(count) {
        return `Quoted scripture is limited to ${EMBED_VERSE_CAP} verses (this reference is ${count}).`;
    }

    function isEmbedToken(raw) {
        const s = String(raw == null ? '' : raw).trim();
        if (!s) return false;
        const m = s.match(/^\[?([ve])=/i);
        return !!(m && m[1].toLowerCase() === 'e');
    }

    /** Stored pointer prefixes — leave these alone on save-normalize. */
    function isStoredPointerInner(inner) {
        const s = String(inner || '').trim();
        return /^[ve]=/i.test(s)
            || /^pid=\d+$/i.test(s)
            || /^passage=[0-9a-f-]{36}$/i.test(s);
    }

    /**
     * Insert / save-normalize: write [e=…] or [v=…] from the editor embed flag.
     * Refuses (does not truncate) an embed over EMBED_VERSE_CAP.
     * @returns {{ ok: true, token: string } | { ok: false, error: string }}
     */
    function tokenFromRanges(ranges, embed) {
        const n = verseCount(ranges);
        if (embed) {
            if (n > EMBED_VERSE_CAP) {
                return { ok: false, error: embedCapMessage(n) };
            }
            return { ok: true, token: serializeEToken(ranges) };
        }
        return { ok: true, token: serializeVToken(ranges) };
    }

    function rangesFromNaturalKey(naturalKey) {
        const ranges = [];
        for (const part of String(naturalKey).split(',')) {
            const p = part.trim();
            if (!p) continue;
            if (p.includes(':')) {
                const [a, b] = p.split(':', 2).map(x => parseInt(x.trim(), 10));
                if (!Number.isFinite(a) || !Number.isFinite(b)) throw new Error('bad natural key');
                ranges.push({ from: Math.min(a, b), to: Math.max(a, b) });
            } else {
                const v = parseInt(p, 10);
                if (!Number.isFinite(v)) throw new Error('bad natural key');
                ranges.push({ from: v, to: v });
            }
        }
        return normalizeRanges(ranges);
    }

    function tokenFromNaturalKey(naturalKey, embed) {
        return tokenFromRanges(rangesFromNaturalKey(naturalKey), embed);
    }

    /**
     * Place a token into a textarea body. [e=…] sits alone on its line with no
     * surrounding spaces so markdown-lite treats it as a whole-line blockquote.
     * [v=…] keeps the existing inline space-padding.
     * @returns {{ next: string, caret: number }}
     */
    function applyTokenInsert(text, start, end, token) {
        const before = String(text).slice(0, start);
        const after = String(text).slice(end);
        if (isEmbedToken(token)) {
            let left = before.replace(/[ \t]+$/, '');
            let right = after.replace(/^[ \t]+/, '');
            if (left.length && !left.endsWith('\n')) left += '\n';
            if (right.length && !right.startsWith('\n')) right = '\n' + right;
            const next = left + token + right;
            return { next, caret: left.length + token.length };
        }
        const needsSpaceBefore = before.length > 0 && !/\s$/.test(before);
        const needsSpaceAfter = after.length > 0 && !/^\s/.test(after);
        const insert = (needsSpaceBefore ? ' ' : '') + token + (needsSpaceAfter ? ' ' : '');
        return { next: before + insert + after, caret: before.length + insert.length };
    }

    /**
     * Write-side recap for already-stored / pasted [e=…] tokens.
     * Refuses (does not truncate) any embed over EMBED_VERSE_CAP.
     */
    function refuseOversizedEmbeds(text) {
        if (!text) return { ok: true };
        const re = /\[e=([^\]]+)\]/gi;
        let m;
        while ((m = re.exec(text)) !== null) {
            try {
                const n = verseCount(parseToken(m[0]).ranges);
                if (n > EMBED_VERSE_CAP) {
                    return { ok: false, error: embedCapMessage(n) };
                }
            } catch (_) { /* leave malformed tokens for other handling */ }
        }
        return { ok: true };
    }

    /**
     * Cite label for a .note-range-link. Embed cites stay a verse reference —
     * never a Passage title.
     */
    function rangeLinkDisplayLabel({ embedCite, passageTitle, reference, body }) {
        if (embedCite) return reference || body || '';
        return passageTitle || reference || body || '';
    }

    /**
     * Quoted-block markup. Verse text is NOT included — hydrate from /api/ranges.
     * The cite is a .note-range-link so the same click opens /read/range?v=….
     */
    function renderEmbedHtml(body, label) {
        const parsed = parseToken(body.includes('=') ? body : `e=${body}`);
        const canon = serializeRangeBody(parsed.ranges);
        const safeBody = escapeAttr(canon);
        const citeLabel = escapeHtml(label || canon);
        return `<blockquote class="note-scripture-embed" data-v="${safeBody}">`
            + `<div class="note-scripture-embed-text"></div>`
            + `<cite><a class="note-range-link" data-v="${safeBody}" href="#">${citeLabel}</a></cite>`
            + `</blockquote>`;
    }

    /**
     * Split a line on [e=…] tokens so a mid-line paste can be promoted to its
     * own quoted block. Does not drop the embed.
     */
    function splitLineByEmbeds(line) {
        const re = /\[e=[^\]]+\]/gi;
        const parts = [];
        let last = 0;
        let m;
        const s = String(line == null ? '' : line);
        while ((m = re.exec(s)) !== null) {
            if (m.index > last) {
                parts.push({ type: 'text', value: s.slice(last, m.index) });
            }
            parts.push({ type: 'embed', value: m[0] });
            last = m.index + m[0].length;
        }
        if (last < s.length || parts.length === 0) {
            parts.push({ type: 'text', value: s.slice(last) });
        }
        return parts;
    }

    /**
     * Render a line that may contain mid-line [e=…] tokens. Surrounding text
     * stays; each embed becomes a sibling blockquote — never nested in <p>.
     */
    function renderFlowWithEmbeds(line, renderInlineText) {
        const renderText = typeof renderInlineText === 'function' ? renderInlineText : escapeHtml;
        const parts = splitLineByEmbeds(line);
        let html = '';
        for (const part of parts) {
            if (part.type === 'embed') {
                try {
                    html += renderEmbedHtml(part.value);
                } catch (_) {
                    html += '<p>' + renderText(part.value) + '</p>';
                }
            } else if (part.value.trim()) {
                html += '<p>' + renderText(part.value.trim()) + '</p>';
            }
        }
        return html;
    }

    function collectEmbedParts(line, renderInlineText) {
        const renderText = typeof renderInlineText === 'function' ? renderInlineText : escapeHtml;
        const texts = [];
        const embeds = [];
        for (const part of splitLineByEmbeds(line)) {
            if (part.type === 'embed') {
                try {
                    embeds.push(renderEmbedHtml(part.value));
                } catch (_) {
                    texts.push(renderText(part.value));
                }
            } else if (part.value.trim()) {
                texts.push(renderText(part.value.trim()));
            }
        }
        return { textHtml: texts.join(' '), embedHtml: embeds.join('') };
    }

    /**
     * Heading: one heading element keeps all the text; quotes are siblings
     * after it. Never nest a blockquote in the heading.
     */
    function renderHeadingWithEmbeds(rest, renderInlineText, tag) {
        const wrap = tag || 'h4';
        const { textHtml, embedHtml } = collectEmbedParts(rest, renderInlineText);
        const head = textHtml ? '<' + wrap + '>' + textHtml + '</' + wrap + '>' : '';
        return head + embedHtml;
    }

    /**
     * List item: text stays in <li>; quotes sit after the text inside the
     * same item so the surrounding <ul>/<ol> is not closed.
     */
    function renderListItemWithEmbeds(item, renderInlineText) {
        const { textHtml, embedHtml } = collectEmbedParts(item, renderInlineText);
        return '<li>' + textHtml + embedHtml + '</li>';
    }

    function verseTextsHtml(verses) {
        if (!verses || !verses.length) return '';
        return verses.map(v => {
            const num = escapeHtml(String(v.verse ?? ''));
            const text = escapeHtml(v.text || '');
            return `<span class="note-scripture-embed-verse"><sup>${num}</sup>${text}</span>`;
        }).join('');
    }

    /** Fill a rendered embed from GET /api/ranges. Does not write text into the note body. */
    function applyEmbedHydration(el, data) {
        if (!el || !data) return;
        const textEl = el.querySelector('.note-scripture-embed-text');
        if (textEl) textEl.innerHTML = verseTextsHtml(data.verses);
        const cite = el.querySelector('a.note-range-link');
        if (cite && data.reference) {
            cite.textContent = data.reference;
            cite.dataset.labelReady = '1';
        }
    }

    const api = {
        MIN_VERSE_ID,
        MAX_VERSE_ID,
        EMBED_VERSE_CAP,
        escapeHtml,
        escapeAttr,
        parseToken,
        parseRangeBody,
        normalizeRanges,
        serializeRangeBody,
        serializeVToken,
        serializeEToken,
        verseCount,
        embedCapMessage,
        isEmbedToken,
        isStoredPointerInner,
        tokenFromRanges,
        rangesFromNaturalKey,
        tokenFromNaturalKey,
        applyTokenInsert,
        refuseOversizedEmbeds,
        rangeLinkDisplayLabel,
        renderEmbedHtml,
        splitLineByEmbeds,
        renderFlowWithEmbeds,
        renderHeadingWithEmbeds,
        renderListItemWithEmbeds,
        verseTextsHtml,
        applyEmbedHydration
    };

    if (typeof module !== 'undefined' && module.exports) {
        module.exports = api;
    }
    global.KjvNoteLinks = api;
})(typeof window !== 'undefined' ? window : globalThis);
