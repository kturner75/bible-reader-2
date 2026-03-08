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
                default: return true; // 5 = all
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

    // --- Load entry from sessionStorage ---
    const entry = JSON.parse(sessionStorage.getItem('kjv_training_entry') || 'null');
    sessionStorage.removeItem('kjv_training_entry');
    if (!entry) {
        window.location.href = '/read';
        return;
    }

    // --- DOM refs ---
    const refEl    = document.getElementById('train-ref');
    const verseEl  = document.getElementById('train-verse');
    const checkBtn = document.getElementById('train-check-btn');
    const ratingsEl = document.getElementById('train-ratings');
    const errorEl  = document.getElementById('train-error');

    // --- Render ---
    document.title = entry.fromVerseRef + ' — Memory Training';
    refEl.textContent = entry.fromVerseRef;

    const segments = computeBlankedSegments(entry.fromVerseText, entry.masteryLevel);
    verseEl.innerHTML = segments.map(seg => {
        if (seg.isBlank) {
            const sz = Math.max(3, seg.expected.length + 1);
            return escapeHtml(seg.prefix) +
                   '<input class="blank-input" size="' + sz + '"' +
                   ' data-expected="' + escapeHtml(seg.expected) + '"' +
                   ' autocomplete="off" spellcheck="false">' +
                   escapeHtml(seg.suffix);
        }
        return '<span>' + escapeHtml(seg.text) + '</span>';
    }).join('');

    const first = verseEl.querySelector('.blank-input');
    if (first) first.focus();

    // --- Check ---
    function checkAnswers() {
        checkBtn.disabled = true;
        verseEl.querySelectorAll('.blank-input').forEach(input => {
            input.disabled = true;
            const answer = input.value.trim()
                .replace(/^[^a-zA-Z0-9]+|[^a-zA-Z0-9]+$/g, '').toLowerCase();
            const expected = input.dataset.expected.toLowerCase();
            if (answer === expected) {
                input.classList.add('blank-correct');
            } else {
                input.classList.add('blank-wrong');
                input.value = input.dataset.expected;
            }
        });
        checkBtn.hidden = true;
        ratingsEl.hidden = false;
    }

    // --- Rate ---
    async function submitRating(quality) {
        ratingsEl.querySelectorAll('.rating-btn').forEach(b => b.disabled = true);
        try {
            const res = await fetch('/api/memorization/queue/' + entry.id + '/review', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                credentials: 'include',
                body: JSON.stringify({ quality })
            });
            if (res.status === 401) {
                window.location.href = '/login.html';
                return;
            }
        } catch (e) {
            errorEl.textContent = 'Could not save rating. Please try again.';
            errorEl.hidden = false;
            ratingsEl.querySelectorAll('.rating-btn').forEach(b => b.disabled = false);
            return;
        }
        window.location.href = '/read';
    }

    // --- Event listeners ---
    checkBtn.addEventListener('click', checkAnswers);

    ratingsEl.addEventListener('click', (e) => {
        const btn = e.target.closest('.rating-btn');
        if (btn && !btn.disabled) submitRating(parseInt(btn.dataset.quality));
    });

    // Enter on any blank input triggers Check
    verseEl.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !checkBtn.disabled && !checkBtn.hidden) checkAnswers();
    });
})();
