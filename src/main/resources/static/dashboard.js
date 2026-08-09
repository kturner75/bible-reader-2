(async function () {
    'use strict';

    // ── Utilities ────────────────────────────────────────────────────────────

    function escapeHtml(str) {
        const d = document.createElement('div');
        d.appendChild(document.createTextNode(String(str)));
        return d.innerHTML;
    }

    /**
     * escapeHtml() serialises a text node, so it encodes & < > but leaves quotes
     * alone — safe between tags, unsafe inside an attribute, where a name like
     * `" onfocus="…` closes the attribute and injects a new one. Use this for any
     * value interpolated into a quoted attribute.
     */
    function escapeAttr(str) {
        return escapeHtml(str).replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    // Shared with the reader so a passage cannot be due in one place and not the
    // other — see date-utils.js for why this is one definition rather than a copy.
    const localIsoDate = window.KjvDate.localIsoDate;

    const TODAY = window.KjvDate.todayIso();

    function isDue(entry) {
        return window.KjvDate.isEntryDue(entry, TODAY);
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

    // Every <details> on this page remembers whether the reader left it open —
    // the auto-open rules below are first-visit defaults only. See view-prefs.js.
    const bindDisclosure = window.KjvViewPrefs.bindDisclosure;

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
        <a href="/notes" class="nav-link">Notes</a>
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
    let rhythmsData    = [];
    let booksData      = [];

    // The server derives "marked today" from a day boundary that has to agree with
    // the browser's weekday, or a lane marked just after local midnight resurfaces
    // as outstanding when the server rolls over.
    const TZ_HEADER = { 'X-Time-Zone': Intl.DateTimeFormat().resolvedOptions().timeZone || '' };
    const rhythmFetch = (url, opts = {}) => fetch(url, {
        ...opts,
        credentials: 'include',
        headers: { ...TZ_HEADER, ...(opts.headers || {}) },
    });
    try {
        const [queueRes, streakRes, globalRes, plansRes, heatmapRes, sermonNotesRes,
               rhythmsRes, booksRes] = await Promise.all([
            fetch('/api/memorization/queue',           { credentials: 'include' }),
            fetch('/api/memorization/streak',          { credentials: 'include' }),
            fetch('/api/memorization/global-passages', { credentials: 'include' }),
            fetch('/api/plans',                        { credentials: 'include' }),
            rhythmFetch('/api/activity/heatmap'),   // bucketed in the browser's zone
            fetch('/api/sermon-notes',                 { credentials: 'include' }),
            rhythmFetch('/api/rhythms'),
            fetch('/api/books'),
        ]);
        if (queueRes.ok)        allEntries     = await queueRes.json();
        if (streakRes.ok)       streakData     = await streakRes.json();
        if (globalRes.ok)       globalPassages = await globalRes.json();
        if (plansRes.ok)        plansData      = await plansRes.json();
        if (heatmapRes.ok)      heatmapData    = await heatmapRes.json();
        if (sermonNotesRes.ok)  sermonNotes    = await sermonNotesRes.json();
        if (rhythmsRes.ok)      rhythmsData    = await rhythmsRes.json();
        if (booksRes.ok)        booksData      = await booksRes.json();
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

    // Queue section — due passages lead; the rest are one click away.
    const queueSection = document.getElementById('queue-section');
    queueSection.hidden = false;

    function buildQueueRow(entry) {
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
        return row;
    }

    {
        const badge       = document.getElementById('queue-badge');
        const dueList     = document.getElementById('queue-list');
        const laterList   = document.getElementById('queue-later-list');
        const laterBox    = document.getElementById('queue-later-disclosure');
        const laterLabel  = document.getElementById('queue-later-summary');
        const caughtUpBox = document.getElementById('queue-caught-up');

        const laterEntries = allEntries.filter(e => !isDue(e));

        if (allEntries.length === 0) {
            document.getElementById('queue-empty').hidden = false;
        } else {
            // The badge counts what needs doing, not what exists.
            if (dueCount > 0) badge.textContent = dueCount;
            else badge.hidden = true;

            dueEntries.forEach(e => dueList.appendChild(buildQueueRow(e)));
            caughtUpBox.hidden = dueCount > 0;

            if (laterEntries.length > 0) {
                laterBox.hidden = false;
                laterLabel.textContent = `Scheduled for later (${laterEntries.length})`;
                laterEntries.forEach(e => laterList.appendChild(buildQueueRow(e)));
                // Collapsed until the reader says otherwise: a long queue of things
                // that are *not* actionable today is exactly what this disclosure
                // exists to keep from pushing the rest of the dashboard down. The
                // "All caught up" line above carries the state, so nothing is due
                // and nothing looks empty.
                bindDisclosure(laterBox, false);
            }
        }
    }

    // ── Featured Passages ──────────────────────────────────────────────────────

    function renderFeaturedPassages(passages) {
        const disclosure = document.getElementById('featured-disclosure');
        const summary    = document.getElementById('featured-summary');
        const list       = document.getElementById('featured-list');

        // A catalogue you exhaust: once every passage is queued there is nothing
        // left to offer, so the disclosure goes away entirely.
        const available = (passages || []).filter(p => !p.alreadyQueued);
        if (available.length === 0) {
            disclosure.hidden = true;
            return;
        }

        disclosure.hidden = false;
        let remaining = available.length;
        const setSummary = () => {
            summary.textContent = `Add a featured passage (${remaining})`;
        };
        setSummary();

        // Expanded only when the queue is empty — the one moment this is the most
        // useful thing on the page rather than a distraction.
        bindDisclosure(disclosure, allEntries.length === 0);

        available.forEach(p => {
            const ref = p.fromVerseRef === p.toVerseRef
                ? p.fromVerseRef
                : `${p.fromVerseRef} – ${p.toVerseRef}`;

            const row = document.createElement('div');
            row.className = 'featured-row';
            row.innerHTML = `
                <span class="featured-title">${escapeHtml(p.title)}</span>
                <span class="featured-ref">${escapeHtml(ref)}</span>
                <button class="featured-add-btn">+ Add</button>
            `;

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
                    if (!res.ok) throw new Error('add failed');
                    this.textContent = '✓ Added';
                    this.classList.add('is-added');
                    remaining -= 1;
                    if (remaining === 0) disclosure.hidden = true;
                    else setSummary();
                } catch (_) {
                    this.disabled = false;
                    this.textContent = '+ Add';
                }
            });

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
            // "All done" was previously terminal — no way to re-read or step away.
            actionsHtml = `
                <button class="link-btn plan-leave-btn">Leave</button>
                <button class="btn-secondary plan-restart-btn">Start over</button>
            `;
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
                <button class="link-btn plan-leave-btn">Leave</button>
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

    /** Keep plansData current after an enroll/complete so the Today card can re-derive. */
    function syncPlanData(updated) {
        const idx = plansData.findIndex(p => p.id === updated.id);
        if (idx >= 0) plansData[idx] = updated;
        renderTodayReadingCard();
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
                        syncPlanData(updated);
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

        // Leaving keeps completion history — the confirm text says so, because
        // "unenroll" reads like it might erase what you have already read.
        const leaveBtn = row.querySelector('.plan-leave-btn');
        if (leaveBtn) {
            leaveBtn.addEventListener('click', async function () {
                const ok = confirm(
                    `Leave "${plan.title}"?\n\n` +
                    `Your reading position is reset, but the days you have already ` +
                    `completed stay in your activity history. You can enroll again later.`);
                if (!ok) return;
                this.disabled = true;
                try {
                    const res = await fetch(`/api/plans/${plan.id}/enroll`, {
                        method: 'DELETE', credentials: 'include',
                    });
                    if (!res.ok) throw new Error('unenroll failed');
                    // 204 has no body — rebuild the unenrolled row from what we know.
                    const updated = { ...plan, enrolled: false, currentDay: null,
                                      todayDay: null, streakDays: null, enrolledAt: null };
                    const newRow = buildPlanRow(updated);
                    attachPlanListeners(newRow, updated);
                    row.replaceWith(newRow);
                    syncPlanData(updated);
                } catch (_) {
                    this.disabled = false;
                }
            });
        }

        const restartBtn = row.querySelector('.plan-restart-btn');
        if (restartBtn) {
            restartBtn.addEventListener('click', async function () {
                this.disabled = true;
                this.textContent = 'Resetting…';
                try {
                    const res = await fetch(`/api/plans/${plan.id}/restart`, {
                        method: 'POST', credentials: 'include',
                    });
                    if (!res.ok) throw new Error('restart failed');
                    const updated = await res.json();
                    const newRow = buildPlanRow(updated);
                    attachPlanListeners(newRow, updated);
                    row.replaceWith(newRow);
                    syncPlanData(updated);
                } catch (_) {
                    this.disabled = false;
                    this.textContent = 'Start over';
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
                        syncPlanData(updated);
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
        // The "Today's Reading" card spans plans *and* rhythms — see
        // renderTodayReadingCard(), which runs once both data sets are loaded.
    }

    renderPlans(plansData);

    // ── Reading Rhythms ────────────────────────────────────────────────────────
    //
    // A rhythm is a set of lanes; each lane walks an ordered book list at the
    // reader's own pace. A lane's weekday decides only which lane leads the
    // section today — every lane stays openable and markable on any day.

    const DAY_NAMES = ['', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];
    const ISO_TODAY = ((new Date().getDay() + 6) % 7) + 1;   // JS Sun=0 → ISO Mon=1…Sun=7

    // Book id ranges per category, matching the Library's grouping.
    const BOOK_CATEGORIES = [
        ['Pentateuch',        1,  5],
        ['Historical',        6,  17],
        ['Wisdom & Poetry',   18, 22],
        ['Major Prophets',    23, 27],
        ['Minor Prophets',    28, 39],
        ['Gospels',           40, 43],
        ['Acts',              44, 44],
        ['Pauline Epistles',  45, 57],
        ['General Epistles',  58, 65],
        ['Revelation',        66, 66],
    ];

    // The user's spreadsheet rhythm, offered as a one-click starting point.
    // Every lane is editable afterwards; nothing here is stored server-side.
    const WEEKLY_TEMPLATE = [
        { name: 'Sunday',    dayOfWeek: 7, bookIds: [40, 41, 42, 43, 44, 45] },
        { name: 'Monday',    dayOfWeek: 1, bookIds: [6, 7, 8, 15, 16, 17, 26, 27] },
        { name: 'Tuesday',   dayOfWeek: 2, bookIds: [9, 10, 11, 12, 13, 14] },
        { name: 'Wednesday', dayOfWeek: 3, bookIds: range(46, 66) },
        { name: 'Thursday',  dayOfWeek: 4, bookIds: [23, 24, 25].concat(range(28, 39)) },
        { name: 'Friday',    dayOfWeek: 5, bookIds: [18, 19, 20, 21, 22] },
        { name: 'Saturday',  dayOfWeek: 6, bookIds: range(1, 5) },
    ];

    function range(from, to) {
        return Array.from({ length: to - from + 1 }, (_, i) => from + i);
    }

    const booksById = new Map(booksData.map(b => [b.id, b]));
    const bookName  = id => booksById.get(id)?.name || `Book ${id}`;

    const rhythmTodayEl      = document.getElementById('rhythm-today');
    const rhythmAllEl        = document.getElementById('rhythm-all');
    const rhythmAllDetails   = document.getElementById('rhythm-all-disclosure');
    const rhythmAllSummary   = document.getElementById('rhythm-all-summary');
    const rhythmEmptyEl      = document.getElementById('rhythm-empty');
    const rhythmSubEl        = document.getElementById('rhythms-sub');

    function laneProgressLabel(lane) {
        if (!lane.chaptersTotal) return 'No books yet';
        const remaining = lane.chaptersTotal - lane.chaptersRead;
        const current   = lane.books.find(b => b.bookId === lane.cursorBookId);
        const bookPart  = current
            ? `${escapeHtml(current.bookName)} — ${current.chaptersRead} of ${current.chaptersTotal} · `
            : '';
        return `${bookPart}${lane.chaptersRead} of ${lane.chaptersTotal} chapters · ${remaining} to go`;
    }

    /**
     * The day chip beside a lane's name. Returns '' when the lane is simply named
     * after its day — "Sunday · Sunday" is noise, and naming lanes for weekdays is
     * the common case.
     */
    function laneDayLabel(lane) {
        if (!lane.dayOfWeek) return 'Any day';
        const day = DAY_NAMES[lane.dayOfWeek];
        return lane.name.trim().toLowerCase() === day.toLowerCase() ? '' : day;
    }

    /** One lane, rendered either as today's lead card or as a row in the full list. */
    function buildLaneEl(lane, rhythm, isToday) {
        const el = document.createElement('div');
        el.className = isToday ? 'rhythm-card' : 'rhythm-row';
        el.dataset.laneId = lane.id;

        const pct = lane.chaptersTotal
            ? Math.round(lane.chaptersRead / lane.chaptersTotal * 100)
            : 0;

        // Jumping the cursor anywhere (e.g. transcribing an existing paper plan) lives
        // in the builder — surface it on every lane so it is not hidden behind Edit.
        const setPosition = `<button class="rhythm-link-btn rhythm-position-btn">Set position</button>`;

        let headline, actions;
        if (lane.complete) {
            headline = '<span class="rhythm-complete">Complete ✓</span>';
            actions  = `${setPosition}
                        <button class="btn-secondary rhythm-restart-btn">Restart</button>`;
        } else if (lane.nextReading) {
            const n = lane.nextReading;
            const href = `/read?vid=${n.firstVerseId}&lane=${lane.id}`;
            headline = `<a class="rhythm-next" href="${href}">Continue with
                        ${escapeHtml(n.bookName)} ${n.chapter}</a>`;
            actions  = `${setPosition}
                        <a class="btn-secondary rhythm-open-btn" href="${href}">Open →</a>
                        <button class="btn-primary rhythm-mark-btn"
                                title="Mark ${escapeAttr(n.bookName)} ${n.chapter} as read">
                            Mark chapter read
                        </button>`;
        } else {
            headline = '<span class="rhythm-next-empty">No books in this lane</span>';
            actions  = '';
        }

        const dayLabel = laneDayLabel(lane);
        el.innerHTML = `
            <div class="rhythm-info">
                <div class="rhythm-lane-head">
                    <span class="rhythm-lane-name">${escapeHtml(lane.name)}</span>
                    ${dayLabel ? `<span class="rhythm-lane-day">${escapeHtml(dayLabel)}</span>` : ''}
                    ${/* Rows sit under a rhythm header already; only today's card needs naming. */ ''}
                    ${isToday ? `<span class="rhythm-lane-of">${escapeHtml(rhythm.title)}</span>` : ''}
                </div>
                ${headline}
                <div class="rhythm-progress">${laneProgressLabel(lane)}</div>
                <div class="rhythm-track"><div class="rhythm-fill" style="width:${pct}%"></div></div>
            </div>
            <div class="rhythm-actions">${actions}</div>
        `;

        attachLaneListeners(el, lane, rhythm, isToday);
        return el;
    }

    /**
     * A lane scheduled for today is rendered twice — once as today's lead card and
     * again inside the All lanes disclosure. Replacing only the clicked element
     * would leave the other copy showing stale progress and holding a handler
     * closed over the old nextReading, so clicking it would re-record the same
     * chapter and log a second activity event. Re-render the whole section instead.
     */
    function refreshLane(updatedLane, rhythm) {
        const idx = rhythm.lanes.findIndex(l => l.id === updatedLane.id);
        if (idx >= 0) rhythm.lanes[idx] = updatedLane;
        const wasOpen = rhythmAllDetails.open;
        renderRhythms();
        // bindDisclosure already restores a remembered choice; this covers the case
        // where localStorage is unavailable and nothing was remembered.
        rhythmAllDetails.open = wasOpen;
        // A mark may have satisfied today's lane — let the card settle.
        renderTodayReadingCard();
    }

    /**
     * Lanes with a mutation in flight.
     *
     * A lane scheduled for today is rendered twice — today's card and the All lanes
     * row — each with its own buttons. Disabling the clicked one leaves the other
     * live until the response lands and refreshLane() re-renders, so during a slow
     * request the second copy can submit the same chapter again. The progress log is
     * append-only, so that double-counts activity. Guard the lane, not the button.
     */
    const laneMutationsInFlight = new Set();

    function attachLaneListeners(el, lane, rhythm, isToday) {
        const markBtn = el.querySelector('.rhythm-mark-btn');
        if (markBtn) {
            markBtn.addEventListener('click', async () => {
                if (laneMutationsInFlight.has(lane.id)) return;
                laneMutationsInFlight.add(lane.id);
                markBtn.disabled = true;
                markBtn.textContent = 'Marking…';
                try {
                    const res = await rhythmFetch(`/api/rhythms/lanes/${lane.id}/progress`, {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({
                            bookId: lane.nextReading.bookId,
                            throughChapter: lane.nextReading.chapter,
                        }),
                    });
                    if (!res.ok) throw new Error('mark failed');
                    refreshLane(await res.json(), rhythm);
                } catch (_) {
                    markBtn.disabled = false;
                    markBtn.textContent = 'Try again';
                } finally {
                    laneMutationsInFlight.delete(lane.id);
                }
            });
        }

        const restartBtn = el.querySelector('.rhythm-restart-btn');
        if (restartBtn) {
            restartBtn.addEventListener('click', async () => {
                if (laneMutationsInFlight.has(lane.id)) return;
                laneMutationsInFlight.add(lane.id);
                restartBtn.disabled = true;
                try {
                    const res = await rhythmFetch(`/api/rhythms/lanes/${lane.id}/restart`, {
                        method: 'POST',
                    });
                    if (!res.ok) throw new Error('restart failed');
                    refreshLane(await res.json(), rhythm);
                } catch (_) {
                    restartBtn.disabled = false;
                } finally {
                    laneMutationsInFlight.delete(lane.id);
                }
            });
        }

        const positionBtn = el.querySelector('.rhythm-position-btn');
        if (positionBtn) {
            positionBtn.addEventListener('click', () => openRhythmBuilder(rhythm, lane.id));
        }
    }

    /** Rhythm title bar above its lanes — carries the Edit and Delete affordances. */
    function buildRhythmHeader(rhythm) {
        const el = document.createElement('div');
        el.className = 'rhythm-group-head';
        el.innerHTML = `
            <span class="rhythm-group-title">${escapeHtml(rhythm.title)}</span>
            <span class="rhythm-group-count">${rhythm.lanes.length} lane${rhythm.lanes.length === 1 ? '' : 's'}</span>
            <button class="btn-secondary rhythm-edit-btn">Edit</button>
            <button class="rhythm-link-btn rhythm-delete-btn">Delete</button>
        `;
        el.querySelector('.rhythm-edit-btn')
          .addEventListener('click', () => openRhythmBuilder(rhythm));
        el.querySelector('.rhythm-delete-btn')
          .addEventListener('click', () => deleteRhythm(rhythm));
        return el;
    }

    async function deleteRhythm(rhythm) {
        if (!confirm(`Delete "${rhythm.title}" and its reading progress? This cannot be undone.`)) {
            return;
        }
        try {
            const res = await fetch(`/api/rhythms/${rhythm.id}`, {
                method: 'DELETE', credentials: 'include',
            });
            if (!res.ok) throw new Error('delete failed');
            rhythmsData = rhythmsData.filter(r => r.id !== rhythm.id);
            renderRhythms();
            renderTodayReadingCard();
        } catch (_) {
            alert('Could not delete that rhythm. Please try again.');
        }
    }

    function renderRhythms() {
        rhythmTodayEl.innerHTML = '';
        rhythmAllEl.innerHTML   = '';

        if (!rhythmsData.length) {
            rhythmEmptyEl.hidden     = false;
            rhythmSubEl.hidden       = true;
            rhythmAllDetails.hidden  = true;
            return;
        }
        rhythmEmptyEl.hidden    = true;
        rhythmSubEl.hidden      = false;
        rhythmAllDetails.hidden = false;

        // Today's lanes lead. Weekday-less lanes never auto-surface — they are
        // deliberate choices, so they live in the full list below.
        const todayPairs = [];
        rhythmsData.forEach(rhythm => {
            rhythm.lanes.forEach(lane => {
                if (lane.dayOfWeek === ISO_TODAY) todayPairs.push({ lane, rhythm });
            });
        });

        if (todayPairs.length) {
            const heading = document.createElement('div');
            heading.className = 'rhythm-group-label';
            heading.textContent = `Today · ${DAY_NAMES[ISO_TODAY]}`;
            rhythmTodayEl.appendChild(heading);
            todayPairs.forEach(({ lane, rhythm }) => {
                rhythmTodayEl.appendChild(buildLaneEl(lane, rhythm, true));
            });
        } else {
            const none = document.createElement('div');
            none.className = 'rhythm-group-label rhythm-none-today';
            none.textContent = 'Nothing scheduled today — pick any lane below';
            rhythmTodayEl.appendChild(none);
        }

        // Every lane, always available regardless of what day it is — one click away
        // behind the disclosure. Grouped under a per-rhythm header so Edit and Delete
        // have somewhere visible to live.
        const laneCount = rhythmsData.reduce((n, r) => n + r.lanes.length, 0);
        rhythmAllSummary.textContent = `All lanes (${laneCount})`;
        // Open when today has nothing — otherwise the section would look empty.
        bindDisclosure(rhythmAllDetails, todayPairs.length === 0);

        rhythmsData.forEach(rhythm => {
            rhythmAllEl.appendChild(buildRhythmHeader(rhythm));
            const group = document.createElement('div');
            group.className = 'rhythm-group-lanes';
            rhythm.lanes.forEach(lane => {
                group.appendChild(buildLaneEl(lane, rhythm, false));
            });
            rhythmAllEl.appendChild(group);
        });
    }

    // ── Rhythm Builder ─────────────────────────────────────────────────────────

    const rbOverlay  = document.getElementById('rhythm-builder-overlay');
    const rbHeading  = document.getElementById('rhythm-builder-heading');
    const rbTitle    = document.getElementById('rhythm-title-input');
    const rbLanesEl  = document.getElementById('rhythm-lanes');
    const rbError    = document.getElementById('rhythm-builder-error');
    const rbDeleteBtn = document.getElementById('rhythm-delete-btn');

    // Draft state: { id, title, lanes: [{ id, name, dayOfWeek, bookIds, cursorBookId, cursorChapter }] }
    let draft = null;

    function newLaneDraft(seed = {}) {
        return {
            id: seed.id ?? null,
            name: seed.name ?? '',
            dayOfWeek: seed.dayOfWeek ?? null,
            bookIds: [...(seed.bookIds ?? [])],
            cursorBookId: seed.cursorBookId ?? null,
            cursorChapter: seed.cursorChapter ?? 0,
        };
    }

    /**
     * @param focusLaneId when given, scrolls to that lane and highlights its position
     *                    control — the path taken by a lane's "Set position" button.
     */
    function openRhythmBuilder(rhythm = null, focusLaneId = null) {
        draft = rhythm
            ? {
                id: rhythm.id,
                title: rhythm.title,
                lanes: rhythm.lanes.map(l => newLaneDraft({
                    id: l.id, name: l.name, dayOfWeek: l.dayOfWeek,
                    bookIds: l.books.map(b => b.bookId),
                    cursorBookId: l.cursorBookId, cursorChapter: l.cursorChapter,
                })),
              }
            : { id: null, title: '', lanes: [newLaneDraft({ name: 'Lane 1' })] };

        rbHeading.textContent = rhythm ? 'Edit Rhythm' : 'New Rhythm';
        rbTitle.value = draft.title;
        rbDeleteBtn.hidden = !rhythm;
        rbError.hidden = true;
        renderDraftLanes();
        rbOverlay.hidden = false;

        if (focusLaneId != null) {
            const index = draft.lanes.findIndex(l => l.id === focusLaneId);
            const card  = index >= 0 ? rbLanesEl.children[index] : null;
            if (card) {
                card.classList.add('rb-lane-focused');
                card.scrollIntoView({ block: 'center' });
                card.querySelector('.rb-position-chapter')?.focus();
                return;
            }
        }
        rbTitle.focus();
    }

    function closeRhythmBuilder() {
        rbOverlay.hidden = true;
        draft = null;
    }

    function renderDraftLanes() {
        rbLanesEl.innerHTML = '';
        draft.lanes.forEach((lane, index) => rbLanesEl.appendChild(buildLaneEditor(lane, index)));
    }

    function buildLaneEditor(lane, index) {
        const card = document.createElement('div');
        card.className = 'rb-lane';

        const dayOptions = ['<option value="">Any day</option>']
            .concat(DAY_NAMES.slice(1).map((name, i) =>
                `<option value="${i + 1}" ${lane.dayOfWeek === i + 1 ? 'selected' : ''}>${name}</option>`))
            .join('');

        card.innerHTML = `
            <div class="rb-lane-head">
                <input type="text" class="rb-lane-name" maxlength="60"
                       placeholder="Lane name">
                <select class="rb-lane-day" title="Which day should lead with this lane?">
                    ${dayOptions}
                </select>
                <button class="rb-icon rb-lane-up"     title="Move up"      ${index === 0 ? 'disabled' : ''}>↑</button>
                <button class="rb-icon rb-lane-down"   title="Move down"    ${index === draft.lanes.length - 1 ? 'disabled' : ''}>↓</button>
                <button class="rb-icon rb-lane-remove" title="Remove lane">✕</button>
            </div>
            <div class="rb-lane-books"></div>
            <div class="rb-lane-position"></div>
            <details class="rb-picker">
                <summary>Choose books</summary>
                <div class="rb-picker-body"></div>
            </details>
        `;

        // Assigned as a property, never interpolated into the markup above: a lane
        // name is free text, and a quote in an attribute would inject markup.
        const nameInput = card.querySelector('.rb-lane-name');
        nameInput.value = lane.name;
        nameInput.addEventListener('input', e => {
            lane.name = e.target.value;
        });
        card.querySelector('.rb-lane-day').addEventListener('change', e => {
            lane.dayOfWeek = e.target.value ? parseInt(e.target.value, 10) : null;
        });
        card.querySelector('.rb-lane-up').addEventListener('click', () => moveLane(index, -1));
        card.querySelector('.rb-lane-down').addEventListener('click', () => moveLane(index, 1));
        card.querySelector('.rb-lane-remove').addEventListener('click', () => {
            draft.lanes.splice(index, 1);
            if (!draft.lanes.length) draft.lanes.push(newLaneDraft({ name: 'Lane 1' }));
            renderDraftLanes();
        });

        renderLaneChips(card, lane);
        renderLanePosition(card, lane);
        renderBookPicker(card, lane);
        return card;
    }

    function moveLane(index, delta) {
        const target = index + delta;
        if (target < 0 || target >= draft.lanes.length) return;
        const [lane] = draft.lanes.splice(index, 1);
        draft.lanes.splice(target, 0, lane);
        renderDraftLanes();
    }

    /** Selected books as ordered chips — order is the reading order. */
    function renderLaneChips(card, lane) {
        const wrap = card.querySelector('.rb-lane-books');
        wrap.innerHTML = '';
        if (!lane.bookIds.length) {
            wrap.innerHTML = '<span class="rb-empty">No books chosen yet</span>';
            return;
        }
        lane.bookIds.forEach((bookId, i) => {
            const chip = document.createElement('span');
            chip.className = 'rb-chip';
            chip.innerHTML = `
                <span class="rb-chip-name">${escapeHtml(bookName(bookId))}</span>
                <button class="rb-chip-btn rb-chip-left"  title="Move earlier" ${i === 0 ? 'disabled' : ''}>‹</button>
                <button class="rb-chip-btn rb-chip-right" title="Move later"   ${i === lane.bookIds.length - 1 ? 'disabled' : ''}>›</button>
                <button class="rb-chip-btn rb-chip-x"     title="Remove">✕</button>
            `;
            chip.querySelector('.rb-chip-left').addEventListener('click',  () => moveBook(card, lane, i, -1));
            chip.querySelector('.rb-chip-right').addEventListener('click', () => moveBook(card, lane, i, 1));
            chip.querySelector('.rb-chip-x').addEventListener('click', () => {
                lane.bookIds.splice(i, 1);
                if (lane.cursorBookId === bookId) { lane.cursorBookId = null; lane.cursorChapter = 0; }
                refreshLaneEditor(card, lane);
            });
            wrap.appendChild(chip);
        });
    }

    function moveBook(card, lane, index, delta) {
        const target = index + delta;
        if (target < 0 || target >= lane.bookIds.length) return;
        const [bookId] = lane.bookIds.splice(index, 1);
        lane.bookIds.splice(target, 0, bookId);
        refreshLaneEditor(card, lane);
    }

    /**
     * "Set position" — where the reader currently is in this lane. Lets someone
     * transcribe an existing paper or spreadsheet plan instead of starting over.
     */
    function renderLanePosition(card, lane) {
        const wrap = card.querySelector('.rb-lane-position');
        if (!lane.bookIds.length) { wrap.innerHTML = ''; return; }

        const bookOptions = ['<option value="">Not started</option>'].concat(
            lane.bookIds.map(id =>
                `<option value="${id}" ${lane.cursorBookId === id ? 'selected' : ''}>${escapeHtml(bookName(id))}</option>`)
        ).join('');
        const maxChapter = lane.cursorBookId ? (booksById.get(lane.cursorBookId)?.chapters || 1) : 1;

        wrap.innerHTML = `
            <span class="rb-position-label">Read through</span>
            <select class="rb-position-book">${bookOptions}</select>
            <input type="number" class="rb-position-chapter" min="0" max="${maxChapter}"
                   value="${lane.cursorChapter || 0}" ${lane.cursorBookId ? '' : 'disabled'}
                   title="Chapters finished in this book">
            <span class="rb-position-hint">${lane.cursorBookId ? `of ${maxChapter}` : ''}</span>
        `;

        wrap.querySelector('.rb-position-book').addEventListener('change', e => {
            lane.cursorBookId = e.target.value ? parseInt(e.target.value, 10) : null;
            lane.cursorChapter = lane.cursorBookId ? 1 : 0;
            refreshLaneEditor(card, lane);
        });
        wrap.querySelector('.rb-position-chapter').addEventListener('change', e => {
            const value = parseInt(e.target.value, 10);
            lane.cursorChapter = Number.isFinite(value) ? Math.max(0, Math.min(value, maxChapter)) : 0;
            e.target.value = lane.cursorChapter;
        });
    }

    /** Category-grouped checkboxes; checking inserts in canonical order. */
    function renderBookPicker(card, lane) {
        const body = card.querySelector('.rb-picker-body');
        body.innerHTML = BOOK_CATEGORIES.map(([label, from, to]) => {
            const boxes = range(from, to)
                .filter(id => booksById.has(id))
                .map(id => `
                    <label class="rb-book">
                        <input type="checkbox" value="${id}" ${lane.bookIds.includes(id) ? 'checked' : ''}>
                        <span>${escapeHtml(bookName(id))}</span>
                    </label>`)
                .join('');
            return `
                <div class="rb-category">
                    <div class="rb-category-head">
                        <span class="rb-category-name">${escapeHtml(label)}</span>
                        <button class="rb-category-all" data-from="${from}" data-to="${to}">Add all</button>
                    </div>
                    <div class="rb-category-books">${boxes}</div>
                </div>`;
        }).join('');

        body.querySelectorAll('input[type="checkbox"]').forEach(box => {
            box.addEventListener('change', () => {
                const id = parseInt(box.value, 10);
                if (box.checked) addBook(lane, id);
                else {
                    lane.bookIds = lane.bookIds.filter(b => b !== id);
                    if (lane.cursorBookId === id) { lane.cursorBookId = null; lane.cursorChapter = 0; }
                }
                refreshLaneEditor(card, lane);
            });
        });

        body.querySelectorAll('.rb-category-all').forEach(btn => {
            btn.addEventListener('click', () => {
                range(parseInt(btn.dataset.from, 10), parseInt(btn.dataset.to, 10))
                    .filter(id => booksById.has(id))
                    .forEach(id => addBook(lane, id));
                refreshLaneEditor(card, lane);
            });
        });
    }

    /** Insert in canonical order — the common case, and reorderable afterwards. */
    function addBook(lane, bookId) {
        if (lane.bookIds.includes(bookId)) return;
        const at = lane.bookIds.findIndex(id => id > bookId);
        if (at < 0) lane.bookIds.push(bookId);
        else lane.bookIds.splice(at, 0, bookId);
    }

    /** Re-render one lane's sub-panels, leaving the picker's open/closed state alone. */
    function refreshLaneEditor(card, lane) {
        renderLaneChips(card, lane);
        renderLanePosition(card, lane);
        renderBookPicker(card, lane);
    }

    function draftToRequest() {
        return {
            title: rbTitle.value.trim(),
            lanes: draft.lanes.map(lane => ({
                id: lane.id,
                name: lane.name.trim(),
                dayOfWeek: lane.dayOfWeek,
                bookIds: lane.bookIds,
                // Only send a cursor when one is set; null leaves the server's alone.
                cursorBookId: lane.cursorBookId,
                cursorChapter: lane.cursorBookId ? lane.cursorChapter : null,
                // The draft always carries the lane's full intended position, so no
                // cursor here means "not started" — say so explicitly, or the server
                // reads it as "untouched" and restores the old progress.
                clearCursor: lane.cursorBookId === null,
            })),
        };
    }

    async function saveRhythm() {
        const payload = draftToRequest();
        if (!payload.title) return showBuilderError('Give the rhythm a title.');
        if (payload.lanes.some(l => !l.name)) return showBuilderError('Every lane needs a name.');
        if (payload.lanes.some(l => !l.bookIds.length)) {
            return showBuilderError('Every lane needs at least one book.');
        }

        const saveBtn = document.getElementById('rhythm-save-btn');
        saveBtn.disabled = true;
        saveBtn.textContent = 'Saving…';
        try {
            const res = await rhythmFetch(draft.id ? `/api/rhythms/${draft.id}` : '/api/rhythms', {
                method: draft.id ? 'PUT' : 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload),
            });
            if (!res.ok) {
                const body = await res.json().catch(() => ({}));
                throw new Error(body.message || 'Could not save this rhythm.');
            }
            const saved = await res.json();
            const idx = rhythmsData.findIndex(r => r.id === saved.id);
            if (idx >= 0) rhythmsData[idx] = saved;
            else rhythmsData.push(saved);
            closeRhythmBuilder();
            renderRhythms();
            renderTodayReadingCard();
        } catch (err) {
            showBuilderError(err.message);
        } finally {
            saveBtn.disabled = false;
            saveBtn.textContent = 'Save';
        }
    }

    function showBuilderError(message) {
        rbError.textContent = message;
        rbError.hidden = false;
    }

    document.getElementById('rhythm-new-btn').addEventListener('click', () => openRhythmBuilder());
    document.getElementById('rhythm-empty-btn').addEventListener('click', () => openRhythmBuilder());
    document.getElementById('rhythm-builder-close').addEventListener('click', closeRhythmBuilder);
    document.getElementById('rhythm-cancel-btn').addEventListener('click', closeRhythmBuilder);
    document.getElementById('rhythm-save-btn').addEventListener('click', saveRhythm);

    document.getElementById('rhythm-add-lane-btn').addEventListener('click', () => {
        draft.lanes.push(newLaneDraft({ name: `Lane ${draft.lanes.length + 1}` }));
        renderDraftLanes();
    });

    /**
     * Applies the weekly template to the draft.
     *
     * Template entries carry no lane id, so applying them verbatim to an existing
     * rhythm would make every lane look new: applyLanes would orphan-remove the old
     * rows and the FK cascade would take their progress log with them — cursors and
     * activity history gone, with no warning. So reuse the id of whichever existing
     * lane the template entry corresponds to (same weekday, else same name) and carry
     * its cursor across. Anything genuinely left out is named in a confirm.
     */
    function applyWeeklyTemplate() {
        const byDay  = new Map();
        const byName = new Map();
        draft.lanes.forEach(l => {
            if (l.id == null) return;
            if (l.dayOfWeek != null && !byDay.has(l.dayOfWeek)) byDay.set(l.dayOfWeek, l);
            const key = l.name.trim().toLowerCase();
            if (!byName.has(key)) byName.set(key, l);
        });

        // One existing lane can satisfy at most one template entry. A lane named
        // "Sunday" but scheduled for Monday matches Monday by weekday and Sunday by
        // name; without this check both entries would carry the same lane id, and
        // applyLanes would mutate that one entity twice — the second entry winning
        // and a template lane vanishing.
        const reused = new Set();
        const claim = (candidate) =>
            candidate && !reused.has(candidate.id) ? candidate : null;

        const lanes = WEEKLY_TEMPLATE.map(t => {
            const prior = claim(byDay.get(t.dayOfWeek)) || claim(byName.get(t.name.toLowerCase()));
            if (prior) reused.add(prior.id);
            return newLaneDraft({
                id: prior ? prior.id : null,
                name: t.name,
                dayOfWeek: t.dayOfWeek,
                bookIds: t.bookIds,
                // Carry progress across. If the cursor's book is not in the template's
                // list the server resets that lane, which is the honest outcome.
                cursorBookId: prior ? prior.cursorBookId : null,
                cursorChapter: prior ? prior.cursorChapter : 0,
            });
        });

        const dropped = draft.lanes.filter(l => l.id != null && !reused.has(l.id));
        if (dropped.length) {
            const names = dropped.map(l => l.name).join(', ');
            const ok = confirm(
                `The weekly template has no place for ${dropped.length} of your lanes ` +
                `(${names}).\n\nApplying it will delete them and their reading progress. ` +
                `Lanes that match a template day keep theirs.\n\nContinue?`);
            if (!ok) return;
        }

        if (!rbTitle.value.trim()) rbTitle.value = 'Weekly Rhythm';
        draft.lanes = lanes;
        renderDraftLanes();
    }

    document.getElementById('rhythm-template-btn').addEventListener('click', applyWeeklyTemplate);

    rbDeleteBtn.addEventListener('click', async () => {
        if (!draft?.id) return;
        if (!confirm(`Delete "${draft.title}" and its reading progress?`)) return;
        try {
            const res = await fetch(`/api/rhythms/${draft.id}`, {
                method: 'DELETE', credentials: 'include',
            });
            if (!res.ok) throw new Error('Could not delete this rhythm.');
            rhythmsData = rhythmsData.filter(r => r.id !== draft.id);
            closeRhythmBuilder();
            renderRhythms();
            renderTodayReadingCard();
        } catch (err) {
            showBuilderError(err.message);
        }
    });

    rbOverlay.addEventListener('click', e => {
        if (e.target === rbOverlay) closeRhythmBuilder();
    });
    document.addEventListener('keydown', e => {
        if (e.key === 'Escape' && !rbOverlay.hidden) closeRhythmBuilder();
    });

    renderRhythms();

    // ── Today's Reading card (plans + rhythms) ─────────────────────────────────
    //
    // Spans both concepts. Ordering is by consequence: a plan day has a deadline
    // behind it, so falling behind costs something; a rhythm lane never does.
    // With more than one outstanding item the card shows a count and names the
    // first two, mirroring how "Due for Review" handles a multi-item queue.

    function outstandingTodayReadings() {
        const items = [];

        // Enrolled, unfinished plans with a day to read.
        plansData.forEach(plan => {
            if (!plan.enrolled || plan.currentDay > plan.totalDays || !plan.todayDay) return;
            items.push({
                name: plan.title,
                ref:  plan.todayDay.label,
                sub:  `Day ${plan.currentDay} of ${plan.totalDays} · ${plan.title}`,
                href: `/read?vid=${plan.todayDay.fromVerseId}`,
                section: 'plans-section',
            });
        });

        // Lanes scheduled for today that have not been marked yet. A lane with no
        // weekday never appears here — same rule as the Rhythms section.
        rhythmsData.forEach(rhythm => {
            rhythm.lanes.forEach(lane => {
                if (lane.dayOfWeek !== ISO_TODAY) return;
                if (lane.complete || lane.markedToday || !lane.nextReading) return;
                const n = lane.nextReading;
                items.push({
                    name: lane.name,
                    ref:  `${n.bookName} ${n.chapter}`,
                    sub:  `${lane.name} · ${rhythm.title}`,
                    href: `/read?vid=${n.firstVerseId}&lane=${lane.id}`,
                    section: 'rhythms-section',
                });
            });
        });

        return items;
    }

    function renderTodayReadingCard() {
        const card   = document.getElementById('today-reading-card');
        const refEl  = document.getElementById('today-reading-ref');
        const subEl  = document.getElementById('today-reading-sub');
        const linkEl = document.getElementById('today-reading-link');

        const items = outstandingTodayReadings();

        if (items.length === 0) {
            card.hidden = true;
            return;
        }

        card.hidden = false;

        if (items.length === 1) {
            // Exactly one thing to read — name it, and open it directly.
            const only = items[0];
            refEl.textContent  = only.ref;
            subEl.textContent  = only.sub;
            linkEl.href        = only.href;
            linkEl.textContent = 'Open →';
            linkEl.onclick     = null;
            return;
        }

        // Several outstanding: count leads, first two named, the rest summarised.
        const named = items.slice(0, 2).map(i => `${i.name} · ${i.ref}`).join('  ·  ');
        const extra = items.length > 2 ? `  +${items.length - 2} more` : '';
        refEl.textContent  = `${items.length} readings`;
        subEl.textContent  = named + extra;
        linkEl.href        = `#${items[0].section}`;
        linkEl.textContent = 'See all →';
        linkEl.onclick     = (e) => {
            e.preventDefault();
            document.getElementById(items[0].section).scrollIntoView({ behavior: 'smooth' });
        };
    }

    renderTodayReadingCard();

    // ── Activity Heatmap ───────────────────────────────────────────────────────

    // Retrospective, so it starts collapsed — but a reader who opens it keeps it open.
    bindDisclosure(document.getElementById('activity-details'), false);

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
                    const iso   = localIsoDate(date);
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

    // ── Sermon Notes (preview → /notes workspace) ────────────────────────────

    function formatUpdatedAt(iso) {
        return new Date(iso).toLocaleDateString('en-US', { year: 'numeric', month: 'short', day: 'numeric' });
    }

    function escapeHtmlLocal(str) {
        return escapeHtml(str);
    }

    const notesList  = document.getElementById('sermon-notes-list');
    const notesEmpty = document.getElementById('sermon-notes-empty');

    function renderSermonNotesPreview() {
        notesList.innerHTML = '';
        if (!sermonNotes.length) {
            notesEmpty.hidden = false;
            return;
        }
        notesEmpty.hidden = true;
        sermonNotes.slice(0, 5).forEach(n => {
            const row = document.createElement('a');
            row.className = 'sermon-note-row';
            row.href = `/notes?id=${encodeURIComponent(n.id)}`;
            row.innerHTML = `
                <span class="sermon-note-row-title">${escapeHtmlLocal(n.title)}</span>
                <span class="sermon-note-row-meta">Updated ${formatUpdatedAt(n.updatedAt)}</span>
                <span class="sermon-note-row-snippet">${escapeHtmlLocal(n.snippet)}</span>
            `;
            notesList.appendChild(row);
        });
        if (sermonNotes.length > 5) {
            const more = document.createElement('a');
            more.className = 'sermon-notes-more';
            more.href = '/notes';
            more.textContent = `View all ${sermonNotes.length} notes →`;
            notesList.appendChild(more);
        }
    }

    renderSermonNotesPreview();

})();
