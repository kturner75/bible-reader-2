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

        document.title = (isSingle ? verses[0].reference : `${entry.fromVerseRef} – ${entry.toVerseRef}`)
                         + ' — Memory Training';
        refEl.textContent = isSingle
            ? verses[0].reference
            : `${entry.fromVerseRef} – ${entry.toVerseRef}`;

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

        function setPeeking(peeking) {
            card.classList.toggle('peeking', peeking);
            peekToggle.classList.toggle('active', peeking);
            peekToggle.textContent = peeking ? 'Hide passage' : 'Peek';
            peekToggle.setAttribute('aria-expanded', String(peeking));
            if (!peeking) focusFirstBlank();
        }

        beginBtn.addEventListener('click', () => {
            card.classList.remove('pretraining');
            peekToggle.hidden = false;
            focusFirstBlank();
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

        // --- Check (fill-in-blank mode) ---
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
            ratingsEl.hidden = false;
            checkBtn.hidden  = true;
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

        recordBtn.addEventListener('click', () => {
            if (isRecording) {
                stopRecording();
            } else {
                startRecording();
            }
        });
    }
})();
