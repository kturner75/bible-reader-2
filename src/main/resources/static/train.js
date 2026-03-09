(function () {
    'use strict';

    // --- Utilities ---
    function escapeHtml(text) {
        return text.replace(/&/g, '&amp;').replace(/</g, '&lt;')
                   .replace(/>/g, '&gt;').replace(/"/g, '&quot;')
                   .replace(/'/g, '&#39;');
    }

    function computeBlankedSegments(text, masteryLevel) {
        const tokens = text.split(/(\s+)/);
        const shouldBlank = (i) => {
            switch (masteryLevel) {
                case 0: return i % 7 === 0;
                case 1: return i % 4 === 0;
                case 2: return i % 2 === 0;
                case 3: return i % 3 !== 2;
                case 4: return i % 7 !== 6;
                default: return true; // 5 = all blanked
            }
        };
        let wordIdx = 0;
        const segments = [];
        for (const token of tokens) {
            if (token.trim().length === 0) {
                if (segments.length > 0 && !segments[segments.length - 1].isBlank) {
                    segments[segments.length - 1].text += token;
                } else {
                    segments.push({ text: token, isBlank: false });
                }
            } else {
                const blank = shouldBlank(wordIdx);
                const prefix = token.match(/^[^a-zA-Z0-9]*/)[0];
                const suffix = token.match(/[^a-zA-Z0-9]*$/)[0];
                const expected = token.slice(prefix.length, token.length - suffix.length || undefined);
                segments.push({ text: token, isBlank: blank, expected: blank ? expected : null, prefix, suffix });
                wordIdx++;
            }
        }
        return segments;
    }

    function renderVerseSegments(segments, useFirstLetter) {
        return segments.map(seg => {
            if (seg.isBlank) {
                const sz   = Math.max(3, seg.expected.length + 1);
                const hint = (useFirstLetter && seg.expected.length > 0)
                    ? ` placeholder="${escapeHtml(seg.expected[0])}"` : '';
                return escapeHtml(seg.prefix) +
                       `<input class="blank-input" size="${sz}"${hint}` +
                       ` data-expected="${escapeHtml(seg.expected)}"` +
                       ' autocomplete="off" spellcheck="false">' +
                       escapeHtml(seg.suffix);
            }
            return '<span>' + escapeHtml(seg.text) + '</span>';
        }).join('');
    }

    // --- Session resolution ---
    let session = null;
    let entry = null;

    const rawSession = sessionStorage.getItem('kjv_training_session');
    if (rawSession) {
        try { session = JSON.parse(rawSession); } catch (e) { /* fall through */ }
    }

    if (session) {
        if (session.index >= session.entries.length) {
            sessionStorage.removeItem('kjv_training_session');
            showCompletion();
        } else {
            entry = session.entries[session.index];
            init();
        }
    } else {
        const rawEntry = sessionStorage.getItem('kjv_training_entry');
        sessionStorage.removeItem('kjv_training_entry');
        if (!rawEntry) { window.location.href = '/read'; return; }
        try { entry = JSON.parse(rawEntry); } catch (e) { window.location.href = '/read'; return; }
        init();
    }

    // --- Completion screen ---
    function showCompletion() {
        const card     = document.getElementById('train-card');
        const progress = document.getElementById('train-progress');
        const done     = document.getElementById('train-done');
        if (card)     card.hidden     = true;
        if (progress) progress.hidden = true;
        if (done)     done.hidden     = false;
        document.title = 'All done — KJV Bible Reader';
    }

    // --- Main render ---
    function init() {
        // Progress indicator (only for multi-entry sessions)
        if (session && session.entries.length > 1) {
            const progressEl = document.getElementById('train-progress');
            progressEl.hidden = false;
            document.getElementById('train-progress-current').textContent = session.index + 1;
            document.getElementById('train-progress-total').textContent   = session.entries.length;
        }

        const refEl     = document.getElementById('train-ref');
        const verseEl   = document.getElementById('train-verse');
        const checkBtn  = document.getElementById('train-check-btn');
        const ratingsEl = document.getElementById('train-ratings');
        const errorEl   = document.getElementById('train-error');

        // Normalise: support both old {fromVerseText} and new {verses:[]}
        const verses = entry.verses && entry.verses.length
            ? entry.verses
            : [{ verseNum: 1, reference: entry.fromVerseRef, text: entry.fromVerseText || '' }];

        const isSingle = verses.length === 1;
        const useFirstLetter = entry.masteryLevel >= 4;

        document.title = (isSingle ? verses[0].reference : `${entry.fromVerseRef} – ${entry.toVerseRef}`)
                         + ' — Memory Training';
        refEl.textContent = isSingle
            ? verses[0].reference
            : `${entry.fromVerseRef} – ${entry.toVerseRef}`;

        // Render verses — single verse inline, multi-verse as paragraphs with sup numbers
        if (isSingle) {
            const segs = computeBlankedSegments(verses[0].text, entry.masteryLevel);
            verseEl.innerHTML = renderVerseSegments(segs, useFirstLetter);
        } else {
            verseEl.innerHTML = verses.map(v => {
                const segs = computeBlankedSegments(v.text, entry.masteryLevel);
                return `<p class="train-verse-line"><sup class="train-verse-num">${v.verseNum}</sup>${renderVerseSegments(segs, useFirstLetter)}</p>`;
            }).join('');
        }

        const first = verseEl.querySelector('.blank-input');
        if (first) first.focus();

        // Normalize for comparison: collapse smart apostrophes/quotes, strip
        // leading/trailing punctuation, lowercase.  Applied to both sides so
        // curly ' (U+2019) in the KJV text matches a straight ' typed by the user.
        function normalizeAnswer(s) {
            return s
                .replace(/[\u2018\u2019\u02BC]/g, "'")   // curly/modifier apostrophes → '
                .replace(/[\u201C\u201D]/g, '"')           // curly double-quotes → "
                .trim()
                .replace(/^[^a-zA-Z0-9]+|[^a-zA-Z0-9]+$/g, '')
                .toLowerCase();
        }

        // --- Check ---
        function checkAnswers() {
            checkBtn.disabled = true;
            verseEl.querySelectorAll('.blank-input').forEach(input => {
                input.disabled = true;
                const answer   = normalizeAnswer(input.value);
                const expected = normalizeAnswer(input.dataset.expected);
                if (answer === expected) {
                    input.classList.add('blank-correct');
                } else {
                    input.classList.add('blank-wrong');
                    input.value = input.dataset.expected;
                }
            });
            checkBtn.hidden  = true;
            ratingsEl.hidden = false;
        }

        // --- Rate & advance ---
        async function submitRating(quality) {
            ratingsEl.querySelectorAll('.rating-btn').forEach(b => b.disabled = true);
            try {
                const res = await fetch('/api/memorization/queue/' + entry.id + '/review', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    credentials: 'include',
                    body: JSON.stringify({ quality })
                });
                if (res.status === 401) { window.location.href = '/login.html'; return; }
            } catch (e) {
                errorEl.textContent = 'Could not save rating. Please try again.';
                errorEl.hidden = false;
                ratingsEl.querySelectorAll('.rating-btn').forEach(b => b.disabled = false);
                return;
            }

            if (session) {
                session.index++;
                if (session.index >= session.entries.length) {
                    sessionStorage.removeItem('kjv_training_session');
                    showCompletion();
                } else {
                    sessionStorage.setItem('kjv_training_session', JSON.stringify(session));
                    window.location.reload();
                }
            } else {
                window.location.href = '/read';
            }
        }

        // --- Event listeners ---
        checkBtn.addEventListener('click', checkAnswers);
        ratingsEl.addEventListener('click', (e) => {
            const btn = e.target.closest('.rating-btn');
            if (btn && !btn.disabled) submitRating(parseInt(btn.dataset.quality));
        });
        verseEl.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && !checkBtn.disabled && !checkBtn.hidden) checkAnswers();
        });
    }
})();
