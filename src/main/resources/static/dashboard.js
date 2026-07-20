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
        window.location.href = '/train?from=dashboard';
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

    // ── Memorization Queue + Streak + Featured Passages + Reading Plans + Heatmap (parallel) ─

    let allEntries     = [];
    let streakData     = null;
    let globalPassages = [];
    let plansData      = [];
    let heatmapData    = {};
    let sermonNotes    = [];
    let collections    = [];
    try {
        const [queueRes, streakRes, globalRes, plansRes, heatmapRes, sermonNotesRes, collectionsRes] = await Promise.all([
            fetch('/api/memorization/queue',           { credentials: 'include' }),
            fetch('/api/memorization/streak',          { credentials: 'include' }),
            fetch('/api/memorization/global-passages', { credentials: 'include' }),
            fetch('/api/plans',                        { credentials: 'include' }),
            fetch('/api/activity/heatmap',             { credentials: 'include' }),
            fetch('/api/sermon-notes',                 { credentials: 'include' }),
            fetch('/api/collections',                  { credentials: 'include' }),
        ]);
        if (queueRes.ok)        allEntries     = await queueRes.json();
        if (streakRes.ok)       streakData     = await streakRes.json();
        if (globalRes.ok)       globalPassages = await globalRes.json();
        if (plansRes.ok)        plansData      = await plansRes.json();
        if (heatmapRes.ok)      heatmapData    = await heatmapRes.json();
        if (sermonNotesRes.ok)  sermonNotes    = await sermonNotesRes.json();
        if (collectionsRes.ok)  collections    = await collectionsRes.json();
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

    // ── Reading Plans ──────────────────────────────────────────────────────────

    function buildPlanRow(plan) {
        const row = document.createElement('div');
        row.className = 'plan-row';

        let infoHtml;
        let actionsHtml;

        if (!plan.enrolled) {
            // Unenrolled
            infoHtml = `
                <span class="plan-title">${escapeHtml(plan.title)}</span>
                <span class="plan-progress plan-unenrolled">${plan.totalDays} days</span>
            `;
            actionsHtml = `<button class="btn-secondary plan-enroll-btn">Enroll</button>`;
        } else if (plan.currentDay > plan.totalDays) {
            // Finished
            infoHtml = `
                <span class="plan-title">${escapeHtml(plan.title)}</span>
                <span class="plan-progress plan-finished">Completed ✓</span>
                <div class="plan-progress-track"><div class="plan-progress-fill" style="width:100%"></div></div>
            `;
            actionsHtml = `<span class="plan-done-label">All done ✓</span>`;
        } else {
            // Enrolled, in progress
            const dayLabel = plan.todayDay ? escapeHtml(plan.todayDay.label) : '';
            const readHref = plan.todayDay ? `/read?vid=${plan.todayDay.fromVerseId}` : '/read';
            const pct      = Math.round((plan.currentDay - 1) / plan.totalDays * 100);
            const streak   = plan.streakDays && plan.streakDays > 0
                ? ` · ${plan.streakDays}-day streak` : '';
            infoHtml = `
                <span class="plan-title">${escapeHtml(plan.title)}</span>
                <span class="plan-progress">Day ${plan.currentDay} of ${plan.totalDays}${escapeHtml(streak)}</span>
                <div class="plan-progress-track"><div class="plan-progress-fill" style="width:${pct}%"></div></div>
                ${dayLabel ? `<a class="plan-day-label" href="${readHref}">${dayLabel}</a>` : ''}
            `;
            actionsHtml = `
                <a class="btn-secondary plan-open-btn" href="${readHref}">Open →</a>
                <button class="btn-primary plan-complete-btn">Mark Complete</button>
            `;
        }

        row.innerHTML = `
            <div class="plan-info">${infoHtml}</div>
            <div class="plan-actions">${actionsHtml}</div>
        `;
        return row;
    }

    function attachPlanListeners(row, plan) {
        const enrollBtn = row.querySelector('.plan-enroll-btn');
        if (enrollBtn) {
            enrollBtn.addEventListener('click', async function () {
                this.disabled = true;
                this.textContent = 'Enrolling…';
                try {
                    const res = await fetch(`/api/plans/${plan.id}/enroll`, {
                        method: 'POST',
                        credentials: 'include',
                    });
                    if (res.ok) {
                        const updated = await res.json();
                        const newRow = buildPlanRow(updated);
                        attachPlanListeners(newRow, updated);
                        row.replaceWith(newRow);
                    } else {
                        this.disabled = false;
                        this.textContent = 'Enroll';
                    }
                } catch (_) {
                    this.disabled = false;
                    this.textContent = 'Enroll';
                }
            });
        }

        const completeBtn = row.querySelector('.plan-complete-btn');
        if (completeBtn) {
            completeBtn.addEventListener('click', async function () {
                this.disabled = true;
                this.textContent = 'Saving…';
                try {
                    const res = await fetch(`/api/plans/${plan.id}/complete-day`, {
                        method: 'POST',
                        credentials: 'include',
                    });
                    if (res.ok) {
                        const updated = await res.json();
                        const newRow = buildPlanRow(updated);
                        attachPlanListeners(newRow, updated);
                        row.replaceWith(newRow);
                    } else {
                        this.disabled = false;
                        this.textContent = 'Mark Complete';
                    }
                } catch (_) {
                    this.disabled = false;
                    this.textContent = 'Mark Complete';
                }
            });
        }
    }

    function renderPlans(plans) {
        if (!plans || plans.length === 0) return;

        const section = document.getElementById('plans-section');
        const list    = document.getElementById('plans-list');
        section.hidden = false;

        plans.forEach(plan => {
            const row = buildPlanRow(plan);
            attachPlanListeners(row, plan);
            list.appendChild(row);
        });

        // Populate "Today's Reading" summary card from first active enrolled plan
        const activePlan = plans.find(p => p.enrolled && p.currentDay <= p.totalDays && p.todayDay);
        if (activePlan) {
            const card    = document.getElementById('today-reading-card');
            const refEl   = document.getElementById('today-reading-ref');
            const subEl   = document.getElementById('today-reading-sub');
            const linkEl  = document.getElementById('today-reading-link');
            card.hidden   = false;
            refEl.textContent  = activePlan.todayDay.label;
            subEl.textContent  = `Day ${activePlan.currentDay} of ${activePlan.totalDays} · ${activePlan.title}`;
            linkEl.href        = `/read?vid=${activePlan.todayDay.fromVerseId}`;
        }
    }

    renderPlans(plansData);

    // ── Activity Heatmap ───────────────────────────────────────────────────────

    function renderHeatmap(data) {
        // data: { "2026-03-14": 3, ... } — only active days included

        const CELL  = 11; // px per cell
        const GAP   = 2;  // px gap
        const STEP  = CELL + GAP;

        const grid   = document.getElementById('heatmap-grid');
        const months = document.getElementById('heatmap-months');

        // Determine the range: go back 52 full weeks, then align left edge to Sunday
        const today = new Date();
        today.setHours(0, 0, 0, 0);

        const start = new Date(today);
        start.setDate(start.getDate() - 364);          // 365 days total
        start.setDate(start.getDate() - start.getDay()); // align left edge to Sunday

        const totalDays  = Math.round((today - start) / 86400000) + 1;
        const totalWeeks = Math.ceil(totalDays / 7);

        // Build grid cells (appended Sun→Sat per week; grid-auto-flow:column places them correctly)
        for (let w = 0; w < totalWeeks; w++) {
            for (let d = 0; d < 7; d++) {
                const date = new Date(start);
                date.setDate(start.getDate() + w * 7 + d);

                const cell = document.createElement('div');
                cell.className = 'heat-cell';

                if (date > today) {
                    cell.classList.add('heat-future');
                } else {
                    const iso   = date.toISOString().slice(0, 10);
                    const count = data[iso] || 0;
                    const level = count === 0 ? 0 : count === 1 ? 1 : count <= 3 ? 2 : count <= 6 ? 3 : 4;
                    cell.classList.add(`heat-${level}`);
                    cell.title = count > 0
                        ? `${count} activit${count === 1 ? 'y' : 'ies'} on ${iso}`
                        : `No activity on ${iso}`;
                }

                grid.appendChild(cell);
            }
        }

        // Month labels — positioned above the week column where each month begins
        const MONTH_NAMES = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
        let lastMonth = -1;
        for (let w = 0; w < totalWeeks; w++) {
            // The Sunday of this week
            const weekStart = new Date(start);
            weekStart.setDate(start.getDate() + w * 7);
            const m = weekStart.getMonth();
            if (m !== lastMonth) {
                lastMonth = m;
                const label = document.createElement('span');
                label.className     = 'heatmap-month-label';
                label.textContent   = MONTH_NAMES[m];
                label.style.left    = `${w * STEP}px`;
                months.appendChild(label);
            }
        }
    }

    renderHeatmap(heatmapData);

    // ── Sermon Notes ─────────────────────────────────────────────────────────
    // Markdown-lite renderer ported from app.js (renderNoteInline/renderNoteMarkdown) —
    // dashboard.js and app.js are separate unbundled scripts on different pages, so this
    // is a deliberate copy, not a shared module. Sermon notes have no chapter/book scope,
    // so bare [12]-style numeric refs are left as plain text; only [pid=N] and [Ref] links resolve.

    const collectionsById = {};
    collections.forEach(c => { collectionsById[c.id] = c; });

    function renderNoteInline(text) {
        let html = escapeHtml(text);
        html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
        html = html.replace(/\*([^*]+)\*/g, '<em>$1</em>');
        html = html.replace(/\[([^\]]+)\]/g, (match, ref) => {
            const trimmed = ref.trim();
            const pid = trimmed.match(/^pid=(\d+)$/);
            if (pid) {
                const collection = collectionsById[parseInt(pid[1], 10)];
                const label = collection ? collection.label : `Collection #${pid[1]}`;
                return `<a class="note-collection-link" data-collection-id="${pid[1]}" href="#">${escapeHtml(label)}</a>`;
            }
            if (/^\d+$/.test(trimmed)) return match; // no chapter scope to resolve against
            return `<a class="note-verse-link" data-ref="${trimmed}" href="#">${match}</a>`;
        });
        return html;
    }

    function renderNoteMarkdown(text) {
        const lines = text.split('\n');
        const out = [];
        let list = null;
        let paragraph = [];

        const flushParagraph = () => {
            if (paragraph.length) {
                out.push(`<p>${paragraph.join('<br>')}</p>`);
                paragraph = [];
            }
        };
        const closeList = () => {
            if (list) {
                out.push(`</${list}>`);
                list = null;
            }
        };

        for (const rawLine of lines) {
            const line = rawLine.trim();
            const heading = line.match(/^(#{1,3})\s+(.*)/);
            const bullet = line.match(/^[-*]\s+(.*)/);
            const numbered = line.match(/^\d+[.)]\s+(.*)/);

            if (!line) {
                flushParagraph();
                closeList();
            } else if (heading) {
                flushParagraph();
                closeList();
                const level = heading[1].length;
                out.push(`<h${level + 3}>${renderNoteInline(heading[2])}</h${level + 3}>`);
            } else if (bullet || numbered) {
                flushParagraph();
                const type = bullet ? 'ul' : 'ol';
                if (list !== type) {
                    closeList();
                    out.push(`<${type}>`);
                    list = type;
                }
                out.push(`<li>${renderNoteInline((bullet || numbered)[1])}</li>`);
            } else {
                closeList();
                paragraph.push(renderNoteInline(line));
            }
        }
        flushParagraph();
        closeList();
        return out.join('');
    }

    function formatUpdatedAt(iso) {
        return new Date(iso).toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' });
    }

    const notesSection = document.getElementById('sermon-notes-section');
    const notesList     = document.getElementById('sermon-notes-list');
    const notesEmpty    = document.getElementById('sermon-notes-empty');

    function renderSermonNotesList() {
        notesList.innerHTML = '';
        if (sermonNotes.length === 0) {
            notesEmpty.hidden = false;
            return;
        }
        notesEmpty.hidden = true;
        sermonNotes.forEach(n => {
            const row = document.createElement('div');
            row.className = 'sermon-note-row';
            row.innerHTML = `
                <span class="sermon-note-row-title">${escapeHtml(n.title)}</span>
                <span class="sermon-note-row-meta">Updated ${formatUpdatedAt(n.updatedAt)}</span>
                <span class="sermon-note-row-snippet">${escapeHtml(n.snippet)}</span>
            `;
            row.addEventListener('click', () => openSermonNote(n.id));
            notesList.appendChild(row);
        });
    }

    renderSermonNotesList();

    // ── Sermon Note Editor Modal ────────────────────────────────────────────

    const overlay      = document.getElementById('sermon-note-overlay');
    const modalTitle    = document.getElementById('sermon-note-modal-title');
    const viewSection   = document.getElementById('sermon-note-view');
    const viewTitle     = document.getElementById('sermon-note-view-title-text');
    const viewBody      = document.getElementById('sermon-note-view-body');
    const viewActions   = document.getElementById('sermon-note-view-actions');
    const editSection   = document.getElementById('sermon-note-edit');
    const titleInput    = document.getElementById('sermon-note-title-input');
    const textarea      = document.getElementById('sermon-note-textarea');
    const charCurrent   = document.getElementById('sermon-note-char-current');

    let editingNoteId = null;   // full note object's id while modal open; null while creating
    let savedTitle = '';        // last-saved title/note, to restore the form on Cancel
    let savedNoteText = '';

    function updateCharCount() {
        charCurrent.textContent = textarea.value.length;
    }

    function setMode(mode) {
        if (mode === 'view') {
            viewSection.hidden = false;
            viewActions.hidden = false;
            editSection.hidden = true;
        } else {
            viewSection.hidden = true;
            viewActions.hidden = true;
            editSection.hidden = false;
            titleInput.focus();
        }
    }

    async function openSermonNote(id) {
        try {
            const res = await fetch(`/api/sermon-notes/${id}`, { credentials: 'include' });
            if (!res.ok) return;
            const note = await res.json();
            editingNoteId = note.id;
            savedTitle = note.title;
            savedNoteText = note.note;
            modalTitle.textContent = 'Sermon Note';
            viewTitle.textContent = note.title;
            viewBody.innerHTML = renderNoteMarkdown(note.note);
            titleInput.value = note.title;
            textarea.value = note.note;
            updateCharCount();
            overlay.hidden = false;
            setMode('view');
        } catch (_) { /* ignore */ }
    }

    function openNewSermonNote() {
        editingNoteId = null;
        savedTitle = '';
        savedNoteText = '';
        modalTitle.textContent = 'New Sermon Note';
        titleInput.value = '';
        textarea.value = '';
        updateCharCount();
        overlay.hidden = false;
        setMode('edit');
    }

    function closeModal() {
        overlay.hidden = true;
        editingNoteId = null;
    }

    async function saveSermonNote() {
        const title = titleInput.value.trim();
        const note = textarea.value.trim();
        if (!title || !note) return;
        try {
            const res = await fetch(
                editingNoteId ? `/api/sermon-notes/${editingNoteId}` : '/api/sermon-notes',
                {
                    method: editingNoteId ? 'PUT' : 'POST',
                    credentials: 'include',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ title, note }),
                }
            );
            if (!res.ok) return;
            const saved = await res.json();
            editingNoteId = saved.id;
            savedTitle = saved.title;
            savedNoteText = saved.note;

            const listRes = await fetch('/api/sermon-notes', { credentials: 'include' });
            if (listRes.ok) sermonNotes = await listRes.json();
            renderSermonNotesList();

            modalTitle.textContent = 'Sermon Note';
            viewTitle.textContent = saved.title;
            viewBody.innerHTML = renderNoteMarkdown(saved.note);
            setMode('view');
        } catch (_) { /* ignore */ }
    }

    async function deleteSermonNote() {
        if (!editingNoteId) return;
        if (!confirm('Delete this sermon note? This cannot be undone.')) return;
        try {
            const res = await fetch(`/api/sermon-notes/${editingNoteId}`, {
                method: 'DELETE',
                credentials: 'include',
            });
            if (!res.ok && res.status !== 204) return;
            sermonNotes = sermonNotes.filter(n => n.id !== editingNoteId);
            renderSermonNotesList();
            closeModal();
        } catch (_) { /* ignore */ }
    }

    document.getElementById('sermon-note-new-btn').addEventListener('click', openNewSermonNote);
    document.getElementById('sermon-notes-empty-new-btn').addEventListener('click', openNewSermonNote);
    document.getElementById('sermon-note-close').addEventListener('click', closeModal);
    document.getElementById('sermon-note-done-btn').addEventListener('click', closeModal);
    document.getElementById('sermon-note-edit-btn').addEventListener('click', () => setMode('edit'));
    document.getElementById('sermon-note-save-btn').addEventListener('click', saveSermonNote);
    document.getElementById('sermon-note-delete-btn').addEventListener('click', deleteSermonNote);
    document.getElementById('sermon-note-cancel-btn').addEventListener('click', () => {
        if (editingNoteId) {
            // Discard the draft — restore the form to the last-saved values so a later
            // Edit + Save doesn't resubmit this canceled edit.
            titleInput.value = savedTitle;
            textarea.value = savedNoteText;
            updateCharCount();
            setMode('view');
        } else {
            closeModal();
        }
    });
    textarea.addEventListener('input', updateCharCount);
    overlay.addEventListener('click', e => { if (e.target === overlay) closeModal(); });

    // Verse / collection links inside a rendered sermon note navigate away to the reader
    viewBody.addEventListener('click', async e => {
        const collectionLink = e.target.closest('.note-collection-link');
        if (collectionLink) {
            e.preventDefault();
            window.location.href = `/read/collection/${collectionLink.dataset.collectionId}`;
            return;
        }
        const verseLink = e.target.closest('.note-verse-link');
        if (verseLink) {
            e.preventDefault();
            try {
                const res = await fetch(`/api/reference?ref=${encodeURIComponent(verseLink.dataset.ref)}`);
                if (res.ok) {
                    const parsed = await res.json();
                    if (parsed.valid) window.location.href = `/read?vid=${parsed.verseId}`;
                }
            } catch (_) { /* ignore */ }
        }
    });

})();
