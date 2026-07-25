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
    let passages       = [];
    try {
        const [queueRes, streakRes, globalRes, plansRes, heatmapRes, sermonNotesRes, collectionsRes, passagesRes] = await Promise.all([
            fetch('/api/memorization/queue',           { credentials: 'include' }),
            fetch('/api/memorization/streak',          { credentials: 'include' }),
            fetch('/api/memorization/global-passages', { credentials: 'include' }),
            fetch('/api/plans',                        { credentials: 'include' }),
            fetch('/api/activity/heatmap',             { credentials: 'include' }),
            fetch('/api/sermon-notes',                 { credentials: 'include' }),
            fetch('/api/collections',                  { credentials: 'include' }),
            fetch('/api/passages',                     { credentials: 'include' }),
        ]);
        if (queueRes.ok)        allEntries     = await queueRes.json();
        if (streakRes.ok)       streakData     = await streakRes.json();
        if (globalRes.ok)       globalPassages = await globalRes.json();
        if (plansRes.ok)        plansData      = await plansRes.json();
        if (heatmapRes.ok)      heatmapData    = await heatmapRes.json();
        if (sermonNotesRes.ok)  sermonNotes    = await sermonNotesRes.json();
        if (collectionsRes.ok)  collections    = await collectionsRes.json();
        if (passagesRes.ok)     passages       = await passagesRes.json();
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
    // so bare [12]-style numeric refs are left as plain text; [v=…], legacy [passage=uuid],
    // [pid=N], and [Ref] links resolve.

    const collectionsById = {};
    collections.forEach(c => { collectionsById[c.id] = c; });
    const passagesById = {};
    passages.forEach(p => { passagesById[p.id] = p; });

    function passageDisplayLabel(p) {
        if (!p) return 'Passage';
        const title = p.title && String(p.title).trim();
        return title || p.reference || 'Passage';
    }

    function rangesFromNaturalKey(naturalKey) {
        const ranges = [];
        for (const part of String(naturalKey).split(',')) {
            const p = part.trim();
            if (p.includes(':')) {
                const [a, b] = p.split(':', 2).map(x => parseInt(x.trim(), 10));
                ranges.push({ from: Math.min(a, b), to: Math.max(a, b) });
            } else {
                const v = parseInt(p, 10);
                ranges.push({ from: v, to: v });
            }
        }
        ranges.sort((a, b) => a.from - b.from);
        const merged = [];
        let cur = ranges[0];
        for (let i = 1; i < ranges.length; i++) {
            const next = ranges[i];
            if (next.from <= cur.to + 1) cur = { from: cur.from, to: Math.max(cur.to, next.to) };
            else { merged.push(cur); cur = next; }
        }
        if (cur) merged.push(cur);
        return merged;
    }

    function serializeVBody(ranges) {
        return ranges.map(r => r.from === r.to ? String(r.from) : `${r.from}-${r.to}`).join(',');
    }

    function serializeVToken(ranges) {
        return `[v=${serializeVBody(ranges)}]`;
    }

    function findPassageByVBody(body) {
        return passages.find(p => {
            try {
                return serializeVBody(rangesFromNaturalKey(p.naturalKey)) === body;
            } catch { return false; }
        });
    }

    function renderNoteInline(text) {
        let html = escapeHtml(text);
        html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
        html = html.replace(/\*([^*]+)\*/g, '<em>$1</em>');
        html = html.replace(/\[([^\]]+)\]/g, (match, ref) => {
            const trimmed = ref.trim();
            const vTok = trimmed.match(/^v=(.+)$/i);
            if (vTok) {
                const body = vTok[1].replace(/\s/g, '');
                const p = findPassageByVBody(body);
                const label = p ? passageDisplayLabel(p) : body;
                return `<a class="note-range-link" data-v="${escapeHtml(body)}" href="#">${escapeHtml(label)}</a>`;
            }
            const passageTok = trimmed.match(/^passage=([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/i);
            if (passageTok) {
                const p = passagesById[passageTok[1]];
                const label = p ? passageDisplayLabel(p) : 'Passage';
                return `<a class="note-passage-link" data-passage-id="${passageTok[1]}" href="#">${escapeHtml(label)}</a>`;
            }
            const pid = trimmed.match(/^pid=(\d+)$/);
            if (pid) {
                const collection = collectionsById[parseInt(pid[1], 10)];
                const label = collection ? collection.label : `Collection #${pid[1]}`;
                return `<a class="note-collection-link" data-collection-id="${pid[1]}" href="#">${escapeHtml(label)}</a>`;
            }
            if (/^\d+$/.test(trimmed)) return match;
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

    async function normalizeNoteLinksOnSave(text) {
        if (!text) return text;
        const re = /\[([^\]]+)\]/g;
        const parts = [];
        let last = 0;
        let m;
        while ((m = re.exec(text)) !== null) {
            parts.push(text.slice(last, m.index));
            const inner = m[1].trim();
            last = m.index + m[0].length;
            if (/^v=/i.test(inner) || /^pid=\d+$/i.test(inner)
                || /^passage=[0-9a-f-]{36}$/i.test(inner) || /^\d+$/.test(inner)) {
                parts.push(m[0]);
                continue;
            }
            try {
                const res = await fetch(`/api/reference?ref=${encodeURIComponent(inner)}`);
                if (res.ok) {
                    const parsed = await res.json();
                    if (parsed.valid && parsed.verseId) {
                        parts.push(serializeVToken([{ from: parsed.verseId, to: parsed.verseId }]));
                        continue;
                    }
                }
            } catch (_) { /* keep original */ }
            parts.push(m[0]);
        }
        parts.push(text.slice(last));
        return parts.join('');
    }

    async function hydrateRangeLinkLabels(root) {
        if (!root) return;
        for (const link of root.querySelectorAll('.note-range-link[data-v]')) {
            const body = link.dataset.v;
            const p = findPassageByVBody(body);
            if (p) { link.textContent = passageDisplayLabel(p); continue; }
            if (link.dataset.labelReady) continue;
            try {
                const res = await fetch(`/api/ranges?v=${encodeURIComponent(body)}`);
                if (!res.ok) continue;
                const data = await res.json();
                link.textContent = data.reference || body;
                link.dataset.labelReady = '1';
            } catch (_) { /* ignore */ }
        }
    }

    function setMode(mode) {
        if (mode === 'view') {
            viewSection.hidden = false;
            viewActions.hidden = false;
            editSection.hidden = true;
            hydrateRangeLinkLabels(viewBody);
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
        let note = textarea.value.trim();
        if (!title || !note) return;
        try {
            note = await normalizeNoteLinksOnSave(note);
            textarea.value = note;
            updateCharCount();
            const maxLen = parseInt(textarea.getAttribute('maxlength'), 10) || 20000;
            if (note.length > maxLen) {
                window.alert(`Note is too long after converting scripture links (${maxLen} char limit)`);
                return;
            }
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

    // Insert scripture picker (Matching Verses + My Passages)
    const insertOverlay = document.getElementById('passage-insert-overlay');
    const insertClose = document.getElementById('passage-insert-close');
    const insertSearch = document.getElementById('passage-insert-search');
    const insertTabs = document.getElementById('passage-insert-tabs');
    const insertBrowse = document.getElementById('passage-insert-browse');
    const insertCount = document.getElementById('passage-insert-count');
    const insertList = document.getElementById('passage-insert-list');
    const insertExpand = document.getElementById('passage-insert-expand');
    const insertExpandBack = document.getElementById('passage-insert-expand-back');
    const insertExpandCount = document.getElementById('passage-insert-expand-count');
    const insertChapters = document.getElementById('passage-insert-chapters');
    const insertConfirm = document.getElementById('passage-insert-confirm');
    const insertBtn = document.getElementById('sermon-note-insert-passage-btn');

    const INSERT_MAX_VERSES = 500;
    let insertTab = 'verses';
    let insertMode = 'browse';
    let insertSearchTimer = null;
    let insertSearchGen = 0;

    function closeInsertPicker() {
        insertOverlay.hidden = true;
        insertMode = 'browse';
        if (insertSearchTimer) {
            clearTimeout(insertSearchTimer);
            insertSearchTimer = null;
        }
    }

    function syncInsertTabs() {
        if (!insertTabs) return;
        insertTabs.querySelectorAll('.passage-insert-tab').forEach(btn => {
            const active = btn.dataset.tab === insertTab;
            btn.classList.toggle('active', active);
            btn.setAttribute('aria-selected', active ? 'true' : 'false');
        });
        insertSearch.placeholder = insertTab === 'verses'
            ? 'Search scripture or reference…'
            : 'Filter by title or reference…';
    }

    function showInsertBrowse() {
        insertMode = 'browse';
        if (insertBrowse) insertBrowse.hidden = false;
        if (insertExpand) insertExpand.hidden = true;
        if (insertTabs) insertTabs.hidden = false;
        insertSearch.hidden = false;
    }

    function setInsertTab(tab) {
        insertTab = tab === 'passages' ? 'passages' : 'verses';
        syncInsertTabs();
        showInsertBrowse();
        renderInsertBrowse();
        insertSearch.focus();
    }

    function insertVTokenFromNaturalKey(naturalKey) {
        let token;
        try {
            token = serializeVToken(rangesFromNaturalKey(naturalKey));
        } catch {
            return;
        }
        const start = textarea.selectionStart ?? textarea.value.length;
        const end = textarea.selectionEnd ?? start;
        const before = textarea.value.slice(0, start);
        const after = textarea.value.slice(end);
        const needsSpaceBefore = before.length > 0 && !/\s$/.test(before);
        const needsSpaceAfter = after.length > 0 && !/^\s/.test(after);
        const insert = (needsSpaceBefore ? ' ' : '') + token + (needsSpaceAfter ? ' ' : '');
        const next = before + insert + after;
        const maxLen = parseInt(textarea.getAttribute('maxlength'), 10);
        if (Number.isFinite(maxLen) && next.length > maxLen) {
            insertCount.textContent = `Not enough room for that link (${maxLen} char limit)`;
            return;
        }
        textarea.value = next;
        const caret = before.length + insert.length;
        textarea.focus();
        textarea.setSelectionRange(caret, caret);
        updateCharCount();
        closeInsertPicker();
    }

    function buildNaturalKeyFromIds(ids) {
        if (!ids.length) return null;
        const sorted = [...ids].sort((a, b) => a - b);
        const segs = [];
        let start = sorted[0], end = sorted[0];
        for (let i = 1; i < sorted.length; i++) {
            if (sorted[i] === end + 1) { end = sorted[i]; }
            else {
                segs.push(start === end ? `${start}` : `${start}:${end}`);
                start = end = sorted[i];
            }
        }
        segs.push(start === end ? `${start}` : `${start}:${end}`);
        return segs.join(',');
    }

    function renderInsertPassages() {
        const q = (insertSearch.value || '').trim().toLowerCase();
        let list = passages;
        if (q) {
            list = list.filter(p => {
                const label = passageDisplayLabel(p).toLowerCase();
                return label.includes(q)
                    || (p.reference && p.reference.toLowerCase().includes(q))
                    || (p.title && p.title.toLowerCase().includes(q));
            });
        }
        insertCount.textContent = list.length === 1 ? '1 passage' : `${list.length} passages`;
        if (list.length === 0) {
            insertList.innerHTML = '<p class="collections-empty">No passages yet. Create one from the reader, or use Matching Verses.</p>';
            return;
        }
        insertList.innerHTML = list.map(p => `
            <button type="button" class="sermon-note-row passage-insert-row" data-passage-id="${p.id}"
                data-natural-key="${escapeHtml(p.naturalKey || '')}">
                <span class="sermon-note-row-title">${escapeHtml(passageDisplayLabel(p))}</span>
                <span class="sermon-note-row-meta">${escapeHtml(p.reference || '')}${p.global ? ' · Featured' : ''}</span>
            </button>`).join('');
        insertList.querySelectorAll('.passage-insert-row').forEach(btn => {
            btn.addEventListener('click', () => {
                const p = passages.find(x => x.id === btn.dataset.passageId);
                const key = btn.dataset.naturalKey || (p && p.naturalKey);
                if (key) insertVTokenFromNaturalKey(key);
            });
        });
    }

    async function runInsertVerseSearch() {
        const q = (insertSearch.value || '').trim();
        const gen = ++insertSearchGen;
        if (!q) {
            insertCount.textContent = 'Type to search verses';
            insertList.innerHTML =
                '<p class="collections-empty">Search by words or a reference like John 3:16.<br>' +
                'Then optionally include surrounding verses before inserting.</p>';
            return;
        }

        insertCount.textContent = 'Searching…';
        insertList.innerHTML = '<p class="collections-empty">Searching…</p>';

        let refHit = null;
        let searchVerses = [];
        try {
            const res = await fetch(`/api/reference?ref=${encodeURIComponent(q)}`);
            if (res.ok) {
                const refResult = await res.json();
                if (refResult.valid && refResult.verseId) {
                    const v = refResult.verse;
                    refHit = {
                        id: refResult.verseId,
                        book: v?.book || '',
                        chapter: v?.chapter,
                        verse: v?.verse ?? 1,
                        text: v?.text || '',
                        highlight: null,
                        fromReference: true
                    };
                }
            }
        } catch (_) { /* not a reference */ }

        try {
            const res = await fetch(`/api/search?q=${encodeURIComponent(q)}&limit=50`);
            if (res.ok) {
                const results = await res.json();
                searchVerses = results.verses || [];
            }
        } catch (err) {
            console.error('Insert scripture search failed', err);
            if (gen !== insertSearchGen) return;
            if (!refHit) {
                insertCount.textContent = 'Search failed';
                insertList.innerHTML = '<p class="collections-empty">Could not search scripture. Try again.</p>';
                return;
            }
        }

        if (gen !== insertSearchGen) return;

        const seen = new Set();
        const hits = [];
        if (refHit) {
            seen.add(refHit.id);
            hits.push(refHit);
        }
        for (const v of searchVerses) {
            if (seen.has(v.id)) continue;
            seen.add(v.id);
            hits.push(v);
        }

        insertCount.textContent =
            hits.length === 1 ? '1 matching verse' : `${hits.length} matching verses`;

        if (hits.length === 0) {
            insertList.innerHTML = '<p class="collections-empty">No verses found.</p>';
            return;
        }

        insertList.innerHTML = hits.map(v => {
            const ref = `${v.book} ${v.chapter}:${v.verse}`;
            const snippet = v.highlight || escapeHtml(v.text || '');
            const badge = v.fromReference
                ? '<span class="passage-insert-badge">Reference</span>'
                : '';
            return `
                <button type="button" class="search-result-item passage-insert-verse-hit" data-verse-id="${v.id}">
                    <div class="search-result-ref">${escapeHtml(ref)}${badge}</div>
                    <div class="search-result-text">${snippet || '&nbsp;'}</div>
                </button>`;
        }).join('');

        insertList.querySelectorAll('.passage-insert-verse-hit').forEach(item => {
            item.addEventListener('click', () => {
                openInsertExpand(parseInt(item.dataset.verseId, 10));
            });
        });
    }

    function renderInsertBrowse() {
        if (insertTab === 'passages') renderInsertPassages();
        else runInsertVerseSearch();
    }

    function onInsertSearchInput() {
        if (insertMode === 'expand') showInsertBrowse();
        if (insertTab === 'passages') {
            renderInsertPassages();
            return;
        }
        if (insertSearchTimer) clearTimeout(insertSearchTimer);
        insertSearchTimer = setTimeout(() => {
            insertSearchTimer = null;
            runInsertVerseSearch();
        }, 280);
    }

    async function openInsertExpand(verseId) {
        if (!Number.isFinite(verseId)) return;
        insertMode = 'expand';
        insertBrowse.hidden = true;
        insertExpand.hidden = false;
        insertTabs.hidden = true;
        insertSearch.hidden = true;
        insertChapters.innerHTML = '<div class="passage-picker-loading">Loading…</div>';
        insertConfirm.disabled = true;
        insertExpandCount.textContent = 'Loading…';

        let context;
        try {
            const res = await fetch(`/api/memorization/context/${verseId}`, { credentials: 'include' });
            if (!res.ok) throw new Error('context failed');
            context = await res.json();
        } catch (err) {
            console.error(err);
            insertChapters.innerHTML = '<div class="passage-picker-loading">Could not load surrounding verses.</div>';
            return;
        }

        const checkedIds = new Set([verseId]);
        renderInsertExpandChapters(context, checkedIds, verseId);
    }

    function renderInsertExpandChapters(context, checkedIds, anchorVerseId) {
        const sections = [context.prevChapter, context.currentChapter, context.nextChapter]
            .filter(Boolean);

        insertChapters.innerHTML = sections.map(ch => {
            const allIds = ch.verses.map(v => v.id);
            const headerLabel = `${ch.bookName} ${ch.chapter}`;
            const verseRows = ch.verses.map(v => `
                <label class="pp-verse-row">
                    <input type="checkbox" class="pp-verse-cb pi-verse-cb" data-verse-id="${v.id}"
                           ${checkedIds.has(v.id) ? 'checked' : ''}>
                    <span class="pp-verse-num">${v.verseNum}</span>
                    <span class="pp-verse-text">${escapeHtml(v.text)}</span>
                </label>`).join('');
            return `
                <div class="pp-chapter-section" data-all-ids="${allIds.join(',')}">
                    <div class="pp-chapter-header">
                        <span class="pp-chapter-label">${escapeHtml(headerLabel)}</span>
                        <label class="pp-select-all-label">
                            <input type="checkbox" class="pp-select-all-cb">
                            Select all
                        </label>
                    </div>
                    ${verseRows}
                </div>`;
        }).join('');

        updateInsertExpandSelection();

        const anchorCb = insertChapters.querySelector(`[data-verse-id="${anchorVerseId}"]`);
        if (anchorCb) anchorCb.closest('.pp-verse-row').scrollIntoView({ block: 'center' });

        insertChapters.querySelectorAll('.pi-verse-cb').forEach(cb => {
            cb.addEventListener('change', updateInsertExpandSelection);
        });
        insertChapters.querySelectorAll('.pp-select-all-cb').forEach(cb => {
            cb.addEventListener('change', () => {
                const section = cb.closest('.pp-chapter-section');
                section.querySelectorAll('.pi-verse-cb')
                    .forEach(v => { v.checked = cb.checked; });
                updateInsertExpandSelection();
            });
        });
    }

    function getInsertCheckedIds() {
        return [...insertChapters.querySelectorAll('.pi-verse-cb:checked')]
            .map(cb => parseInt(cb.dataset.verseId, 10))
            .filter(Number.isFinite)
            .sort((a, b) => a - b);
    }

    function updateInsertExpandSelection() {
        insertChapters.querySelectorAll('.pp-chapter-section').forEach(section => {
            const all = section.querySelectorAll('.pi-verse-cb');
            const chk = section.querySelectorAll('.pi-verse-cb:checked');
            const sa = section.querySelector('.pp-select-all-cb');
            if (!sa) return;
            sa.checked = chk.length === all.length && all.length > 0;
            sa.indeterminate = chk.length > 0 && chk.length < all.length;
        });

        const checked = getInsertCheckedIds();
        const count = checked.length;
        insertExpandCount.textContent =
            count === 0 ? '0 verses selected' :
            count === 1 ? '1 verse selected' :
            `${count} verses selected`;
        insertConfirm.disabled = count === 0 || count > INSERT_MAX_VERSES;
        if (count > INSERT_MAX_VERSES) {
            insertExpandCount.textContent = `Too many verses (max ${INSERT_MAX_VERSES})`;
        }
    }

    function confirmInsertExpand() {
        const checked = getInsertCheckedIds();
        if (!checked.length || checked.length > INSERT_MAX_VERSES) return;
        const naturalKey = buildNaturalKeyFromIds(checked);
        insertVTokenFromNaturalKey(naturalKey);
    }

    if (insertBtn) {
        insertBtn.addEventListener('click', () => {
            insertTab = 'verses';
            insertMode = 'browse';
            insertSearch.value = '';
            syncInsertTabs();
            showInsertBrowse();
            renderInsertBrowse();
            insertOverlay.hidden = false;
            insertSearch.focus();
        });
    }
    if (insertClose) {
        insertClose.addEventListener('click', closeInsertPicker);
        insertOverlay.addEventListener('click', e => {
            if (e.target === insertOverlay) closeInsertPicker();
        });
        insertSearch.addEventListener('input', onInsertSearchInput);
        if (insertTabs) {
            insertTabs.addEventListener('click', e => {
                const tab = e.target.closest('.passage-insert-tab');
                if (tab) setInsertTab(tab.dataset.tab);
            });
        }
        if (insertExpandBack) {
            insertExpandBack.addEventListener('click', () => {
                showInsertBrowse();
                renderInsertBrowse();
                insertSearch.focus();
            });
        }
        if (insertConfirm) {
            insertConfirm.addEventListener('click', confirmInsertExpand);
        }
        document.addEventListener('keydown', e => {
            if (e.key !== 'Escape' || insertOverlay.hidden) return;
            if (insertMode === 'expand') {
                showInsertBrowse();
                renderInsertBrowse();
                insertSearch.focus();
            } else {
                closeInsertPicker();
            }
        });
    }

    // Verse / range / passage / collection links inside a rendered sermon note
    viewBody.addEventListener('click', async e => {
        const rangeLink = e.target.closest('.note-range-link');
        if (rangeLink) {
            e.preventDefault();
            window.location.href = `/read/range?v=${encodeURIComponent(rangeLink.dataset.v)}`;
            return;
        }
        const passageLink = e.target.closest('.note-passage-link');
        if (passageLink) {
            e.preventDefault();
            window.location.href = `/read/passage/${passageLink.dataset.passageId}`;
            return;
        }
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
