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

    function parseRangeBody(body) {
        const s = String(body || '').trim();
        if (!s) throw new Error('Range body must not be blank');
        const ranges = [];
        for (const part of s.split(',')) {
            const p = part.trim();
            if (!p) throw new Error('Empty range segment');
            if (p.includes('-')) {
                const bounds = p.split('-', 2);
                const a = parseInt(bounds[0].trim(), 10);
                const b = parseInt(bounds[1].trim(), 10);
                if (!Number.isFinite(a) || !Number.isFinite(b)) throw new Error('bad range');
                ranges.push({ from: Math.min(a, b), to: Math.max(a, b) });
            } else {
                const v = parseInt(p, 10);
                if (!Number.isFinite(v)) throw new Error('bad id');
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
        renderEmbedHtml,
        verseTextsHtml,
        applyEmbedHydration
    };

    if (typeof module !== 'undefined' && module.exports) {
        module.exports = api;
    }
    global.KjvNoteLinks = api;
})(typeof window !== 'undefined' ? window : globalThis);
