(function () {
    'use strict';

    // Return to the page that explicitly launched this training session.
    // Reading remains the safe fallback for direct or malformed URLs.
    const trainingOrigin = new URLSearchParams(window.location.search).get('from');
    const backLink = document.getElementById('train-back');
    if (trainingOrigin === 'dashboard') {
        backLink.href = '/dashboard';
        backLink.textContent = '\u2190 Back to dashboard';
    }

    // --- Utilities ---
    function escapeHtml(text) {
        return text.replace(/&/g, '&amp;').replace(/</g, '&lt;')
                   .replace(/>/g, '&gt;').replace(/"/g, '&quot;')
                   .replace(/'/g, '&#39;');
    }

    // Common abbreviations so "Jn 3:16" matches "John 3:16" without a round-trip.
    // /api/reference covers the full alias list when the server is available.
    const BOOK_ALIAS = {
        ge: 'genesis', gen: 'genesis',
        ex: 'exodus', exo: 'exodus', exod: 'exodus',
        lev: 'leviticus', le: 'leviticus',
        nu: 'numbers', num: 'numbers',
        de: 'deuteronomy', deu: 'deuteronomy', deut: 'deuteronomy', dt: 'deuteronomy',
        jos: 'joshua', josh: 'joshua',
        jdg: 'judges', judg: 'judges',
        ru: 'ruth',
        ps: 'psalm', psa: 'psalm', pss: 'psalm', psalms: 'psalm',
        pr: 'proverbs', pro: 'proverbs', prov: 'proverbs',
        ec: 'ecclesiastes', ecc: 'ecclesiastes',
        sos: 'songofsolomon', song: 'songofsolomon', songofsongs: 'songofsolomon',
        isa: 'isaiah', is: 'isaiah',
        jer: 'jeremiah', je: 'jeremiah',
        la: 'lamentations', lam: 'lamentations',
        eze: 'ezekiel', ezk: 'ezekiel',
        da: 'daniel', dan: 'daniel',
        ho: 'hosea', hos: 'hosea',
        joe: 'joel',
        am: 'amos',
        ob: 'obadiah', oba: 'obadiah',
        jon: 'jonah',
        mi: 'micah', mic: 'micah',
        na: 'nahum', nah: 'nahum',
        hab: 'habakkuk',
        zep: 'zephaniah',
        hag: 'haggai',
        zec: 'zechariah',
        mal: 'malachi',
        mt: 'matthew', matt: 'matthew', mat: 'matthew',
        mk: 'mark', mr: 'mark',
        lk: 'luke', lu: 'luke',
        jn: 'john', joh: 'john',
        ac: 'acts', act: 'acts',
        ro: 'romans', rom: 'romans',
        cor: 'corinthians', co: 'corinthians',
        ga: 'galatians', gal: 'galatians',
        eph: 'ephesians',
        php: 'philippians', phil: 'philippians',
        col: 'colossians',
        thess: 'thessalonians', th: 'thessalonians',
        tim: 'timothy', ti: 'timothy',
        tit: 'titus',
        phm: 'philemon',
        heb: 'hebrews',
        jas: 'james', jam: 'james',
        pet: 'peter', pe: 'peter',
        jude: 'jude',
        rev: 'revelation', re: 'revelation'
    };

    function canonBook(norm) {
        if (!norm) return '';
        if (norm === 'psalms') return 'psalm';
        if (BOOK_ALIAS[norm]) return BOOK_ALIAS[norm];
        const numbered = norm.match(/^(\d)([a-z]+)$/);
        if (numbered) {
            const mapped = BOOK_ALIAS[numbered[2]] || numbered[2];
            return numbered[1] + mapped;
        }
        return norm;
    }

    function parseOneRef(s) {
        const m = String(s || '').trim().match(
            /^(\d+\s*)?([A-Za-z][A-Za-z']*(?:\s+[A-Za-z][A-Za-z']*)*)\s+(\d+)\s*[:.v]\s*(\d+)(?:\s*-\s*(?:(\d+)\s*[:.v]\s*)?(\d+))?$/i
        );
        if (!m) return null;
        const book = canonBook(((m[1] || '') + m[2]).toLowerCase().replace(/[^a-z0-9]+/g, ''));
        const chapter   = parseInt(m[3], 10);
        const fromVerse = parseInt(m[4], 10);
        const toChapter = m[5] ? parseInt(m[5], 10) : chapter;
        const toVerse   = m[6] ? parseInt(m[6], 10) : fromVerse;
        if (!book || chapter < 1 || fromVerse < 1 || toChapter < 1 || toVerse < 1) return null;
        return { book, chapter, fromVerse, toChapter, toVerse };
    }

    /** Accept John 3:16, John 3:16-17, John 3:16 – John 3:17, extra spaces, en-dashes. */
    function parseTrainingRef(raw) {
        const s = String(raw || '')
            .replace(/[\u2013\u2014\u2212]/g, '-')
            .replace(/\s+/g, ' ')
            .trim()
            .replace(/^["'`]+|["'`.,;]+$/g, '');
        if (!s) return null;
        const halves = s.split(/\s+-\s+/);
        if (halves.length === 2) {
            const a = parseOneRef(halves[0]);
            const b = parseOneRef(halves[1]);
            if (a && b) {
                return {
                    book: a.book,
                    chapter: a.chapter,
                    fromVerse: a.fromVerse,
                    toChapter: b.chapter,
                    toVerse: b.fromVerse
                };
            }
        }
        return parseOneRef(s);
    }

    function samePassage(got, expected) {
        return !!got && !!expected
            && got.book === expected.book
            && got.chapter === expected.chapter
            && got.fromVerse === expected.fromVerse
            && got.toChapter === expected.toChapter
            && got.toVerse === expected.toVerse;
    }

    function formatExpectedDisplay(firstRef, lastRef, first, last) {
        if (!firstRef) return lastRef || '';
        if (!lastRef || firstRef === lastRef) return firstRef;
        if (first && last && first.book === last.book && first.chapter === last.chapter) {
            const bookLabel = firstRef.replace(/\s+\d+:\d+\s*$/, '');
            return bookLabel + ' ' + first.chapter + ':' + first.fromVerse + '\u2013' + last.fromVerse;
        }
        return firstRef + ' \u2013 ' + lastRef;
    }

    function expectedFromEntry(entry, verses) {
        const firstRef = (verses[0] && verses[0].reference) || entry.fromVerseRef || '';
        const lastRef  = (verses[verses.length - 1] && verses[verses.length - 1].reference)
            || entry.toVerseRef || firstRef;
        const first = parseTrainingRef(firstRef);
        const last  = parseTrainingRef(lastRef);
        const passage = (first && last) ? {
            book: first.book,
            chapter: first.chapter,
            fromVerse: first.fromVerse,
            toChapter: last.chapter,
            toVerse: last.fromVerse
        } : null;
        return {
            passage,
            display: formatExpectedDisplay(firstRef, lastRef, first, last),
            ids: verses.map(v => v.id).filter(id => typeof id === 'number')
        };
    }

    async function matchRefViaApi(typed, expected) {
        const q = String(typed || '').trim().replace(/[\u2013\u2014\u2212]/g, '-');
        if (!q || !expected) return null;
        const res = await fetch('/api/reference?ref=' + encodeURIComponent(q));
        if (!res.ok) return null;
        const data = await res.json();
        if (!data || !data.valid) return { ok: false };
        if (!data.verseSpecified) return { ok: false, reason: 'verse' };
        if (!expected.ids.length) return null;
        if (expected.ids.length === 1) {
            const ranged = Array.isArray(data.ranges) && data.ranges.length > 0
                && data.ranges[0].from !== data.ranges[0].to;
            return { ok: data.verseId === expected.ids[0] && !ranged };
        }
        if (Array.isArray(data.ranges) && data.ranges.length) {
            const from = data.ranges[0].from;
            const to   = data.ranges[data.ranges.length - 1].to;
            return { ok: from === expected.ids[0] && to === expected.ids[expected.ids.length - 1] };
        }
        return { ok: false };
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
    function setDoneCopy(heading, subtitle, pageTitle) {
        const title = document.getElementById('train-done-title');
        const sub   = document.getElementById('train-done-sub');
        if (title) title.textContent = heading;
        if (sub)   sub.textContent   = subtitle;
        document.title = pageTitle;
    }

    function showCompletion() {
        const card     = document.getElementById('train-card');
        const progress = document.getElementById('train-progress');
        const done     = document.getElementById('train-done');
        if (card)     card.hidden     = true;
        if (progress) progress.hidden = true;
        if (done)     done.hidden     = false;
        // Don't paint "All done for today" until the due queue is known —
        // a dozen remaining verses would make that heading a lie.
        setDoneCopy('', '', 'Memory Training — KJV Bible Reader');
        offerNextUp();
    }

    /**
     * Remaining due verses use the same queue + calendar-day rule as the
     * dashboard and reader (GET /api/memorization/queue, KjvDate.isEntryDue).
     * A just-reviewed passage is scheduled for tomorrow, so it will not
     * reappear here; Next Up is only for verses this session did not cover.
     */
    async function offerNextUp() {
        const nextUp = document.getElementById('train-next-up');
        const dash   = document.getElementById('train-done-dashboard');

        let due = [];
        let queueKnown = false;
        if (window.KjvDate) {
            try {
                const res = await fetch('/api/memorization/queue', { credentials: 'include' });
                if (res.ok) {
                    const entries = await res.json();
                    const today = window.KjvDate.todayIso();
                    due = (Array.isArray(entries) ? entries : [])
                        .filter(e => window.KjvDate.isEntryDue(e, today));
                    queueKnown = true;
                }
            } catch (e) { /* fall through — don't claim the day is finished */ }
        }

        if (queueKnown && due.length === 0) {
            setDoneCopy(
                'All done for today!',
                'Your reviews are saved. Come back tomorrow for the next session.',
                'All done — KJV Bible Reader'
            );
            return;
        }

        // Session is over; the day is not, or we couldn't prove that it is.
        const remaining = queueKnown
            ? (due.length === 1 ? '1 still due today.' : due.length + ' still due today.')
            : 'Your reviews are saved.';
        setDoneCopy('Session complete', remaining, 'Next up — KJV Bible Reader');

        if (!queueKnown || due.length === 0 || !nextUp) return;

        nextUp.hidden = false;
        if (dash) dash.classList.add('train-done-link-secondary');
        nextUp.addEventListener('click', () => {
            // Same session shape as dashboard Train Now / reader Train — the
            // first remaining due verse is next; the rest follow in queue order.
            sessionStorage.setItem('kjv_training_session', JSON.stringify({
                entries: due,
                index: 0
            }));
            const from = new URLSearchParams(window.location.search).get('from');
            window.location.href = from
                ? '/train?from=' + encodeURIComponent(from)
                : '/train';
        });
        nextUp.focus();
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

        const refEl           = document.getElementById('train-ref');
        const verseEl         = document.getElementById('train-verse');
        const checkBtn        = document.getElementById('train-check-btn');
        const ratingsEl       = document.getElementById('train-ratings');
        const errorEl         = document.getElementById('train-error');
        const testToggle      = document.getElementById('train-test-toggle');
        const card            = document.getElementById('train-card');
        const reciteToggle    = document.getElementById('train-recite-toggle');
        const recitePanel     = document.getElementById('recite-panel');
        const recordBtn       = document.getElementById('record-btn');
        const recordingStatus = document.getElementById('recording-status');
        const transcriptPanel = document.getElementById('transcript-panel');
        const transcriptHeard = document.getElementById('transcript-heard');
        const transcriptDiff  = document.getElementById('transcript-diff');
        const accuracyDisplay = document.getElementById('accuracy-display');
        const previewText     = document.getElementById('passage-preview-text');
        const beginBtn        = document.getElementById('train-begin-btn');
        const peekToggle      = document.getElementById('train-peek-toggle');
        const refRecall       = document.getElementById('train-ref-recall');
        const refInput        = document.getElementById('train-ref-input');
        const refFeedback     = document.getElementById('train-ref-feedback');

        // --- Test mode ---
        const TEST_MODE_KEY = 'kjv_test_mode';
        let testMode = localStorage.getItem(TEST_MODE_KEY) === 'true';

        function applyTestMode() {
            card.classList.toggle('test-mode', testMode);
            testToggle.classList.toggle('active', testMode);
            testToggle.textContent = testMode ? 'Test mode: on' : 'Test mode';
        }

        testToggle.addEventListener('click', () => {
            testMode = !testMode;
            localStorage.setItem(TEST_MODE_KEY, String(testMode));
            applyTestMode();
        });

        applyTestMode();

        // --- Recite mode ---
        const RECITE_MODE_KEY = 'kjv_recite_mode';
        let reciteMode = localStorage.getItem(RECITE_MODE_KEY) === 'true';

        // Recite mode state
        let mediaRecorder = null;
        let audioChunks   = [];
        let isRecording   = false;

        function resetTranscriptPanel() {
            transcriptPanel.hidden = true;
            transcriptHeard.textContent = '';
            transcriptDiff.innerHTML = '';
            accuracyDisplay.textContent = '';
            ratingsEl.querySelectorAll('.rating-btn').forEach(b => b.classList.remove('suggested'));
        }

        function applyReciteMode() {
            card.classList.toggle('recite-mode', reciteMode);
            reciteToggle.classList.toggle('active', reciteMode);
            reciteToggle.textContent = reciteMode ? 'Recite: on' : 'Recite';
            recitePanel.hidden = !reciteMode;
            // Reset panels on mode toggle
            resetTranscriptPanel();
            ratingsEl.hidden = true;
            checkBtn.hidden  = false;
        }

        reciteToggle.addEventListener('click', () => {
            reciteMode = !reciteMode;
            localStorage.setItem(RECITE_MODE_KEY, String(reciteMode));
            applyReciteMode();
        });

        // Browser compatibility guard
        if (reciteMode && typeof MediaRecorder === 'undefined') {
            recitePanel.innerHTML = '<p class="train-error" style="display:block">Voice recitation is not supported in this browser. Please use Chrome, Firefox, or Edge.</p>';
        }

        applyReciteMode();

        // --- Normalise: support both old {fromVerseText} and new {verses:[]} ---
        const verses = entry.verses && entry.verses.length
            ? entry.verses
            : [{ verseNum: 1, reference: entry.fromVerseRef, text: entry.fromVerseText || '' }];

        const isSingle = verses.length === 1;
        const useFirstLetter = entry.masteryLevel >= 4;

        const expectedRef = expectedFromEntry(entry, verses);
        const passageTitle = expectedRef.display
            || (isSingle ? verses[0].reference : `${entry.fromVerseRef} – ${entry.toVerseRef}`);
        const trainingTitle = passageTitle + ' — Memory Training';
        document.title = trainingTitle;
        refEl.textContent = passageTitle;

        // Always begin with an unmasked read-through of the complete passage.
        // This is a separate element so peeking later does not discard answers.
        if (isSingle) {
            previewText.textContent = verses[0].text;
        } else {
            previewText.innerHTML = verses.map(v =>
                `<p class="train-verse-line"><sup class="train-verse-num">${v.verseNum}</sup>${escapeHtml(v.text)}</p>`
            ).join('');
        }

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

        function focusFirstBlank() {
            if (reciteMode) return;
            const first = verseEl.querySelector('.blank-input:not(:disabled)');
            if (first) first.focus();
        }

        function focusRecall() {
            if (refInput && !refInput.disabled) {
                refInput.focus();
                return;
            }
            focusFirstBlank();
        }

        function setPeeking(peeking) {
            card.classList.toggle('peeking', peeking);
            peekToggle.classList.toggle('active', peeking);
            peekToggle.textContent = peeking ? 'Hide passage' : 'Peek';
            peekToggle.setAttribute('aria-expanded', String(peeking));
            if (!peeking) focusRecall();
        }

        beginBtn.addEventListener('click', () => {
            card.classList.remove('pretraining');
            peekToggle.hidden = false;
            if (refRecall) refRecall.hidden = false;
            document.title = 'Memory Training — KJV Bible Reader';
            focusRecall();
        });

        peekToggle.addEventListener('click', () => {
            setPeeking(!card.classList.contains('peeking'));
        });

        // Normalize for comparison: collapse smart apostrophes/quotes, strip
        // leading/trailing punctuation, lowercase.
        function normalizeAnswer(s) {
            return s
                .replace(/[\u2018\u2019\u02BC]/g, "'")
                .replace(/[\u201C\u201D]/g, '"')
                .trim()
                .replace(/^[^a-zA-Z0-9]+|[^a-zA-Z0-9]+$/g, '')
                .toLowerCase();
        }

        function showRefFeedback(message, ok) {
            if (!refFeedback) return;
            refFeedback.hidden = !message;
            refFeedback.textContent = message || '';
            refFeedback.classList.toggle('is-ok', !!ok);
        }

        function markRefInput(ok) {
            if (!refInput) return;
            refInput.classList.toggle('ref-correct', ok);
            refInput.classList.toggle('ref-wrong', !ok);
            refInput.disabled = true;
        }

        let refEvaluated = false;

        async function evaluateReference() {
            if (refEvaluated) return { blocked: false };
            const typed = refInput ? refInput.value : '';
            if (!String(typed).trim()) {
                showRefFeedback('Enter the book, chapter, and verse.', false);
                if (refInput) refInput.focus();
                return { blocked: true };
            }

            const local = parseTrainingRef(typed);
            let matched = samePassage(local, expectedRef.passage);
            let missingVerse = false;

            if (!matched) {
                try {
                    const api = await matchRefViaApi(typed, expectedRef);
                    if (api && api.ok) matched = true;
                    else if (api && api.reason === 'verse') missingVerse = true;
                } catch (e) { /* local result stands */ }
            }

            refEvaluated = true;
            const display = expectedRef.display || 'the printed reference';
            if (matched) {
                markRefInput(true);
                showRefFeedback('', true);
            } else if (missingVerse) {
                markRefInput(false);
                showRefFeedback('Include the verse number — this passage is ' + display + '.', false);
            } else if (!local) {
                markRefInput(false);
                showRefFeedback('That doesn\u2019t look like a reference. This passage is ' + display + '.', false);
            } else {
                markRefInput(false);
                showRefFeedback('That\u2019s not it \u2014 this passage is ' + display + '.', false);
            }
            document.title = trainingTitle;
            return { blocked: false };
        }

        // --- Check (fill-in-blank mode) ---
        async function checkAnswers() {
            const refResult = await evaluateReference();
            if (refResult.blocked) {
                card.classList.add('needs-ref-check');
                checkBtn.disabled = false;
                checkBtn.hidden = false;
                return;
            }

            if (!reciteMode) {
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
            }

            card.classList.add('ref-checked');
            card.classList.remove('needs-ref-check');
            checkBtn.hidden  = true;
            ratingsEl.hidden = false;
        }

        // --- Rate & advance ---
        async function submitRating(quality) {
            ratingsEl.querySelectorAll('.rating-btn').forEach(b => b.disabled = true);
            try {
                const reviewOpts = {
                    method: 'POST',
                    // The server schedules nextReviewAt from this zone, so it matches
                    // the boundary the dashboard and reader use to decide "due today".
                    headers: {
                        'Content-Type': 'application/json',
                        'X-Time-Zone': Intl.DateTimeFormat().resolvedOptions().timeZone || ''
                    },
                    credentials: 'include',
                    body: JSON.stringify({ quality })
                };
                const res = await (window.KjvCsrf
                    ? window.KjvCsrf.fetch('/api/memorization/queue/' + entry.id + '/review', reviewOpts)
                    : fetch('/api/memorization/queue/' + entry.id + '/review', reviewOpts));
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

        // --- Recite: word diff ---
        function normalizeWord(w) {
            return w
                .replace(/[\u2018\u2019\u02BC]/g, "'")
                .replace(/[\u201C\u201D]/g, '"')
                .toLowerCase()
                .replace(/^[^a-z0-9']+|[^a-z0-9']+$/g, '');
        }

        function showTranscriptResult(transcript, expectedText) {
            const expWords = expectedText.split(/\s+/).filter(w => w.length > 0);
            const gotWords = transcript.split(/\s+/).filter(w => w.length > 0);

            let matched = 0;
            const html  = [];
            let gi = 0; // index into gotWords

            for (let ei = 0; ei < expWords.length; ei++) {
                const expNorm = normalizeWord(expWords[ei]);
                // Greedy scan with lookahead of 2 to handle Whisper filler words
                let foundAt = -1;
                for (let la = 0; la <= 2 && gi + la < gotWords.length; la++) {
                    if (normalizeWord(gotWords[gi + la]) === expNorm) {
                        foundAt = gi + la;
                        break;
                    }
                }
                if (foundAt >= 0) {
                    gi = foundAt + 1;
                    matched++;
                    html.push('<span class="word-correct">' + escapeHtml(expWords[ei]) + '</span>');
                } else {
                    html.push('<span class="word-wrong">' + escapeHtml(expWords[ei]) + '</span>');
                }
            }

            const accuracy = expWords.length > 0
                ? Math.round((matched / expWords.length) * 100)
                : 0;

            transcriptHeard.textContent = transcript;
            transcriptDiff.innerHTML = html.join(' ');

            const color = accuracy >= 90 ? '#2e6b35'
                        : accuracy >= 70 ? '#4a7c4e'
                        : accuracy >= 50 ? '#a07030'
                        : '#b05040';
            accuracyDisplay.style.color = color;
            accuracyDisplay.textContent = accuracy + '% accuracy';
            transcriptPanel.hidden = false;

            // Pre-highlight suggested quality
            const suggested = accuracy >= 90 ? 5
                             : accuracy >= 70 ? 4
                             : accuracy >= 50 ? 3
                             : 0;
            ratingsEl.querySelectorAll('.rating-btn').forEach(btn => {
                btn.classList.toggle('suggested', parseInt(btn.dataset.quality) === suggested);
            });
            checkAnswers();
        }

        // --- Recite: recording ---
        async function startRecording() {
            if (isRecording) return;
            errorEl.hidden = true;
            resetTranscriptPanel();

            const mimeType = ['audio/webm;codecs=opus', 'audio/webm', 'audio/ogg;codecs=opus',
                              'audio/ogg', 'audio/mp4']
                .find(t => MediaRecorder.isTypeSupported(t)) || '';

            let stream;
            try {
                stream = await navigator.mediaDevices.getUserMedia({ audio: true });
            } catch (err) {
                errorEl.textContent = 'Microphone access denied. Please allow microphone use in your browser settings.';
                errorEl.hidden = false;
                return;
            }

            audioChunks = [];
            let recorder;
            try {
                recorder = new MediaRecorder(stream, mimeType ? { mimeType } : {});
            } catch (err) {
                recorder = new MediaRecorder(stream);
            }
            mediaRecorder = recorder;

            recorder.addEventListener('dataavailable', e => {
                if (e.data && e.data.size > 0) audioChunks.push(e.data);
            });

            recorder.addEventListener('stop', () => {
                stream.getTracks().forEach(t => t.stop());
                sendRecording(recorder.mimeType || mimeType || 'audio/webm');
            });

            recorder.start(250); // 250ms timeslice — ensures chunks on short recordings
            isRecording = true;
            recordBtn.classList.add('recording');
            recordBtn.querySelector('.record-label').textContent = 'Tap to stop';
            recordingStatus.hidden = false;
        }

        function stopRecording() {
            if (!isRecording || !mediaRecorder) return;
            mediaRecorder.stop();
            isRecording = false;
            recordBtn.classList.remove('recording');
            recordBtn.querySelector('.record-label').textContent = 'Tap to record';
            recordingStatus.hidden = true;
        }

        async function sendRecording(mimeType) {
            recordBtn.disabled = true;

            const ext = mimeType.includes('ogg') ? 'ogg'
                      : mimeType.includes('mp4') ? 'mp4'
                      : 'webm';

            const blob = new Blob(audioChunks, { type: mimeType });
            audioChunks = [];

            const formData = new FormData();
            // Do NOT set Content-Type manually — browser sets multipart boundary automatically
            formData.append('audio', blob, 'recitation.' + ext);

            let data;
            try {
                const reciteOpts = {
                    method: 'POST',
                    credentials: 'include',
                    body: formData
                };
                const res = await (window.KjvCsrf
                    ? window.KjvCsrf.fetch('/api/memorization/queue/' + entry.id + '/recite', reciteOpts)
                    : fetch('/api/memorization/queue/' + entry.id + '/recite', reciteOpts));

                if (res.status === 401) { window.location.href = '/login.html'; return; }
                if (res.status === 503) {
                    errorEl.textContent = 'Voice recitation is not available on this server.';
                    errorEl.hidden = false;
                    return;
                }
                if (!res.ok) {
                    const err = await res.json().catch(() => ({}));
                    errorEl.textContent = err.message || 'Transcription failed. Please try again.';
                    errorEl.hidden = false;
                    return;
                }

                data = await res.json();
            } catch (e) {
                errorEl.textContent = 'Network error. Please try again.';
                errorEl.hidden = false;
                return;
            } finally {
                recordBtn.disabled = false;
            }

            showTranscriptResult(data.transcript, data.expectedText);
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

        if (refInput) {
            refInput.addEventListener('keydown', (e) => {
                if (e.key !== 'Enter') return;
                if (reciteMode && !card.classList.contains('needs-ref-check')) return;
                if (checkBtn.disabled) return;
                e.preventDefault();
                checkAnswers();
            });
        }

        recordBtn.addEventListener('click', () => {
            if (isRecording) {
                stopRecording();
            } else {
                startRecording();
            }
        });
    }
})();
