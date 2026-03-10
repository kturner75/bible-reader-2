(async function () {
    'use strict';

    // ── Utilities ────────────────────────────────────────────────────────────

    function escapeHtml(str) {
        const d = document.createElement('div');
        d.appendChild(document.createTextNode(String(str)));
        return d.innerHTML;
    }

    const TODAY = new Date().toISOString().slice(0, 10);

    function isDue(entry) {
        return !entry.nextReviewAt || entry.nextReviewAt <= TODAY;
    }

    function formatDueDate(nextReviewAt) {
        if (!nextReviewAt || nextReviewAt <= TODAY) return 'Due today';
        const diff = Math.round((new Date(nextReviewAt) - new Date(TODAY)) / 86400000);
        if (diff === 1) return 'Tomorrow';
        return `In ${diff} days`;
    }

    function masteryDots(level) {
        return '●'.repeat(level) + '○'.repeat(5 - level);
    }

    function passageRef(entry) {
        return entry.fromVerseRef === entry.toVerseRef
            ? entry.fromVerseRef
            : `${entry.fromVerseRef} – ${entry.toVerseRef}`;
    }

    function launchTraining(entries) {
        sessionStorage.setItem('kjv_training_session', JSON.stringify({ entries, index: 0 }));
        window.location.href = '/train';
    }

    // recentHistory arrives newest-first; dots render oldest→newest (left→right)
    function historyDots(recentHistory) {
        if (!recentHistory || recentHistory.length === 0) return '';
        const dots = [...recentHistory].reverse().map(h => {
            const cls   = h.quality >= 4 ? 'hist-good'
                        : h.quality === 3 ? 'hist-hard'
                        : 'hist-again';
            const label = h.quality >= 4 ? 'Good/Easy'
                        : h.quality === 3 ? 'Hard'
                        : 'Again';
            return `<span class="hist-dot ${cls}" title="${label} — ${h.reviewedAt}"></span>`;
        }).join('');
        return `<div class="queue-history">${dots}</div>`;
    }

    // ── Auth ─────────────────────────────────────────────────────────────────

    let currentUser = null;
    try {
        const res = await fetch('/api/auth/me', { credentials: 'include' });
        if (res.ok) {
            currentUser = await res.json();
        } else {
            window.location.href = '/login.html';
            return;
        }
    } catch (_) {
        window.location.href = '/login.html';
        return;
    }

    // ── Nav ──────────────────────────────────────────────────────────────────

    const displayName = currentUser.displayName || currentUser.name || currentUser.email;
    document.getElementById('nav-links').innerHTML = `
        <span class="nav-user">${escapeHtml(displayName)}</span>
        <a href="/read" class="btn-nav">Open Reader</a>
        <button class="nav-signout" id="nav-signout">Sign Out</button>
    `;
    document.getElementById('nav-signout').addEventListener('click', async () => {
        await fetch('/api/auth/logout', { method: 'POST', credentials: 'include' });
        window.location.href = '/landing.html';
    });

    // ── Heading ──────────────────────────────────────────────────────────────

    const firstName = displayName.split(/[\s@]/)[0];
    document.getElementById('dash-greeting').textContent = `Welcome back, ${firstName}`;
    document.getElementById('dash-date').textContent = new Date().toLocaleDateString(
        'en-US', { weekday: 'long', year: 'numeric', month: 'long', day: 'numeric' }
    );

    // ── Continue Reading ──────────────────────────────────────────────────────

    const savedVerseId = localStorage.getItem('kjv_current_verse');
    if (savedVerseId && parseInt(savedVerseId, 10) > 1) {
        try {
            const res = await fetch(`/api/verses?from=${encodeURIComponent(savedVerseId)}&count=1`);
            if (res.ok) {
                const data = await res.json();
                const verses = data.verses || data;
                if (verses?.length > 0) {
                    const v = verses[0];
                    const ref = `${v.book} ${v.chapter}`;
                    document.getElementById('reading-ref').textContent = ref;
                    const link = document.getElementById('reading-link');
                    link.href = `/read?vid=${savedVerseId}`;
                    link.textContent = `Open ${ref} →`;
                }
            }
        } catch (_) { /* keep default */ }
    } else {
        document.getElementById('reading-ref').textContent = 'Genesis 1';
    }

    // ── Memorization Queue + Streak + Featured Passages (parallel) ───────────

    let allEntries     = [];
    let streakData     = null;
    let globalPassages = [];
    try {
        const [queueRes, streakRes, globalRes] = await Promise.all([
            fetch('/api/memorization/queue',           { credentials: 'include' }),
            fetch('/api/memorization/streak',          { credentials: 'include' }),
            fetch('/api/memorization/global-passages', { credentials: 'include' }),
        ]);
        if (queueRes.ok)  allEntries     = await queueRes.json();
        if (streakRes.ok) streakData     = await streakRes.json();
        if (globalRes.ok) globalPassages = await globalRes.json();
    } catch (_) { /* stay with defaults */ }

    // Streak card
    const streakCountEl = document.getElementById('streak-count');
    const streakSubEl   = document.getElementById('streak-sub');
    if (streakData && streakData.currentStreak > 0) {
        const days = streakData.currentStreak;
        streakCountEl.textContent = `${days} day${days === 1 ? '' : 's'}`;
        streakCountEl.classList.add('streak-nonzero');
        const best = streakData.longestStreak;
        streakSubEl.textContent = `Best: ${best} day${best === 1 ? '' : 's'}`;
    } else {
        streakCountEl.textContent = '—';
        streakSubEl.textContent   = 'Complete a review to start';
    }

    const dueEntries = allEntries.filter(isDue);
    const dueCount   = dueEntries.length;

    // Due card
    const dueCountEl = document.getElementById('due-count');
    const dueSubEl   = document.getElementById('due-sub');
    const trainBtn   = document.getElementById('train-now-btn');

    if (dueCount === 0 && allEntries.length === 0) {
        dueCountEl.textContent = 'None yet';
        dueSubEl.textContent   = 'Add passages from the reader';
    } else if (dueCount === 0) {
        dueCountEl.textContent = 'All caught up';
        dueSubEl.textContent   = 'Check back later';
    } else {
        dueCountEl.textContent = `${dueCount} passage${dueCount === 1 ? '' : 's'}`;
        dueCountEl.classList.add('due-nonzero');
        dueSubEl.textContent   = 'ready for review';
        trainBtn.hidden = false;
        trainBtn.addEventListener('click', () => launchTraining(dueEntries));
    }

    // Queue section
    const queueSection = document.getElementById('queue-section');
    queueSection.hidden = false;

    if (allEntries.length === 0) {
        document.getElementById('queue-empty').hidden = false;
    } else {
        document.getElementById('queue-badge').textContent = allEntries.length;
        const list = document.getElementById('queue-list');

        allEntries.forEach(entry => {
            const due = isDue(entry);
            const row = document.createElement('div');
            row.className = 'queue-row';
            row.innerHTML = `
                <span class="queue-ref">${escapeHtml(passageRef(entry))}</span>
                <span class="queue-mastery" title="Mastery level ${entry.masteryLevel} of 5">${masteryDots(entry.masteryLevel)}</span>
                <span class="queue-due${due ? ' is-due' : ''}">${escapeHtml(formatDueDate(entry.nextReviewAt))}</span>
                <button class="queue-practice-btn">Practice</button>
                ${historyDots(entry.recentHistory)}
            `;
            row.querySelector('.queue-practice-btn').addEventListener('click', () => {
                launchTraining([entry]);
            });
            list.appendChild(row);
        });
    }

    // ── Featured Passages ──────────────────────────────────────────────────────

    function renderFeaturedPassages(passages) {
        if (!passages || passages.length === 0) return;

        const section = document.getElementById('featured-section');
        const list    = document.getElementById('featured-list');
        section.hidden = false;

        passages.forEach(p => {
            const ref = p.fromVerseRef === p.toVerseRef
                ? p.fromVerseRef
                : `${p.fromVerseRef} – ${p.toVerseRef}`;

            const row = document.createElement('div');
            row.className = 'featured-row';
            row.innerHTML = `
                <span class="featured-title">${escapeHtml(p.title)}</span>
                <span class="featured-ref">${escapeHtml(ref)}</span>
                <button class="featured-add-btn${p.alreadyQueued ? ' is-added' : ''}"
                        ${p.alreadyQueued ? 'disabled' : ''}>
                    ${p.alreadyQueued ? '✓ Added' : '+ Add'}
                </button>
            `;

            if (!p.alreadyQueued) {
                row.querySelector('.featured-add-btn').addEventListener('click', async function () {
                    this.disabled = true;
                    this.textContent = 'Adding…';
                    try {
                        const res = await fetch('/api/memorization/queue', {
                            method: 'POST',
                            credentials: 'include',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({ naturalKey: p.naturalKey }),
                        });
                        if (res.ok) {
                            this.textContent = '✓ Added';
                            this.classList.add('is-added');
                        } else {
                            this.disabled = false;
                            this.textContent = '+ Add';
                        }
                    } catch (_) {
                        this.disabled = false;
                        this.textContent = '+ Add';
                    }
                });
            }

            list.appendChild(row);
        });
    }

    renderFeaturedPassages(globalPassages);

})();
