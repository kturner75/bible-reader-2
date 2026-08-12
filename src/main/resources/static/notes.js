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
     * alone — safe between tags, unsafe inside an attribute, where a value like
     * `" onfocus="…` closes the attribute and injects a new one. Use this for any
     * value interpolated into a quoted attribute.
     */
    function escapeAttr(str) {
        return escapeHtml(str).replace(/"/g, '&quot;').replace(/'/g, '&#39;');
    }

    let _toastTimer = null;
    function showToast(message, durationMs = 2500) {
        const existing = document.querySelector('.toast');
        if (existing) existing.remove();
        if (_toastTimer) clearTimeout(_toastTimer);
        const toast = document.createElement('div');
        toast.className = 'toast';
        toast.textContent = message;
        document.body.appendChild(toast);
        _toastTimer = setTimeout(() => {
            toast.classList.add('toast-hiding');
            setTimeout(() => toast.remove(), 400);
        }, durationMs);
    }

    // Editor state must exist before auth/nav handlers can run (slow initial fetches).
    let editingNoteId = null;
    let savedTitle = '';
    let savedNoteText = '';
    let editorMode = null; // 'view' | 'edit' | null
    let openNoteGen = 0;
    let savingNote = false;
    let saveWriteDispatched = false; // true once POST/PUT has been sent
    let allowUnload = false;

    function isEditorDirty() {
        if (editorMode !== 'edit') return false;
        if (!document.getElementById('sermon-note-title-input')) return false;
        const titleInput = document.getElementById('sermon-note-title-input');
        const textarea = document.getElementById('sermon-note-textarea');
        return titleInput.value !== savedTitle || textarea.value !== savedNoteText;
    }

    function confirmDiscardEdits() {
        return window.confirm('Discard unsaved note changes?');
    }

    /** Once the write hits the server it cannot be canceled client-side. */
    function blockIfSaveDispatched() {
        if (!saveWriteDispatched) return false;
        showToast('Still saving…');
        return true;
    }

    function unlockEditorInputs() {
        savingNote = false;
        saveWriteDispatched = false;
        const titleEl = document.getElementById('sermon-note-title-input');
        const bodyEl = document.getElementById('sermon-note-textarea');
        if (titleEl) titleEl.disabled = false;
        if (bodyEl) bodyEl.disabled = false;
        const cancelBtn = document.getElementById('sermon-note-cancel-btn');
        if (cancelBtn) cancelBtn.disabled = false;
        const insertBtnEl = document.getElementById('sermon-note-insert-passage-btn');
        if (insertBtnEl) insertBtnEl.disabled = false;
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
        <a href="/dashboard" class="nav-link">Dashboard</a>
        <a href="/read" class="btn-nav">Open Reader</a>
        <button class="nav-signout" id="nav-signout">Sign Out</button>
    `;
    document.getElementById('nav-signout').addEventListener('click', async (e) => {
        if (saveWriteDispatched) {
            e.preventDefault();
            e.stopImmediatePropagation();
            showToast('Still saving…');
            return;
        }
        if (isEditorDirty() && !confirmDiscardEdits()) {
            e.preventDefault();
            e.stopImmediatePropagation();
            return;
        }
        // Abandon any pre-write save so it cannot commit after discard.
        if (savingNote) {
            openNoteGen++;
            unlockEditorInputs();
        }
        allowUnload = true;
        await (window.KjvCsrf
            ? window.KjvCsrf.fetch('/api/auth/logout', { method: 'POST' })
            : fetch('/api/auth/logout', { method: 'POST', credentials: 'include' }));
        window.location.href = '/landing.html';
    });

    // ── Data ─────────────────────────────────────────────────────────────────

    let sermonNotes = [];
    let collections = [];
    let passages = [];
    try {
        const [sermonNotesRes, collectionsRes, passagesRes] = await Promise.all([
            fetch('/api/sermon-notes', { credentials: 'include' }),
            fetch('/api/collections', { credentials: 'include' }),
            fetch('/api/passages', { credentials: 'include' }),
        ]);
        if (sermonNotesRes.ok) sermonNotes = await sermonNotesRes.json();
        if (collectionsRes.ok) collections = await collectionsRes.json();
        if (passagesRes.ok) passages = await passagesRes.json();
    } catch (_) { /* stay with defaults */ }

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
                return `<a class="note-range-link" data-v="${escapeAttr(body)}" href="#">${escapeHtml(label)}</a>`;
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
            return `<a class="note-verse-link" data-ref="${escapeAttr(trimmed)}" href="#">${match}</a>`;
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

    const notesList     = document.getElementById('sermon-notes-list');
    const notesEmpty    = document.getElementById('sermon-notes-empty');
    const paneEmpty     = document.getElementById('notes-pane-empty');
    const viewSection   = document.getElementById('sermon-note-view');
    const viewTitle     = document.getElementById('sermon-note-view-title-text');
    const viewBody      = document.getElementById('sermon-note-view-body');
    const viewActions   = document.getElementById('sermon-note-view-actions');
    const editSection   = document.getElementById('sermon-note-edit');
    const modalTitle    = document.getElementById('sermon-note-modal-title');
    const titleInput    = document.getElementById('sermon-note-title-input');
    const textarea      = document.getElementById('sermon-note-textarea');
    const charCurrent   = document.getElementById('sermon-note-char-current');

    function syncUrl() {
        const params = new URLSearchParams();
        if (editingNoteId) params.set('id', editingNoteId);
        else if (editorMode === 'edit' && !editingNoteId) params.set('new', '1');
        const qs = params.toString();
        const next = qs ? `/notes?${qs}` : '/notes';
        if (`${location.pathname}${location.search}` !== next) {
            history.replaceState({}, '', next);
        }
    }

    function showPaneEmpty() {
        openNoteGen++;
        editorMode = null;
        editingNoteId = null;
        savedTitle = '';
        savedNoteText = '';
        paneEmpty.hidden = false;
        viewSection.hidden = true;
        editSection.hidden = true;
        syncUrl();
        renderSermonNotesList();
    }

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
                    if (parsed.valid) {
                        if (Array.isArray(parsed.ranges) && parsed.ranges.length) {
                            parts.push(serializeVToken(parsed.ranges.map(r => ({
                                from: r.from, to: r.to
                            }))));
                            continue;
                        }
                        if (parsed.v) {
                            parts.push(`[v=${parsed.v}]`);
                            continue;
                        }
                        if (parsed.verseId) {
                            parts.push(serializeVToken([{ from: parsed.verseId, to: parsed.verseId }]));
                            continue;
                        }
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
        editorMode = mode;
        paneEmpty.hidden = true;
        if (mode === 'view') {
            viewSection.hidden = false;
            editSection.hidden = true;
            hydrateRangeLinkLabels(viewBody);
        } else {
            viewSection.hidden = true;
            editSection.hidden = false;
            titleInput.focus();
        }
        syncUrl();
        renderSermonNotesList();
    }

    function renderSermonNotesList() {
        notesList.innerHTML = '';
        if (sermonNotes.length === 0) {
            notesEmpty.hidden = false;
            return;
        }
        notesEmpty.hidden = true;
        sermonNotes.forEach(n => {
            const row = document.createElement('button');
            row.type = 'button';
            row.className = 'sermon-note-row' + (n.id === editingNoteId ? ' active' : '');
            row.innerHTML = `
                <span class="sermon-note-row-title">${escapeHtml(n.title)}</span>
                <span class="sermon-note-row-meta">Updated ${formatUpdatedAt(n.updatedAt)}</span>
                <span class="sermon-note-row-snippet">${escapeHtml(n.snippet)}</span>
            `;
            row.addEventListener('click', () => openSermonNote(n.id));
            notesList.appendChild(row);
        });
    }

    async function openSermonNote(id) {
        if (editingNoteId === id && editorMode) {
            if (editorMode === 'edit') titleInput.focus();
            return;
        }
        // Write already on the wire — cannot abandon; wait for it to finish.
        if (blockIfSaveDispatched()) return;
        // Confirm discard first — do not abandon an in-flight save if the user cancels.
        if (isEditorDirty() && !confirmDiscardEdits()) return;
        const gen = ++openNoteGen;
        if (savingNote) unlockEditorInputs();
        try {
            const res = await fetch(`/api/sermon-notes/${id}`, { credentials: 'include' });
            if (gen !== openNoteGen) return;
            if (!res.ok) return;
            const note = await res.json();
            if (gen !== openNoteGen) return;
            editingNoteId = note.id;
            savedTitle = note.title;
            savedNoteText = note.note;
            modalTitle.textContent = 'Edit Note';
            viewTitle.textContent = note.title;
            viewBody.innerHTML = renderNoteMarkdown(note.note);
            titleInput.value = note.title;
            textarea.value = note.note;
            updateCharCount();
            setMode('view');
        } catch (_) { /* ignore */ }
    }

    function openNewSermonNote() {
        if (blockIfSaveDispatched()) return;
        // Confirm discard first — do not abandon an in-flight save if the user cancels.
        if (isEditorDirty() && !confirmDiscardEdits()) return;
        openNoteGen++; // invalidate any in-flight open/save
        if (savingNote) unlockEditorInputs();
        editingNoteId = null;
        savedTitle = '';
        savedNoteText = '';
        modalTitle.textContent = 'New Note';
        titleInput.value = '';
        textarea.value = '';
        updateCharCount();
        setMode('edit');
    }

    function closeEditor() {
        if (blockIfSaveDispatched()) return;
        if (isEditorDirty() && !confirmDiscardEdits()) return;
        openNoteGen++;
        if (savingNote) unlockEditorInputs();
        showPaneEmpty();
    }

    function guardLeave(e) {
        if (allowUnload) return;
        if (!saveWriteDispatched && !isEditorDirty()) return;
        // beforeunload cannot use a custom confirm string in modern browsers,
        // but returning/setting returnValue still prompts.
        e.preventDefault();
        e.returnValue = '';
        return '';
    }

    window.addEventListener('beforeunload', guardLeave);

    document.getElementById('nav-links').addEventListener('click', (e) => {
        const link = e.target.closest('a[href]');
        if (!link) return;
        if (saveWriteDispatched) {
            e.preventDefault();
            e.stopPropagation();
            showToast('Still saving…');
            return;
        }
        if (!isEditorDirty()) return;
        // Modified clicks open a new tab/window; keep the unload guard for this tab.
        if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey || e.button !== 0) return;
        if (!confirmDiscardEdits()) {
            e.preventDefault();
            e.stopPropagation();
            return;
        }
        // Abandon any pre-write save so it cannot commit after discard.
        if (savingNote) {
            openNoteGen++;
            unlockEditorInputs();
        }
        allowUnload = true;
    });

    async function saveSermonNote() {
        if (savingNote) return;
        const title = titleInput.value.trim();
        let note = textarea.value.trim();
        if (!title || !note) {
            showToast('Title and note are required');
            return;
        }
        const targetId = editingNoteId;
        const saveGen = openNoteGen;
        savingNote = true;
        saveWriteDispatched = false;
        titleInput.disabled = true;
        textarea.disabled = true;
        const cancelBtn = document.getElementById('sermon-note-cancel-btn');
        const insertBtnEl = document.getElementById('sermon-note-insert-passage-btn');
        if (insertBtnEl) insertBtnEl.disabled = true;
        const insertOverlayEl = document.getElementById('passage-insert-overlay');
        if (insertOverlayEl && !insertOverlayEl.hidden) {
            insertOverlayEl.hidden = true;
        }
        try {
            note = await normalizeNoteLinksOnSave(note);
            if (saveGen !== openNoteGen) return; // user switched notes mid-save
            textarea.value = note;
            updateCharCount();
            const maxLen = parseInt(textarea.getAttribute('maxlength'), 10) || 20000;
            if (note.length > maxLen) {
                showToast(`Note is too long after converting scripture links (${maxLen} char limit)`);
                return;
            }
            // From here the server will commit — cancel/navigation must not abandon.
            saveWriteDispatched = true;
            if (cancelBtn) cancelBtn.disabled = true;
            const saveOpts = {
                method: targetId ? 'PUT' : 'POST',
                credentials: 'include',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ title, note }),
            };
            const res = await (window.KjvCsrf
                ? window.KjvCsrf.fetch(
                    targetId ? `/api/sermon-notes/${targetId}` : '/api/sermon-notes',
                    saveOpts)
                : fetch(
                    targetId ? `/api/sermon-notes/${targetId}` : '/api/sermon-notes',
                    saveOpts));
            if (saveGen !== openNoteGen) return;
            if (!res.ok) {
                showToast('Failed to save note');
                return;
            }
            const saved = await res.json();
            if (saveGen !== openNoteGen) return;

            const listRes = await fetch('/api/sermon-notes', { credentials: 'include' });
            if (saveGen !== openNoteGen) return;
            if (listRes.ok) sermonNotes = await listRes.json();

            editingNoteId = saved.id;
            savedTitle = saved.title;
            savedNoteText = saved.note;
            titleInput.value = saved.title;
            textarea.value = saved.note;
            updateCharCount();

            modalTitle.textContent = 'Edit Note';
            viewTitle.textContent = saved.title;
            viewBody.innerHTML = renderNoteMarkdown(saved.note);
            setMode('view');
            showToast('Note saved');
        } catch (_) {
            showToast('Failed to save note');
        } finally {
            // Only unlock if this save still owns the editor; an abandoned save
            // must not clear savingNote / re-enable inputs for a newer save.
            if (saveGen === openNoteGen) unlockEditorInputs();
        }
    }

    async function deleteSermonNote() {
        if (!editingNoteId || savingNote) return;
        if (!confirm('Delete this sermon note? This cannot be undone.')) return;
        const targetId = editingNoteId;
        const deleteGen = openNoteGen;
        try {
            const res = await (window.KjvCsrf
                ? window.KjvCsrf.fetch(`/api/sermon-notes/${targetId}`, { method: 'DELETE' })
                : fetch(`/api/sermon-notes/${targetId}`, {
                    method: 'DELETE',
                    credentials: 'include',
                }));
            if (deleteGen !== openNoteGen) return;
            if (!res.ok && res.status !== 204) return;
            sermonNotes = sermonNotes.filter(n => n.id !== targetId);
            showPaneEmpty();
            showToast('Note deleted');
        } catch (_) { /* ignore */ }
    }

    document.getElementById('sermon-note-new-btn').addEventListener('click', openNewSermonNote);
    document.getElementById('sermon-notes-empty-new-btn').addEventListener('click', openNewSermonNote);
    document.getElementById('sermon-note-edit-btn').addEventListener('click', () => {
        if (savingNote) return;
        setMode('edit');
    });
    document.getElementById('sermon-note-save-btn').addEventListener('click', saveSermonNote);
    document.getElementById('sermon-note-delete-btn').addEventListener('click', deleteSermonNote);
    document.getElementById('sermon-note-cancel-btn').addEventListener('click', () => {
        // After the write is dispatched the server will commit — do not pretend to cancel.
        if (blockIfSaveDispatched()) return;
        if (savingNote) {
            openNoteGen++; // abandon in-flight save (pre-write / normalize only)
            unlockEditorInputs();
        }
        if (editingNoteId) {
            titleInput.value = savedTitle;
            textarea.value = savedNoteText;
            updateCharCount();
            setMode('view');
        } else {
            closeEditor();
        }
    });
    textarea.addEventListener('input', updateCharCount);

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
    const insertSave = document.getElementById('passage-insert-save');
    const insertSaveCb = document.getElementById('passage-insert-save-cb');
    const insertTitle = document.getElementById('passage-insert-title');
    const insertConfirm = document.getElementById('passage-insert-confirm');
    const insertBtn = document.getElementById('sermon-note-insert-passage-btn');

    const INSERT_MAX_VERSES = 500;
    /** UpsertPassageRequest.naturalKey max is 500 chars — disable Save when key is too long. */
    const PASSAGE_NATURAL_KEY_MAX = 500;
    let insertTab = 'verses';
    let insertMode = 'browse';
    let insertSearchTimer = null;
    let insertSearchGen = 0;
    let insertExpandGen = 0;

    function closeInsertPicker() {
        insertOverlay.hidden = true;
        insertMode = 'browse';
        insertSearchGen++;
        insertExpandGen++;
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
        insertExpandGen++;
        if (insertBrowse) insertBrowse.hidden = false;
        if (insertExpand) insertExpand.hidden = true;
        if (insertTabs) insertTabs.hidden = false;
        insertSearch.hidden = false;
    }

    function setInsertTab(tab) {
        insertTab = tab === 'passages' ? 'passages' : 'verses';
        insertSearchGen++;
        if (insertSearchTimer) {
            clearTimeout(insertSearchTimer);
            insertSearchTimer = null;
        }
        syncInsertTabs();
        showInsertBrowse();
        renderInsertBrowse();
        insertSearch.focus();
    }

    /** @returns {boolean} true if the portable link was inserted */
    function insertVTokenFromNaturalKey(naturalKey) {
        if (savingNote) {
            showToast('Still saving…');
            return false;
        }
        let token;
        try {
            token = serializeVToken(rangesFromNaturalKey(naturalKey));
        } catch (err) {
            console.error(err);
            showToast('Could not insert scripture link');
            return false;
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
            showToast(`Not enough room for that link (${maxLen} char limit)`);
            return false;
        }
        textarea.value = next;
        const caret = before.length + insert.length;
        textarea.focus();
        textarea.setSelectionRange(caret, caret);
        updateCharCount();
        closeInsertPicker();
        showToast('Scripture link inserted');
        return true;
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
                data-natural-key="${escapeAttr(p.naturalKey || '')}">
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
            insertSearchGen++;
            if (insertSearchTimer) {
                clearTimeout(insertSearchTimer);
                insertSearchTimer = null;
            }
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
        const expandGen = ++insertExpandGen;
        insertMode = 'expand';
        insertBrowse.hidden = true;
        insertExpand.hidden = false;
        insertTabs.hidden = true;
        insertSearch.hidden = true;
        resetInsertSaveUi();
        insertChapters.innerHTML = '<div class="passage-picker-loading">Loading…</div>';
        insertConfirm.disabled = true;
        insertExpandCount.textContent = 'Loading…';

        let context;
        try {
            const res = await fetch(`/api/ranges/context/${verseId}`, { credentials: 'include' });
            if (!res.ok) throw new Error('context failed');
            context = await res.json();
        } catch (err) {
            console.error(err);
            if (expandGen !== insertExpandGen || insertMode !== 'expand') return;
            insertChapters.innerHTML = '<div class="passage-picker-loading">Could not load surrounding verses.</div>';
            return;
        }

        if (expandGen !== insertExpandGen || insertMode !== 'expand') return;
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
        syncInsertSaveAvailability(checked);
    }

    function syncInsertSaveAvailability(checkedIds) {
        if (!insertSaveCb) return;
        const key = checkedIds.length ? buildNaturalKeyFromIds(checkedIds) : '';
        const tooLong = !!(key && key.length > PASSAGE_NATURAL_KEY_MAX);
        insertSaveCb.disabled = tooLong;
        if (tooLong && insertSaveCb.checked) {
            insertSaveCb.checked = false;
            syncInsertSaveTitleEnabled();
        }
        if (insertSave) {
            insertSave.title = tooLong
                ? 'Selection is too fragmented to save as a passage (natural key limit)'
                : '';
        }
    }

    function resetInsertSaveUi() {
        if (insertSaveCb) {
            insertSaveCb.checked = false;
            insertSaveCb.disabled = false;
        }
        if (insertTitle) {
            insertTitle.value = '';
            insertTitle.disabled = true;
        }
        if (insertSave) {
            insertSave.hidden = false;
            insertSave.title = '';
        }
    }

    function syncInsertSaveTitleEnabled() {
        if (!insertTitle || !insertSaveCb) return;
        insertTitle.disabled = !insertSaveCb.checked;
        if (insertSaveCb.checked) insertTitle.focus();
    }

    function confirmInsertExpand() {
        const checked = getInsertCheckedIds();
        if (!checked.length || checked.length > INSERT_MAX_VERSES) return;
        const naturalKey = buildNaturalKeyFromIds(checked);
        const shouldSave = !!(insertSaveCb
            && insertSaveCb.checked
            && naturalKey
            && naturalKey.length <= PASSAGE_NATURAL_KEY_MAX);
        const title = (insertTitle?.value || '').trim();
        // Insert first — saving is optional and must not block the portable link.
        const inserted = insertVTokenFromNaturalKey(naturalKey);
        if (inserted && shouldSave) {
            savePassageFromInsert(naturalKey, title);
        }
    }

    async function savePassageFromInsert(naturalKey, title) {
        try {
            const res = await (window.KjvCsrf
                ? window.KjvCsrf.fetch('/api/passages', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ naturalKey, title: title || null })
                })
                : fetch('/api/passages', {
                    method: 'POST',
                    credentials: 'include',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ naturalKey, title: title || null })
                }));
            if (!res.ok) throw new Error('save failed');
            const listRes = await fetch('/api/passages', { credentials: 'include' });
            if (listRes.ok) {
                passages = await listRes.json();
            }
            showToast(title ? 'Passage saved' : 'Passage saved (untitled)');
        } catch (err) {
            console.error('Failed to save passage from insert', err);
            showToast('Link inserted, but saving the passage failed');
        }
    }

    if (insertBtn) {
        insertBtn.addEventListener('click', () => {
            if (savingNote) {
                showToast('Still saving…');
                return;
            }
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
        if (insertSaveCb) {
            insertSaveCb.addEventListener('change', syncInsertSaveTitleEnabled);
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
                    if (parsed.valid) {
                        if (parsed.v) {
                            window.location.href = `/read/range?v=${encodeURIComponent(parsed.v)}`;
                        } else if (Array.isArray(parsed.ranges) && parsed.ranges.length) {
                            const body = parsed.ranges.map(r =>
                                r.from === r.to ? String(r.from) : `${r.from}-${r.to}`
                            ).join(',');
                            window.location.href = `/read/range?v=${encodeURIComponent(body)}`;
                        } else if (parsed.verseId) {
                            window.location.href = `/read?vid=${parsed.verseId}`;
                        }
                    }
                }
            } catch (_) { /* ignore */ }
        }
    });

    // Deep-link: /notes?id=… or /notes?new=1
    const bootParams = new URLSearchParams(location.search);
    const bootId = bootParams.get('id');
    const bootNew = bootParams.get('new') === '1';
    if (bootId) {
        openSermonNote(bootId);
    } else if (bootNew) {
        openNewSermonNote();
    } else {
        showPaneEmpty();
    }

})();
