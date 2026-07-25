/**
 * KJV Bible Reader - Frontend Application
 * Distraction-free Bible reading with dynamic viewport-fitted pages
 */

(function() {
    'use strict';

    // ============================================
    // State Management
    // ============================================
    const state = {
        // Currently displayed verses on page
        pageVerses: [],
        // Current verse ID (the highlighted one)
        currentVerseId: 1,
        // First verse ID visible on current page
        pageStartVerseId: 1,
        // All books metadata
        books: [],
        // Current book chapters
        chapters: [],
        // Total verses in Bible
        totalVerses: 31102,
        // Reading-area size at last page measurement (guards relayout triggers)
        lastMeasuredWidth: 0,
        lastMeasuredHeight: 0,
        // True once init() has loaded the first page; relayout triggers
        // (resize/ResizeObserver) are ignored before then so they can't
        // race the initial load of the saved verse
        initialPageLoaded: false,
        // Font size multiplier
        fontSizeMultiplier: 1.0,
        // Loading state
        isLoading: true,
        // Search overlay open
        searchOpen: false,
        // Help overlay open
        helpOpen: false,
        // Saved verses feature
        savedVerses: {},           // { verseId: { id, savedAt, tagIds, note } }
        tags: {},                  // { tagId: { id, name, colorIndex, createdAt } }
        libraryOpen: false,        // library modal state
        libraryFilters: {
            search: '',            // text search for verse text + notes
            tagIds: [],            // selected tag IDs (OR logic)
            categoryIds: [],       // selected category IDs (OR logic)
            bookIds: [],           // selected book IDs (OR logic)
            sort: 'date-desc'      // 'date-desc', 'date-asc', 'canonical'
        },
        libraryFiltersExpanded: false, // additional filters collapsed state
        libraryView: 'verses',     // 'verses' | 'chapter-notes'
        tagPickerOpen: false,      // tag picker modal state
        noteEditorOpen: false,     // note editor modal state
        tagPickerVerseId: null,    // which verse the tag picker is for
        noteEditorVerseId: null,   // which verse the note editor is for
        // Chapter notes (account-only — no localStorage mode)
        chapterNotes: {},              // { "bookId:chapter": { bookId, bookName, chapter, firstVerseId, verseCount, note, updatedAt } }
        bookNotes: {},                 // { bookId: { bookId, bookName, firstVerseId, note, updatedAt } }
        chapterNoteEditorOpen: false,  // shared note modal state (chapter + book notes)
        chapterNoteEditorTarget: null, // { type: 'chapter'|'book', bookId, chapter?, bookName?, label } while modal open
        // TTS Audio
        audioEnabled: false,       // Feature flag from backend
        audioPlaying: false,       // Currently playing
        audioSpeed: 1.0,           // 1, 1.25, 1.5, 1.75, 2
        audioPendingChapter: null, // { book, chapter } if chapter announcement pending
        audioWasPlayingBeforeModal: false,  // Track if audio was playing when modal opened
        mobileMenuOpen: false,             // Mobile quick-actions sheet
        // Memorization
        memorizationOpen: false,           // memorization queue modal state
        memorizedPassages: {},             // { naturalKey: entryId } e.g. { "26930": "uuid" }
        memorizedEntries: [],              // full entry list [{id, fromVerseId, toVerseId, naturalKey}]
        memorizationDueEntries: [],        // entries due today, set on each queue render
        passagePickerOpen: false,          // passage picker modal state
        // Passage collections (account-only — no localStorage mode)
        collections: [],                   // summaries [{id, label, passageCount, verseCount, createdAt, updatedAt}]
        passages: [],                      // catalog for note picker [{id, title, reference, naturalKey, global}]
        collectionsOpen: false,            // collections hub modal state
        collectionBuilderOpen: false,      // collection builder modal state
        passageInsertOpen: false,          // insert-scripture picker for notes
        passageInsertTarget: null,         // textarea element to insert into
        passageInsertTab: 'verses',        // 'verses' | 'passages'
        passageInsertMode: 'browse',       // 'browse' | 'expand'
        passageInsertSearchTimer: null,
        passageInsertSearchGen: 0,
        passageInsertExpandGen: 0,
        // Header search overlay tabs (verses always; passages only when catalog non-empty)
        searchResultTab: 'verses',         // 'verses' | 'passages' | future lanes
        lastSearchQuery: '',
        lastSearchResults: null,           // { query, count, verses: [...] } display slice
        lastSearchHitIds: null,            // Set<number> wider hit ids for passage overlap
        // Scoped reader: collection OR single focused passage/range
        // { kind:'collection'|'passage'|'range', id, label, verses, ... }
        collection: null,
        // Where to return when leaving scoped mode (reading position + optional note)
        // { verseId, note: null | { type:'verse', verseId } | { type:'chapter'|'book', ... }, historyPushed }
        scopedReturn: null,
        // Auth
        currentUser: null                  // null = anonymous; object = { id, email, displayName }
    };

    // ============================================
    // DOM Elements
    // ============================================
    const elements = {
        readingArea: document.getElementById('reading-area'),
        chapterTitle: document.getElementById('chapter-title'),
        currentReference: document.getElementById('current-reference'),
        pageInfo: document.getElementById('page-info'),
        bookSelect: document.getElementById('book-select'),
        chapterSelect: document.getElementById('chapter-select'),
        verseSelect: document.getElementById('verse-select'),
        searchInput: document.getElementById('search-input'),
        searchAutocomplete: document.getElementById('search-autocomplete'),
        searchOverlay: document.getElementById('search-overlay'),
        searchResultTabs: document.getElementById('search-result-tabs'),
        searchPassagesTab: document.getElementById('search-passages-tab'),
        searchResultsList: document.getElementById('search-results-list'),
        searchResultsTitle: document.getElementById('search-results-title'),
        searchClose: document.getElementById('search-close'),
        helpOverlay: document.getElementById('help-overlay'),
        helpToggle: document.getElementById('help-toggle'),
        helpClose: document.getElementById('help-close'),
        fontIncrease: document.getElementById('font-increase'),
        fontDecrease: document.getElementById('font-decrease'),
        loadingOverlay: document.getElementById('loading-overlay'),
        // Library (saved verses)
        libraryToggle: document.getElementById('library-toggle'),
        libraryOverlay: document.getElementById('library-overlay'),
        libraryClose: document.getElementById('library-close'),
        librarySearch: document.getElementById('library-search'),
        librarySort: document.getElementById('library-sort'),
        libraryCategories: document.getElementById('library-categories'),
        libraryBooks: document.getElementById('library-books'),
        libraryTags: document.getElementById('library-tags'),
        libraryResultsCount: document.getElementById('library-results-count'),
        libraryResults: document.getElementById('library-results'),
        libraryFiltersToggle: document.getElementById('library-filters-toggle'),
        libraryFiltersBadge: document.getElementById('filters-toggle-badge'),
        libraryAdditionalFilters: document.getElementById('library-additional-filters'),
        // Tag picker
        tagPickerOverlay: document.getElementById('tag-picker-overlay'),
        tagPickerClose: document.getElementById('tag-picker-close'),
        tagPickerVerseRef: document.getElementById('tag-picker-verse-ref'),
        tagList: document.getElementById('tag-list'),
        newTagInput: document.getElementById('new-tag-input'),
        createTagBtn: document.getElementById('create-tag-btn'),
        // Note editor
        noteEditorOverlay: document.getElementById('note-editor-overlay'),
        noteEditorClose: document.getElementById('note-editor-close'),
        noteEditorVerseRef: document.getElementById('note-editor-verse-ref'),
        noteView: document.getElementById('note-view'),
        noteViewActions: document.getElementById('note-view-actions'),
        noteEditBtn: document.getElementById('note-edit-btn'),
        noteDoneBtn: document.getElementById('note-done-btn'),
        noteEdit: document.getElementById('note-edit'),
        noteTextarea: document.getElementById('note-textarea'),
        noteCharCurrent: document.getElementById('note-char-current'),
        noteSaveBtn: document.getElementById('note-save-btn'),
        noteCancelBtn: document.getElementById('note-cancel-btn'),
        // Note editor (shared by chapter + book notes)
        chapterNoteOverlay: document.getElementById('chapter-note-overlay'),
        chapterNoteTitle: document.getElementById('chapter-note-title'),
        chapterNoteCharMax: document.getElementById('chapter-note-char-max'),
        chapterNoteHintLinks: document.getElementById('chapter-note-hint-links'),
        chapterNoteClose: document.getElementById('chapter-note-close'),
        chapterNoteRef: document.getElementById('chapter-note-ref'),
        chapterNoteSignin: document.getElementById('chapter-note-signin'),
        chapterNoteView: document.getElementById('chapter-note-view'),
        chapterNoteViewActions: document.getElementById('chapter-note-view-actions'),
        chapterNoteEditBtn: document.getElementById('chapter-note-edit-btn'),
        chapterNoteDoneBtn: document.getElementById('chapter-note-done-btn'),
        chapterNoteEdit: document.getElementById('chapter-note-edit'),
        chapterNoteTextarea: document.getElementById('chapter-note-textarea'),
        chapterNoteCharCurrent: document.getElementById('chapter-note-char-current'),
        chapterNoteSaveBtn: document.getElementById('chapter-note-save-btn'),
        chapterNoteCancelBtn: document.getElementById('chapter-note-cancel-btn'),
        // Library tabs
        libraryTabVerses: document.getElementById('library-tab-verses'),
        libraryTabChapterNotes: document.getElementById('library-tab-chapter-notes'),
        libraryFiltersBar: document.getElementById('library-filters-bar'),
        // Audio
        audioControls: document.getElementById('audio-controls'),
        audioToggle: document.getElementById('audio-toggle'),
        audioSpeedBadge: document.getElementById('audio-speed-badge'),
        ttsAudio: document.getElementById('tts-audio'),
        ttsAudioBuffer: document.getElementById('tts-audio-buffer'),
        // Mobile navigation buttons
        mobilePrev: document.getElementById('mobile-prev'),
        mobileNext: document.getElementById('mobile-next'),
        // Mobile quick-actions menu
        mobileMenuOverlay: document.getElementById('mobile-menu-overlay'),
        mobileMenuBookmarkLabel: document.getElementById('mobile-menu-bookmark-label'),
        // Mobile search cancel button
        mobileSearchCancel: document.getElementById('mobile-search-cancel'),
        // Auth header area
        authHeader: document.getElementById('auth-header'),
        authDisplayName: document.getElementById('auth-display-name'),
        authLogoutBtn: document.getElementById('auth-logout-btn'),
        authSigninLink: document.getElementById('auth-signin-link'),
        // Memorization queue modal
        memorizationToggle: document.getElementById('memorization-toggle'),
        memorizationOverlay: document.getElementById('memorization-overlay'),
        memorizationClose: document.getElementById('memorization-close'),
        // Passage picker modal
        passagePickerOverlay: document.getElementById('passage-picker-overlay'),
        passagePickerChapters: document.getElementById('passage-picker-chapters'),
        passagePickerCount: document.getElementById('passage-picker-count'),
        passagePickerAdd: document.getElementById('passage-picker-add'),
        passagePickerRemove: document.getElementById('passage-picker-remove'),
        passagePickerClose: document.getElementById('passage-picker-close'),
        passagePickerCancel: document.getElementById('passage-picker-cancel'),
        // Passage collections hub modal
        collectionsToggle: document.getElementById('collections-toggle'),
        collectionsOverlay: document.getElementById('collections-overlay'),
        collectionsClose: document.getElementById('collections-close'),
        collectionsCount: document.getElementById('collections-count'),
        collectionsList: document.getElementById('collections-list'),
        collectionsNew: document.getElementById('collections-new'),
        // Insert-scripture picker (notes)
        passageInsertOverlay: document.getElementById('passage-insert-overlay'),
        passageInsertClose: document.getElementById('passage-insert-close'),
        passageInsertSearch: document.getElementById('passage-insert-search'),
        passageInsertTabs: document.getElementById('passage-insert-tabs'),
        passageInsertBrowse: document.getElementById('passage-insert-browse'),
        passageInsertCount: document.getElementById('passage-insert-count'),
        passageInsertList: document.getElementById('passage-insert-list'),
        passageInsertExpand: document.getElementById('passage-insert-expand'),
        passageInsertExpandBack: document.getElementById('passage-insert-expand-back'),
        passageInsertExpandCount: document.getElementById('passage-insert-expand-count'),
        passageInsertChapters: document.getElementById('passage-insert-chapters'),
        passageInsertConfirm: document.getElementById('passage-insert-confirm'),
        noteInsertPassageBtn: document.getElementById('note-insert-passage-btn'),
        chapterNoteInsertPassageBtn: document.getElementById('chapter-note-insert-passage-btn'),
        // Collection builder modal
        cbOverlay: document.getElementById('collection-builder-overlay'),
        cbTitle: document.getElementById('cb-title'),
        cbClose: document.getElementById('cb-close'),
        cbBookSelect: document.getElementById('cb-book-select'),
        cbChapterSelect: document.getElementById('cb-chapter-select'),
        cbPrevCh: document.getElementById('cb-prev-ch'),
        cbNextCh: document.getElementById('cb-next-ch'),
        cbVerseList: document.getElementById('cb-verse-list'),
        cbAddChecked: document.getElementById('cb-add-checked'),
        cbLabel: document.getElementById('cb-label'),
        cbQueueCount: document.getElementById('cb-queue-count'),
        cbQueueList: document.getElementById('cb-queue-list'),
        cbCancel: document.getElementById('cb-cancel'),
        cbSave: document.getElementById('cb-save'),
        // Memorization queue modal
        memorizationResultsCount: document.getElementById('memorization-results-count'),
        memorizationDueBar: document.getElementById('memorization-due-bar'),
        memorizationDueCount: document.getElementById('memorization-due-count'),
        memorizationTrainBtn: document.getElementById('memorization-train-btn'),
        memorizationList: document.getElementById('memorization-list')
    };

    // ============================================
    // LocalStorage Keys
    // ============================================
    const STORAGE_KEYS = {
        CURRENT_VERSE: 'kjv_current_verse',
        FONT_SIZE: 'kjv_font_size',
        SAVED_VERSES: 'kjv_saved_verses',
        TAGS: 'kjv_tags',
        AUDIO_SPEED: 'kjv_audio_speed'
    };

    // ============================================
    // Audio URL Cache
    // ============================================
    // Caches resolved CDN URLs so each verse/chapter only needs one API round-trip ever.
    // Key format: 'verse:{id}' or 'chapter:{book}:{chapter}'
    const audioUrlCache = new Map();

    // ============================================
    // Library API Helper
    // ============================================

    async function libApi(url, options = {}) {
        const res = await fetch(url, { credentials: 'include', ...options });
        if (!res.ok) throw new Error(`API ${res.status} for ${url}`);
        return res.status === 204 ? null : res.json();
    }

    function buildNaturalKey(fromVerseId, toVerseId) {
        return fromVerseId === toVerseId
            ? String(fromVerseId)
            : `${fromVerseId}:${toVerseId}`;
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

    // ============================================
    // Tag Colors
    // ============================================
    const TAG_COLORS = [
        '#8B4513',  // Saddle Brown
        '#6B8E23',  // Olive Drab
        '#4682B4',  // Steel Blue
        '#CD853F',  // Peru
        '#708090',  // Slate Gray
        '#9ACD32',  // Yellow Green
        '#BC8F8F',  // Rosy Brown
        '#DAA520'   // Goldenrod
    ];
    const TAG_COLOR_DEFAULT = '#d4a84b';

    // ============================================
    // Book Categories
    // ============================================
    const BOOK_CATEGORIES = [
        { id: 'pentateuch', name: 'Pentateuch', bookIds: [1, 2, 3, 4, 5] },
        { id: 'historical', name: 'Historical', bookIds: [6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17] },
        { id: 'poetic', name: 'Wisdom & Poetry', bookIds: [18, 19, 20, 21, 22] },
        { id: 'major-prophets', name: 'Major Prophets', bookIds: [23, 24, 25, 26, 27] },
        { id: 'minor-prophets', name: 'Minor Prophets', bookIds: [28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39] },
        { id: 'gospels', name: 'Gospels', bookIds: [40, 41, 42, 43] },
        { id: 'acts', name: 'Acts', bookIds: [44] },
        { id: 'pauline', name: 'Pauline Epistles', bookIds: [45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57] },
        { id: 'general', name: 'General Epistles', bookIds: [58, 59, 60, 61, 62, 63, 64, 65] },
        { id: 'revelation', name: 'Revelation', bookIds: [66] }
    ];

    // ============================================
    // MultiSelectCombo Component
    // ============================================

    /**
     * Creates a multi-select combo box with autocomplete and pills.
     * @param {HTMLElement} container - The container element
     * @param {Object} config - Configuration object
     * @param {Array} config.options - Array of {id, label, color?} objects
     * @param {Array} config.selected - Array of selected IDs
     * @param {string} config.placeholder - Input placeholder text
     * @param {Function} config.onChange - Callback when selection changes
     * @param {Function} config.getOptions - Optional function to get current options (for dynamic lists)
     */
    function createMultiSelectCombo(container, config) {
        const { placeholder = 'Type to search...', onChange } = config;
        let options = config.options || [];
        let selected = [...(config.selected || [])];
        let highlightedIndex = -1;
        let isOpen = false;

        // Create DOM structure
        container.innerHTML = `
            <div class="combo-wrapper">
                <div class="combo-input-area">
                    <div class="combo-pills"></div>
                    <input type="text" class="combo-input" placeholder="${placeholder}" autocomplete="off">
                </div>
                <div class="combo-dropdown" hidden>
                    <div class="combo-options"></div>
                </div>
            </div>
        `;

        const wrapper = container.querySelector('.combo-wrapper');
        const inputArea = container.querySelector('.combo-input-area');
        const pillsContainer = container.querySelector('.combo-pills');
        const input = container.querySelector('.combo-input');
        const dropdown = container.querySelector('.combo-dropdown');
        const optionsContainer = container.querySelector('.combo-options');

        function getFilteredOptions() {
            const currentOptions = config.getOptions ? config.getOptions() : options;
            const query = input.value.toLowerCase().trim();
            return currentOptions.filter(opt => {
                const notSelected = !selected.includes(String(opt.id));
                const matchesQuery = !query || opt.label.toLowerCase().includes(query);
                return notSelected && matchesQuery;
            });
        }

        function renderPills() {
            const currentOptions = config.getOptions ? config.getOptions() : options;
            const optionMap = {};
            currentOptions.forEach(opt => { optionMap[String(opt.id)] = opt; });

            pillsContainer.innerHTML = selected.map(id => {
                const opt = optionMap[String(id)];
                if (!opt) return '';
                const colorStyle = opt.color ? `style="--pill-color: ${opt.color}"` : '';
                const colorClass = opt.color ? ' combo-pill-colored' : '';
                return `
                    <span class="combo-pill${colorClass}" data-id="${opt.id}" ${colorStyle}>
                        ${escapeHtml(opt.label)}
                        <button type="button" class="combo-pill-remove" aria-label="Remove">&times;</button>
                    </span>
                `;
            }).join('');

            // Update placeholder visibility
            input.placeholder = selected.length === 0 ? placeholder : '';
        }

        function renderDropdown() {
            const filtered = getFilteredOptions();
            highlightedIndex = Math.min(highlightedIndex, filtered.length - 1);
            if (highlightedIndex < 0 && filtered.length > 0) highlightedIndex = 0;

            if (filtered.length === 0) {
                optionsContainer.innerHTML = '<div class="combo-no-options">No options</div>';
            } else {
                optionsContainer.innerHTML = filtered.map((opt, i) => {
                    const highlightClass = i === highlightedIndex ? ' highlighted' : '';
                    const colorDot = opt.color
                        ? `<span class="combo-option-dot" style="background:${opt.color}"></span>`
                        : '';
                    return `
                        <div class="combo-option${highlightClass}" data-id="${opt.id}" data-index="${i}">
                            ${colorDot}${escapeHtml(opt.label)}
                        </div>
                    `;
                }).join('');
            }
        }

        function openDropdown() {
            if (isOpen) return;
            isOpen = true;
            highlightedIndex = 0;
            renderDropdown();
            dropdown.hidden = false;
            wrapper.classList.add('combo-open');
        }

        function closeDropdown() {
            if (!isOpen) return;
            isOpen = false;
            dropdown.hidden = true;
            wrapper.classList.remove('combo-open');
            highlightedIndex = -1;
        }

        function selectOption(id) {
            if (!selected.includes(String(id))) {
                selected.push(String(id));
                input.value = '';
                renderPills();
                renderDropdown();
                onChange(selected);
            }
        }

        function removeOption(id) {
            selected = selected.filter(s => s !== String(id));
            renderPills();
            if (isOpen) renderDropdown();
            onChange(selected);
        }

        function scrollToHighlighted() {
            const highlighted = optionsContainer.querySelector('.combo-option.highlighted');
            if (highlighted) {
                highlighted.scrollIntoView({ block: 'nearest' });
            }
        }

        // Event: Click on input area to focus
        inputArea.addEventListener('click', (e) => {
            if (e.target.closest('.combo-pill-remove')) return;
            input.focus();
        });

        // Event: Input focus
        input.addEventListener('focus', () => {
            openDropdown();
        });

        // Event: Input typing
        input.addEventListener('input', () => {
            highlightedIndex = 0;
            renderDropdown();
            if (!isOpen) openDropdown();
        });

        // Event: Keyboard navigation
        input.addEventListener('keydown', (e) => {
            const filtered = getFilteredOptions();

            switch (e.key) {
                case 'ArrowDown':
                    e.preventDefault();
                    if (!isOpen) {
                        openDropdown();
                    } else if (highlightedIndex < filtered.length - 1) {
                        highlightedIndex++;
                        renderDropdown();
                        scrollToHighlighted();
                    }
                    break;
                case 'ArrowUp':
                    e.preventDefault();
                    if (highlightedIndex > 0) {
                        highlightedIndex--;
                        renderDropdown();
                        scrollToHighlighted();
                    }
                    break;
                case 'Enter':
                    e.preventDefault();
                    if (isOpen && highlightedIndex >= 0 && filtered[highlightedIndex]) {
                        selectOption(filtered[highlightedIndex].id);
                    }
                    break;
                case 'Escape':
                    e.preventDefault();
                    e.stopPropagation();
                    closeDropdown();
                    input.blur();
                    break;
                case 'Backspace':
                    if (input.value === '' && selected.length > 0) {
                        removeOption(selected[selected.length - 1]);
                    }
                    break;
            }
        });

        // Event: Click on dropdown option
        optionsContainer.addEventListener('click', (e) => {
            const option = e.target.closest('.combo-option');
            if (option && option.dataset.id) {
                selectOption(option.dataset.id);
                input.focus();
            }
        });

        // Event: Hover on dropdown option
        optionsContainer.addEventListener('mousemove', (e) => {
            const option = e.target.closest('.combo-option');
            if (option && option.dataset.index !== undefined) {
                const newIndex = parseInt(option.dataset.index);
                if (newIndex !== highlightedIndex) {
                    highlightedIndex = newIndex;
                    renderDropdown();
                }
            }
        });

        // Event: Click on pill remove button
        pillsContainer.addEventListener('click', (e) => {
            const removeBtn = e.target.closest('.combo-pill-remove');
            if (removeBtn) {
                const pill = removeBtn.closest('.combo-pill');
                if (pill && pill.dataset.id) {
                    removeOption(pill.dataset.id);
                }
            }
        });

        // Event: Click outside to close
        document.addEventListener('click', (e) => {
            if (!wrapper.contains(e.target)) {
                closeDropdown();
            }
        });

        // Public API
        return {
            setOptions(newOptions) {
                options = newOptions;
                if (isOpen) renderDropdown();
            },
            setSelected(newSelected) {
                selected = [...newSelected].map(String);
                renderPills();
                if (isOpen) renderDropdown();
            },
            getSelected() {
                return [...selected];
            },
            render() {
                renderPills();
                if (isOpen) renderDropdown();
            },
            clear() {
                selected = [];
                input.value = '';
                renderPills();
                if (isOpen) renderDropdown();
            }
        };
    }

    // ============================================
    // API Functions
    // ============================================
    async function fetchVerses(fromId, count) {
        const response = await fetch(`/api/verses?from=${fromId}&count=${count}`);
        if (!response.ok) throw new Error('Failed to fetch verses');
        return response.json();
    }

    async function fetchVerse(id) {
        const response = await fetch(`/api/verses/${id}`);
        if (!response.ok) throw new Error('Failed to fetch verse');
        return response.json();
    }

    async function fetchBooks() {
        const response = await fetch('/api/books');
        if (!response.ok) throw new Error('Failed to fetch books');
        return response.json();
    }

    async function fetchChapters(bookId) {
        const response = await fetch(`/api/books/${bookId}/chapters`);
        if (!response.ok) throw new Error('Failed to fetch chapters');
        return response.json();
    }

    async function searchBible(query, limit = 50) {
        const response = await fetch(
            `/api/search?q=${encodeURIComponent(query)}&limit=${encodeURIComponent(limit)}`
        );
        if (!response.ok) throw new Error('Search failed');
        return response.json();
    }

    /** Lightweight id-only search for Matching Passages overlap (large hit windows). */
    async function searchBibleIds(query, limit = 2000) {
        const response = await fetch(
            `/api/search/ids?q=${encodeURIComponent(query)}&limit=${encodeURIComponent(limit)}`
        );
        if (!response.ok) throw new Error('Search ids failed');
        return response.json();
    }

    async function parseReference(ref) {
        const response = await fetch(`/api/reference?ref=${encodeURIComponent(ref)}`);
        if (!response.ok) throw new Error('Failed to parse reference');
        return response.json();
    }

    async function fetchNavigation(currentId) {
        const response = await fetch(`/api/navigate/${currentId}`);
        if (!response.ok) throw new Error('Failed to fetch navigation');
        return response.json();
    }

    // ============================================
    // Rendering Functions
    // ============================================
    
    /**
     * Render verses with chapter headers for measurement purposes.
     * This generates the same HTML that will be displayed, including chapter headers.
     */
    function renderVersesWithHeaders(verses, prevVerse = null) {
        let html = '';
        for (let i = 0; i < verses.length; i++) {
            const verse = verses[i];
            const prev = i === 0 ? prevVerse : verses[i - 1];
            html += createVerseWithHeaderHTML(verse, false, prev);
        }
        return html;
    }

    /**
     * Binary-search how many of `verses` fit in the reading area.
     * fromEnd=false keeps a prefix (first verse fixed); fromEnd=true keeps a
     * suffix (last verse fixed). renderFn(verses) must produce the exact
     * display HTML so measurement matches rendering.
     */
    function measureFittingVerses(verses, fromEnd, renderFn) {
        const container = elements.readingArea;
        // Fractional dimensions, not clientWidth/clientHeight: those round to
        // integers, and a sub-pixel width/height difference can change where
        // column breaks fall (a verse overflowing by 0.5px is pushed whole
        // into a clipped overflow column by break-inside: avoid).
        const rect = container.getBoundingClientRect();
        state.lastMeasuredWidth = rect.width;
        state.lastMeasuredHeight = rect.height;

        // Create a hidden measuring container that mirrors the reading area
        // exactly, including its fixed height and column-fill: auto, so
        // fragmentation happens identically. Content that doesn't fit spills
        // into extra columns to the right (or below, for an unbreakable block
        // taller than a column) — both show up as scroll overflow.
        const containerStyle = getComputedStyle(container);
        const measureContainer = document.createElement('div');
        measureContainer.style.cssText = `
            position: absolute;
            visibility: hidden;
            width: ${rect.width}px;
            height: ${rect.height}px;
            column-count: ${containerStyle.columnCount};
            column-gap: ${containerStyle.columnGap};
            column-fill: auto;
            overflow: hidden;
            text-align: justify;
            hyphens: auto;
            -webkit-hyphens: auto;
            font-family: ${containerStyle.fontFamily};
            font-size: ${containerStyle.fontSize};
            line-height: var(--line-height, ${containerStyle.lineHeight});
        `;
        // line-height must stay the unitless number, NOT the computed px
        // value: a number inherits as a ratio, so descendants with a larger
        // font-size (chapter headers, 1.15rem) resolve a taller line box.
        // Copying the computed "35px" froze that px value for every child,
        // making headers 5.25px shorter in the mirror than on the page —
        // enough to overcount by one verse and clip a chapter's verse 1.
        document.body.appendChild(measureContainer);

        let low = 1;
        let high = verses.length;
        let fittingVerses = [];

        try {
            while (low <= high) {
                const mid = Math.floor((low + high) / 2);
                const testVerses = fromEnd ? verses.slice(-mid) : verses.slice(0, mid);

                measureContainer.innerHTML = renderFn(testVerses);

                const fits = measureContainer.scrollWidth <= measureContainer.clientWidth &&
                    measureContainer.scrollHeight <= measureContainer.clientHeight;
                if (fits) {
                    fittingVerses = testVerses;
                    low = mid + 1;
                } else {
                    high = mid - 1;
                }
            }
        } finally {
            document.body.removeChild(measureContainer);
        }

        // Ensure at least one verse (the anchored one)
        if (fittingVerses.length === 0 && verses.length > 0) {
            fittingVerses = fromEnd ? [verses[verses.length - 1]] : [verses[0]];
        }

        return fittingVerses;
    }

    /**
     * Calculate how many verses fit in the current viewport starting from a given verse.
     * Accounts for chapter headers that appear before the first verse of each chapter.
     */
    async function calculatePageVerses(startVerseId) {
        // Fetch a batch of verses to test
        const batchSize = 100; // Fetch more than we need
        const data = await fetchVerses(startVerseId, batchSize);

        if (data.verses.length === 0) {
            return { verses: [], fits: 0 };
        }

        const fittingVerses = measureFittingVerses(data.verses, false, renderVersesWithHeaders);
        return { verses: fittingVerses, total: data.total };
    }

    /**
     * Calculate how many verses fit in the current viewport ENDING at a given verse.
     * Used for backward navigation - finds the maximum verses that fit while ensuring
     * the specified verse is the LAST verse on the page.
     */
    async function calculatePageVersesEndingAt(endVerseId) {
        // Fetch verses ending at the target (fetch backwards)
        const batchSize = 100;
        const startId = Math.max(1, endVerseId - batchSize + 1);
        const count = endVerseId - startId + 1;

        const data = await fetchVerses(startId, count);
        const allVerses = data.verses;

        if (allVerses.length === 0) {
            return { verses: [], total: data.total };
        }

        // Make sure we include the target verse
        const targetIndex = allVerses.findIndex(v => v.id === endVerseId);
        if (targetIndex === -1) {
            return { verses: [], total: data.total };
        }

        // Trim to only include verses up to and including target
        const versesEndingAtTarget = allVerses.slice(0, targetIndex + 1);

        const fittingVerses = measureFittingVerses(versesEndingAtTarget, true, renderVersesWithHeaders);
        return { verses: fittingVerses, total: data.total };
    }

    const COPY_ICON_SVG = '<svg viewBox="0 0 16 16" width="13" height="13" aria-hidden="true">' +
        '<rect x="5.5" y="5.5" width="9" height="9" rx="1.5" fill="none" stroke="currentColor" stroke-width="1.3"/>' +
        '<path d="M3.5 10.5h-1a1 1 0 0 1-1-1v-7a1 1 0 0 1 1-1h7a1 1 0 0 1 1 1v1" fill="none" stroke="currentColor" stroke-width="1.3"/>' +
        '</svg>';

    /**
     * Create HTML for a chapter header.
     * Format: "Chapter N" for most books, or "Psalm N" for Psalms
     */
    function createChapterHeaderHTML(verse) {
        // Check for both "Psalm" and "Psalms" to handle different data formats
        const isPsalm = verse.book === 'Psalms' || verse.book === 'Psalm';
        const headerText = isPsalm ? `Psalm ${verse.chapter}` : `Chapter ${verse.chapter}`;
        const label = `${isPsalm ? 'Psalm' : verse.book} ${verse.chapter}`;
        const hasNote = !!state.chapterNotes[chapterKey(verse.bookId, verse.chapter)];
        return `<div class="chapter-header"><span>${headerText}</span><button class="chapter-note-btn${hasNote ? ' has-note' : ''}"
            data-book-id="${verse.bookId}" data-chapter="${verse.chapter}" data-label="${escapeHtml(label)}"
            title="${hasNote ? 'Edit chapter note (c)' : 'Add chapter note (c)'}" aria-label="Chapter note for ${escapeHtml(label)}">&#9998;</button></div>`;
    }

    /**
     * Create HTML for a single verse.
     */
    function isVerseMemorized(verseId) {
        return state.memorizedEntries.some(
            e => verseId >= e.fromVerseId && verseId <= e.toVerseId
        );
    }

    function createVerseHTML(verse, isCurrent) {
        const currentClass = isCurrent ? ' current' : '';
        const savedClass = isVerseSaved(verse.id) ? ' saved' : '';
        const memorizedClass = isVerseMemorized(verse.id) ? ' memorized' : '';

        let tagDotsHtml = '';
        const savedVerse = state.savedVerses[verse.id];
        if (savedVerse && savedVerse.tagIds.length > 0) {
            tagDotsHtml = '<span class="verse-tag-dots">' +
                savedVerse.tagIds.slice(0, 5).map(tid => {
                    const tag = state.tags[tid];
                    const color = tag ? TAG_COLORS[tag.colorIndex] : TAG_COLOR_DEFAULT;
                    return `<span class="verse-tag-dot" style="background:${color}"></span>`;
                }).join('') +
                '</span>';
        }

        // Absolutely positioned, so it never adds flow height/width — the
        // page-fit measurement mirror always renders isCurrent=false and must
        // never see a different box size for the current verse (see
        // subpixel-pagination-clipping memory: measurement must match render).
        const copyBtnHtml = isCurrent
            ? `<button class="verse-copy-btn" data-verse-id="${verse.id}" title="Copy verse (y)" aria-label="Copy verse text and reference">${COPY_ICON_SVG}</button>`
            : '';

        const ciAttr = (verse._ci != null) ? ` data-ci="${verse._ci}"` : '';
        return `
            <p class="verse${currentClass}${savedClass}${memorizedClass}" data-verse-id="${verse.id}"${ciAttr}>
                ${tagDotsHtml}
                <span class="verse-number">${verse.verse}</span>
                <span class="verse-text">${escapeHtml(verse.text)}</span>
                ${copyBtnHtml}
            </p>
        `;
    }

    /**
     * Create HTML for a verse, optionally preceded by a chapter header.
     * Includes chapter header if the verse is the first verse of a chapter.
     */
    function createVerseWithHeaderHTML(verse, isCurrent, prevVerse) {
        let html = '';
        
        // Add chapter header if this is the first verse of a chapter
        // (verse number is 1, or this is a different chapter than the previous verse)
        const isFirstVerseOfChapter = verse.verse === 1 ||
            (prevVerse && (prevVerse.chapter !== verse.chapter || prevVerse.book !== verse.book));
        
        if (isFirstVerseOfChapter) {
            html += createChapterHeaderHTML(verse);
        }
        
        html += createVerseHTML(verse, isCurrent);
        return html;
    }

    /** Copy a verse's text and reference (e.g. "…text…" — Psalm 53:3) to the clipboard. */
    async function copyVerseToClipboard(verseId) {
        const verse = state.pageVerses.find(v => v.id === verseId);
        if (!verse) return;
        const isPsalm = verse.book === 'Psalms' || verse.book === 'Psalm';
        const label = `${isPsalm ? 'Psalm' : verse.book} ${verse.chapter}:${verse.verse}`;
        const formatted = `"${verse.text}" — ${label}`;
        try {
            await navigator.clipboard.writeText(formatted);
            showToast('Verse copied');
        } catch (err) {
            console.error('Failed to copy verse:', err);
            showToast('Failed to copy verse');
        }
    }

    // ─── Scoped Reader (collection or focused passage) ────────────────────────
    // While state.collection is set, the reader pages over an ordered verse
    // array instead of sequential global ids. Each verse carries _ci (index
    // in the scoped list) since verse ids may repeat in collections.
    // kind:'passage' is a single first-class passage; kind:'collection' is
    // an ordered group of passages under one label.

    function passageDisplayLabel(p) {
        if (!p) return 'Passage';
        const title = p.title && String(p.title).trim();
        return title || p.reference || 'Passage';
    }

    /** Sub-heading shown at the start of each passage in a collection. */
    function createPassageHeaderHTML(reference) {
        return `<div class="chapter-header passage-header"><span>${escapeHtml(reference)}</span></div>`;
    }

    /** Flatten API collection passages into the scoped-reader verse list. */
    function flattenCollectionPassages(apiPassages) {
        const verses = [];
        const passageStarts = [];
        const passageRefs = {};
        (apiPassages || []).forEach(p => {
            const start = verses.length;
            passageStarts.push(start);
            passageRefs[start] = passageDisplayLabel(p);
            (p.verses || []).forEach(v => verses.push({ ...v }));
        });
        verses.forEach((v, i) => { v._ci = i; });
        return { verses, passageStarts, passageRefs };
    }

    /**
     * Render collection verses with passage sub-headings before each verse that
     * starts a passage. Used for both measurement (markCurrent=false) and
     * display so pagination is exact. No chapter headers in this mode.
     */
    function renderCollectionVersesWithHeaders(verses, markCurrent = false) {
        const col = state.collection;
        let html = '';
        for (const verse of verses) {
            if (col.passageRefs[verse._ci] !== undefined) {
                html += createPassageHeaderHTML(col.passageRefs[verse._ci]);
            }
            html += createVerseHTML(verse, markCurrent && verse._ci === col.currentIndex);
        }
        return html;
    }

    /** Load the collection page starting at the given collection index. */
    async function loadCollectionPage(startIndex) {
        const col = state.collection;
        startIndex = Math.max(0, Math.min(startIndex, col.verses.length - 1));
        loadGeneration++; // invalidate any in-flight verse-mode load
        state.isLoading = true;
        try {
            const candidates = col.verses.slice(startIndex, startIndex + 100);
            const fitting = measureFittingVerses(candidates, false,
                vs => renderCollectionVersesWithHeaders(vs, false));
            state.pageVerses = fitting;
            col.pageStartIndex = startIndex;
            const lastCi = fitting[fitting.length - 1]._ci;
            if (col.currentIndex < startIndex || col.currentIndex > lastCi) {
                col.currentIndex = startIndex;
            }
            state.currentVerseId = col.verses[col.currentIndex].id;
            renderPage();
        } finally {
            state.isLoading = false;
            hideLoading();
        }
    }

    /** Load the collection page ending at the given collection index (backward paging). */
    async function loadCollectionPageEndingAt(endIndex) {
        const col = state.collection;
        endIndex = Math.max(0, Math.min(endIndex, col.verses.length - 1));
        loadGeneration++; // invalidate any in-flight verse-mode load
        state.isLoading = true;
        try {
            const from = Math.max(0, endIndex - 99);
            const candidates = col.verses.slice(from, endIndex + 1);
            const fitting = measureFittingVerses(candidates, true,
                vs => renderCollectionVersesWithHeaders(vs, false));
            state.pageVerses = fitting;
            col.pageStartIndex = fitting[0]._ci;
            col.currentIndex = endIndex;
            state.currentVerseId = col.verses[endIndex].id;
            renderPage();
        } finally {
            state.isLoading = false;
            hideLoading();
        }
    }

    function renderCollectionPage() {
        const col = state.collection;
        elements.readingArea.innerHTML = renderCollectionVersesWithHeaders(state.pageVerses, true);

        // Explicit Back control — × alone was too easy to miss after following a note link
        elements.chapterTitle.innerHTML =
            `<button type="button" class="scoped-back-btn" title="Back (Esc)" aria-label="Back">← Back</button>` +
            `<span class="scoped-title-label">${escapeHtml(col.label)}</span>`;

        if (col.kind === 'collection') {
            const passageIdx = col.passageStarts.filter(s => s <= col.currentIndex).length;
            elements.pageInfo.textContent = `Passage ${passageIdx} of ${col.passageStarts.length}`;
        } else {
            elements.pageInfo.textContent = col.label;
        }

        updateCurrentReference();
    }

    /** Move the current-verse cursor to a collection index, paging if needed. */
    async function goToCollectionIndex(i) {
        const col = state.collection;
        i = Math.max(0, Math.min(i, col.verses.length - 1));
        if (state.pageVerses.some(v => v._ci === i)) {
            col.currentIndex = i;
            state.currentVerseId = col.verses[i].id;
            renderPage();
        } else {
            col.currentIndex = i;
            await loadCollectionPage(i);
        }
    }

    async function collectionNextVerse() {
        const col = state.collection;
        if (col.currentIndex >= col.verses.length - 1) return;
        const next = col.currentIndex + 1;
        if (state.pageVerses.some(v => v._ci === next)) {
            col.currentIndex = next;
            state.currentVerseId = col.verses[next].id;
            renderPage();
        } else {
            col.currentIndex = next;
            await loadCollectionPage(next);
        }
    }

    async function collectionPrevVerse() {
        const col = state.collection;
        if (col.currentIndex <= 0) return;
        const prev = col.currentIndex - 1;
        if (state.pageVerses.some(v => v._ci === prev)) {
            col.currentIndex = prev;
            state.currentVerseId = col.verses[prev].id;
            renderPage();
        } else {
            await loadCollectionPageEndingAt(prev);
        }
    }

    /** Jump to the start of the next passage. */
    async function nextPassage() {
        const col = state.collection;
        const next = col.passageStarts.find(s => s > col.currentIndex);
        if (next !== undefined) await goToCollectionIndex(next);
    }

    /** Jump to the start of the current passage, or the previous one if already there. */
    async function prevPassage() {
        const col = state.collection;
        const starts = col.passageStarts;
        const currentStart = [...starts].reverse().find(s => s <= col.currentIndex);
        if (col.currentIndex > currentStart) {
            await goToCollectionIndex(currentStart);
        } else {
            const prev = [...starts].reverse().find(s => s < currentStart);
            if (prev !== undefined) await goToCollectionIndex(prev);
        }
    }

    /**
     * Enter the scoped collection reader. Returns true on success.
     * push=false when restoring from the URL (init/popstate).
     */
    async function enterCollectionMode(id, { push = true } = {}) {
        showLoading();
        let data;
        try {
            data = await fetchCollectionVerses(id);
        } catch (err) {
            hideLoading();
            console.error('Failed to load collection:', err);
            showToast(err.message.includes('401')
                ? 'Sign in to view collections'
                : 'Collection not found');
            return false;
        }
        if (state.audioPlaying) stopAudio();

        const flat = flattenCollectionPassages(data.passages);
        if (!flat.verses.length) {
            hideLoading();
            showToast('Collection has no passages');
            return false;
        }

        rememberScopedReturn(push);
        state.collection = {
            kind: 'collection',
            id: data.id,
            label: data.label,
            verses: flat.verses,
            passageStarts: flat.passageStarts,
            passageRefs: flat.passageRefs,
            currentIndex: 0,
            pageStartIndex: 0
        };
        state.currentVerseId = flat.verses[0].id;
        if (push) history.pushState({ collectionId: data.id }, '', `/read/collection/${data.id}`);
        await loadCollectionPage(0);
        return true;
    }

    /**
     * Enter the focused passage reader for a first-class Passage.
     * Resolves to a range session (same chrome as [v=…] links).
     */
    async function enterPassageMode(id, { push = true } = {}) {
        showLoading();
        let data;
        try {
            data = await libApi(`/api/passages/${id}/verses`);
        } catch (err) {
            hideLoading();
            console.error('Failed to load passage:', err);
            showToast(err.message.includes('401')
                ? 'Sign in to view passages'
                : 'Passage not found');
            return false;
        }
        return enterRangeModeFromPayload(data, {
            push,
            url: push ? `/read/passage/${id}` : null,
            kind: 'passage',
            id
        });
    }

    /**
     * Focused range reader for portable [v=…] links. No Passage row required.
     * @param {string} vBody — e.g. "26136-26138" or full "[v=26136]"
     */
    async function enterRangeMode(vBody, { push = true } = {}) {
        showLoading();
        const param = String(vBody).replace(/^\[?v=/i, '').replace(/\]$/, '');
        let data;
        try {
            const res = await fetch(`/api/ranges?v=${encodeURIComponent(param)}`);
            if (!res.ok) throw new Error(String(res.status));
            data = await res.json();
        } catch (err) {
            hideLoading();
            console.error('Failed to load range:', err);
            showToast('Could not open that scripture range');
            return false;
        }
        const matched = findPassageByNaturalKey(data.naturalKey);
        if (matched) {
            data.title = matched.title || data.title;
        }
        const url = `/read/range?v=${encodeURIComponent(data.v)}`;
        return enterRangeModeFromPayload(data, {
            push,
            url: push ? url : null,
            kind: 'range',
            id: data.v
        });
    }

    async function enterRangeModeFromPayload(data, { push, url, kind, id }) {
        if (state.audioPlaying) stopAudio();

        const label = passageDisplayLabel(data);
        const verses = (data.verses || []).map((v, i) => ({ ...v, _ci: i }));
        if (!verses.length) {
            hideLoading();
            showToast('Range has no verses');
            return false;
        }

        rememberScopedReturn(push);
        state.collection = {
            kind: kind || 'range',
            id: id != null ? id : data.v,
            label,
            verses,
            passageStarts: [0],
            passageRefs: { 0: label },
            rangeV: data.v,
            naturalKey: data.naturalKey,
            currentIndex: 0,
            pageStartIndex: 0
        };
        state.currentVerseId = verses[0].id;
        if (push && url) history.pushState({ rangeV: data.v, kind }, '', url);
        await loadCollectionPage(0);
        return true;
    }

    /**
     * Snapshot reading position (and any note return already staged) before
     * entering scoped mode.
     * push=false on a fresh deep link clears return; push=false while already
     * in scoped mode (popstate between scoped URLs) keeps the original return.
     */
    function rememberScopedReturn(push) {
        if (!push) {
            if (!state.collection) {
                state.scopedReturn = null;
            }
            return;
        }
        const stagedNote = state.scopedReturn?.note || null;
        // Prefer the verse the user was reading before this jump — not a verse
        // already overwritten by a prior scoped session.
        const verseId = (!state.collection && state.currentVerseId)
            ? state.currentVerseId
            : (state.scopedReturn?.verseId || state.currentVerseId);
        state.scopedReturn = {
            verseId,
            note: stagedNote,
            historyPushed: true
        };
    }

    /** Capture open note editor so Back can reopen it after a scripture jump. */
    function stageNoteReturnFromOpenEditors() {
        if (state.noteEditorOpen && state.noteEditorVerseId) {
            state.scopedReturn = {
                verseId: state.noteEditorVerseId,
                note: { type: 'verse', verseId: state.noteEditorVerseId },
                historyPushed: true
            };
            return;
        }
        if (state.chapterNoteEditorOpen && state.chapterNoteEditorTarget) {
            const t = state.chapterNoteEditorTarget;
            state.scopedReturn = {
                verseId: state.currentVerseId,
                note: { ...t, type: t.type || 'chapter' },
                historyPushed: true
            };
        }
    }

    async function restoreScopedNote(note) {
        if (!note) return;
        if (note.type === 'verse' && note.verseId) {
            await openNoteEditor(note.verseId);
        } else if (note.type === 'chapter' || note.type === 'book') {
            openChapterNoteEditor(note);
        }
    }

    /**
     * Leave scoped reader; restore prior reading position and optional note.
     * When we entered via pushState, ← Back / Esc use history.back() so the
     * browser Back button and in-app Back share the same restore path.
     */
    async function exitCollectionMode({ push = true } = {}) {
        if (!state.collection) return;

        if (push && state.scopedReturn?.historyPushed) {
            history.back(); // popstate → exitCollectionMode({ push: false })
            return;
        }

        const ret = state.scopedReturn;
        state.collection = null;
        state.scopedReturn = null;
        const verseId = (ret && ret.verseId) || state.currentVerseId;

        if (push) {
            // Deep-linked scoped session (no return entry to pop)
            history.replaceState({}, '', '/read');
        }
        await goToVerse(verseId);
        await restoreScopedNote(ret && ret.note);
    }

    /**
     * Render the current page of verses with inline chapter headers.
     */
    function renderPage() {
        if (state.collection) {
            renderCollectionPage();
            return;
        }

        let html = '';

        // Render each verse, adding chapter headers where appropriate
        for (let i = 0; i < state.pageVerses.length; i++) {
            const verse = state.pageVerses[i];
            const prevVerse = i === 0 ? null : state.pageVerses[i - 1];
            const isCurrent = verse.id === state.currentVerseId;
            
            html += createVerseWithHeaderHTML(verse, isCurrent, prevVerse);
        }
        
        elements.readingArea.innerHTML = html;
        
        // Update chapter title in header to show book name only (chapters now inline)
        if (state.pageVerses.length > 0) {
            const firstVerse = state.pageVerses[0];
            const lastVerse = state.pageVerses[state.pageVerses.length - 1];

            // If spanning multiple chapters, show range; otherwise just the book
            let titleText;
            if (firstVerse.chapter === lastVerse.chapter && firstVerse.book === lastVerse.book) {
                titleText = `${firstVerse.book} ${firstVerse.chapter}`;
            } else if (firstVerse.book === lastVerse.book) {
                titleText = `${firstVerse.book} ${firstVerse.chapter}–${lastVerse.chapter}`;
            } else {
                titleText = `${firstVerse.book} — ${lastVerse.book}`;
            }

            // Book-note pencil, scoped to the current verse's book (resolved at click time)
            const bookRef = getCurrentBookRef();
            const hasBookNote = bookRef && !!state.bookNotes[bookRef.bookId];
            const bookTitle = bookRef ? bookRef.bookName : firstVerse.book;
            elements.chapterTitle.innerHTML = `${escapeHtml(titleText)}<button class="chapter-note-btn book-note-btn${hasBookNote ? ' has-note' : ''}"
                title="${hasBookNote ? `Edit ${escapeHtml(bookTitle)} book note (B)` : `Add ${escapeHtml(bookTitle)} book note (B)`}"
                aria-label="Book note for ${escapeHtml(bookTitle)}">&#9998;</button>`;
        }
        
        updateCurrentReference();
        updatePageInfo();
    }

    /**
     * Update the current reference display.
     */
    function updateCurrentReference() {
        const verse = state.pageVerses.find(v => v.id === state.currentVerseId);
        if (verse) {
            elements.currentReference.textContent = `${verse.book} ${verse.chapter}:${verse.verse}`;
        }
    }

    /**
     * Update page info display.
     */
    function updatePageInfo() {
        if (state.pageVerses.length > 0) {
            const first = state.pageVerses[0];
            const last = state.pageVerses[state.pageVerses.length - 1];
            elements.pageInfo.textContent = 
                `${first.book} ${first.chapter}:${first.verse} — ${last.book} ${last.chapter}:${last.verse}`;
        }
    }

    /**
     * Escape HTML special characters.
     */
    function escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    // ============================================
    // Navigation Functions
    // ============================================

    /**
     * Load and display a page starting from the given verse.
     */
    /**
     * Monotonic token for page loads. Every page-load intent bumps it; an
     * in-flight load whose token is stale by the time its fetch resolves
     * must not write state. Guards background remeasures (auth library
     * load, save toggles) and rapid paging against overwriting a newer
     * navigation with an older page. Collection page loads bump it too so
     * a stale verse-mode load can't stomp a page in collection mode.
     */
    let loadGeneration = 0;

    async function loadPage(startVerseId) {
        const gen = ++loadGeneration;
        state.isLoading = true;

        try {
            const result = await calculatePageVerses(startVerseId);
            if (gen !== loadGeneration) return; // superseded by newer navigation
            state.pageVerses = result.verses;
            state.totalVerses = result.total || state.totalVerses;
            
            if (state.pageVerses.length > 0) {
                state.pageStartVerseId = state.pageVerses[0].id;
                
                // If current verse is not on this page, set it to first verse
                const isCurrentOnPage = state.pageVerses.some(v => v.id === state.currentVerseId);
                if (!isCurrentOnPage) {
                    state.currentVerseId = state.pageStartVerseId;
                }
            }
            
            renderPage();
            saveState();
            updateDropdowns();
        } finally {
            state.isLoading = false;
            hideLoading();
        }
    }

    /**
     * Debounced reload when the reading area's size changes. Fed by the
     * window resize event, a ResizeObserver on the reading area (which also
     * catches size changes that don't fire a window resize event — e.g.
     * embedded/preview browsers that lay the page out at 0×0 first and
     * attach the real viewport dimensions only after the app initializes),
     * and one post-init check from init() itself.
     */
    let relayoutTimeout;
    function scheduleRelayout() {
        clearTimeout(relayoutTimeout);
        relayoutTimeout = setTimeout(() => {
            const area = elements.readingArea;
            // Skip until init() has loaded the first page — the observer's
            // initial callback fires before then and would race it, loading
            // pageStartVerseId's default (verse 1) over the user's saved
            // position. init() re-runs this check once the flag is set, so
            // a size transition that lands here early is not lost.
            if (!state.initialPageLoaded) return;
            // Skip while hidden/zero-sized or if nothing actually changed.
            // Compare fractional dimensions: clientWidth/clientHeight round
            // to integers, and a sub-pixel size change can still move column
            // break points (see measureFittingVerses).
            const rect = area.getBoundingClientRect();
            if (rect.width === 0 || rect.height === 0) return;
            if (rect.width === state.lastMeasuredWidth &&
                rect.height === state.lastMeasuredHeight) return;
            if (state.collection) {
                loadCollectionPage(state.collection.pageStartIndex);
            } else {
                loadPage(state.pageStartVerseId);
            }
        }, 200);
    }

    /**
     * Go to a specific verse (loads page containing it).
     */
    async function goToVerse(verseId) {
        // Any global navigation leaves collection mode
        if (state.collection) {
            state.collection = null;
            history.pushState({}, '', '/read');
        }
        verseId = Math.max(1, Math.min(verseId, state.totalVerses));
        state.currentVerseId = verseId;
        await loadPage(verseId);
    }

    /**
     * Move to next verse (within page or turn page).
     * @param {boolean} autoAdvance - true if called from audio auto-advance (skip restart)
     */
    async function nextVerse(autoAdvance = false) {
        if (state.collection) { await collectionNextVerse(); return; }

        const wasPlaying = !autoAdvance && state.audioPlaying;
        if (wasPlaying) stopAudio();

        const currentIndex = state.pageVerses.findIndex(v => v.id === state.currentVerseId);

        if (currentIndex < state.pageVerses.length - 1) {
            // Move within page
            state.currentVerseId = state.pageVerses[currentIndex + 1].id;
            renderPage();
            saveState();
        } else if (state.currentVerseId < state.totalVerses) {
            // Turn to next page
            await goToVerse(state.currentVerseId + 1);
        }

        if (wasPlaying) restartAudioIfPlaying(wasPlaying);
    }

    /**
     * Move to previous verse (within page or turn page).
     */
    async function prevVerse() {
        if (state.collection) { await collectionPrevVerse(); return; }

        const wasPlaying = state.audioPlaying;
        if (wasPlaying) stopAudio();

        const currentIndex = state.pageVerses.findIndex(v => v.id === state.currentVerseId);

        if (currentIndex > 0) {
            // Move within page
            state.currentVerseId = state.pageVerses[currentIndex - 1].id;
            renderPage();
            saveState();
        } else if (state.currentVerseId > 1) {
            // Need to load previous page and set current to last verse
            const prevVerseId = state.currentVerseId - 1;
            state.currentVerseId = prevVerseId;

            // Load page that would contain the previous verse
            // This is tricky - we need to find where the page would start
            await loadPageEndingAt(prevVerseId);
        }

        if (wasPlaying) restartAudioIfPlaying(wasPlaying);
    }

    /**
     * Load a page that ends with the given verse as the last verse.
     * Used for backward navigation - the target verse should be the last verse on the page.
     * This mimics flipping to the previous page in a physical book.
     */
    async function loadPageEndingAt(targetVerseId) {
        const gen = ++loadGeneration;
        state.isLoading = true;

        try {
            // Use the dedicated function that calculates verses ending at the target
            const result = await calculatePageVersesEndingAt(targetVerseId);
            if (gen !== loadGeneration) return; // superseded by newer navigation

            if (result.verses.length > 0) {
                state.pageVerses = result.verses;
                state.pageStartVerseId = result.verses[0].id;
                state.currentVerseId = targetVerseId;
                state.totalVerses = result.total || state.totalVerses;
                
                renderPage();
                saveState();
                updateDropdowns();
            }
        } finally {
            state.isLoading = false;
            hideLoading();
        }
    }

    /**
     * Re-measure and re-render the current page in place (same first verse).
     * Required instead of a bare renderPage() whenever state that affects
     * verse layout changes after the page was measured — e.g. saved verses
     * arriving from the API (.saved adds left padding) or memorization
     * toggles (.memorized adds an inline diamond). A bare re-render would
     * reflow the columns without re-checking what still fits, silently
     * clipping the last verse(s) into an invisible overflow column.
     */
    async function remeasureCurrentPage() {
        if (state.collection) {
            await loadCollectionPage(state.collection.pageStartIndex);
        } else if (state.pageVerses.length > 0) {
            await loadPage(state.pageStartVerseId);
        } else {
            renderPage();
        }
    }

    /**
     * Turn to next page.
     */
    async function nextPage() {
        if (state.pageVerses.length === 0) return;

        if (state.collection) {
            const col = state.collection;
            const lastCi = state.pageVerses[state.pageVerses.length - 1]._ci;
            if (lastCi < col.verses.length - 1) {
                col.currentIndex = lastCi + 1;
                await loadCollectionPage(lastCi + 1);
            }
            return;
        }

        const wasPlaying = state.audioPlaying;
        if (wasPlaying) stopAudio();

        const lastVerse = state.pageVerses[state.pageVerses.length - 1];
        if (lastVerse.id < state.totalVerses) {
            await goToVerse(lastVerse.id + 1);
        }

        if (wasPlaying) restartAudioIfPlaying(wasPlaying);
    }

    /**
     * Turn to previous page.
     */
    async function prevPage() {
        if (state.collection) {
            if (state.collection.pageStartIndex > 0) {
                await loadCollectionPageEndingAt(state.collection.pageStartIndex - 1);
            }
            return;
        }

        const wasPlaying = state.audioPlaying;
        if (wasPlaying) stopAudio();

        if (state.pageStartVerseId > 1) {
            await loadPageEndingAt(state.pageStartVerseId - 1);
        }

        if (wasPlaying) restartAudioIfPlaying(wasPlaying);
    }

    /**
     * Go to next chapter.
     */
    async function nextChapter() {
        const wasPlaying = state.audioPlaying;
        if (wasPlaying) stopAudio();

        try {
            const nav = await fetchNavigation(state.currentVerseId);
            if (nav.nextChapter) {
                await goToVerse(nav.nextChapter);
                if (wasPlaying) startAudio();
            }
        } catch (e) {
            console.error('Failed to navigate to next chapter', e);
        }
    }

    /**
     * Go to previous chapter.
     */
    async function prevChapter() {
        const wasPlaying = state.audioPlaying;
        if (wasPlaying) stopAudio();

        try {
            const nav = await fetchNavigation(state.currentVerseId);
            if (nav.prevChapter) {
                await goToVerse(nav.prevChapter);
                if (wasPlaying) startAudio();
            }
        } catch (e) {
            console.error('Failed to navigate to previous chapter', e);
        }
    }

    /**
     * Go to next book.
     */
    async function nextBook() {
        const wasPlaying = state.audioPlaying;
        if (wasPlaying) stopAudio();

        try {
            const nav = await fetchNavigation(state.currentVerseId);
            if (nav.nextBook) {
                await goToVerse(nav.nextBook);
                if (wasPlaying) startAudio();
            }
        } catch (e) {
            console.error('Failed to navigate to next book', e);
        }
    }

    /**
     * Go to previous book.
     */
    async function prevBook() {
        const wasPlaying = state.audioPlaying;
        if (wasPlaying) stopAudio();

        try {
            const nav = await fetchNavigation(state.currentVerseId);
            if (nav.prevBook) {
                await goToVerse(nav.prevBook);
                if (wasPlaying) startAudio();
            }
        } catch (e) {
            console.error('Failed to navigate to previous book', e);
        }
    }

    // ============================================
    // Dropdown Controls
    // ============================================

    async function initDropdowns() {
        try {
            state.books = await fetchBooks();
            
            // Populate book dropdown
            elements.bookSelect.innerHTML = '<option value="">Book</option>' +
                state.books.map(b => `<option value="${b.id}">${b.name}</option>`).join('');
            
            elements.bookSelect.addEventListener('change', onBookChange);
            elements.chapterSelect.addEventListener('change', onChapterChange);
            elements.verseSelect.addEventListener('change', onVerseChange);
        } catch (e) {
            console.error('Failed to initialize dropdowns', e);
        }
    }

    async function onBookChange() {
        stopAudioOnUIEvent();
        const bookId = parseInt(elements.bookSelect.value);
        if (!bookId) return;

        try {
            state.chapters = await fetchChapters(bookId);
            
            elements.chapterSelect.innerHTML = '<option value="">Ch</option>' +
                state.chapters.map(c => `<option value="${c.firstVerseId}">${c.chapter}</option>`).join('');
            elements.chapterSelect.disabled = false;
            
            elements.verseSelect.innerHTML = '<option value="">V</option>';
            elements.verseSelect.disabled = true;
        } catch (e) {
            console.error('Failed to load chapters', e);
        }
    }

    async function onChapterChange() {
        const wasPlaying = state.audioPlaying;
        if (wasPlaying) stopAudio();

        const firstVerseId = parseInt(elements.chapterSelect.value);
        if (!firstVerseId) return;

        // Find chapter info
        const chapter = state.chapters.find(c => c.firstVerseId === firstVerseId);
        if (!chapter) return;

        // Populate verse dropdown
        const verses = [];
        for (let i = 0; i < chapter.verseCount; i++) {
            verses.push({
                id: firstVerseId + i,
                num: i + 1
            });
        }

        elements.verseSelect.innerHTML = '<option value="">V</option>' +
            verses.map(v => `<option value="${v.id}">${v.num}</option>`).join('');
        elements.verseSelect.disabled = false;

        // Navigate to first verse of chapter
        await goToVerse(firstVerseId);

        if (wasPlaying) restartAudioIfPlaying(wasPlaying);
    }

    async function onVerseChange() {
        const wasPlaying = state.audioPlaying;
        if (wasPlaying) stopAudio();

        const verseId = parseInt(elements.verseSelect.value);
        if (verseId) {
            await goToVerse(verseId);
            if (wasPlaying) restartAudioIfPlaying(wasPlaying);
        }
    }

    /**
     * Update dropdowns to reflect current verse without triggering navigation.
     * This synchronizes the book/chapter/verse selectors with the current reading position.
     */
    async function updateDropdowns() {
        const currentVerse = state.pageVerses.find(v => v.id === state.currentVerseId);
        if (!currentVerse) return;
        
        // Find current book
        const book = state.books.find(b => b.name === currentVerse.book);
        if (!book) return;
        
        // Set book dropdown (no event triggered by setting .value)
        elements.bookSelect.value = book.id;
        
        // Check if we need to reload chapters for this book
        const needsChapterReload = state.chapters.length === 0 ||
            !state.chapters.some(c => c.firstVerseId >= book.firstVerseId &&
                                      c.firstVerseId < book.firstVerseId + 2000); // rough check for same book
        
        if (needsChapterReload) {
            try {
                state.chapters = await fetchChapters(book.id);
            } catch (e) {
                console.error('Failed to load chapters for dropdown sync', e);
                return;
            }
        }
        
        // Populate chapter dropdown
        elements.chapterSelect.innerHTML = '<option value="">Ch</option>' +
            state.chapters.map(c => `<option value="${c.firstVerseId}">${c.chapter}</option>`).join('');
        elements.chapterSelect.disabled = false;
        
        // Find and select current chapter
        const chapter = state.chapters.find(c => c.chapter === currentVerse.chapter);
        if (!chapter) return;
        
        elements.chapterSelect.value = chapter.firstVerseId;
        
        // Populate verse dropdown for current chapter
        const verses = [];
        for (let i = 0; i < chapter.verseCount; i++) {
            verses.push({
                id: chapter.firstVerseId + i,
                num: i + 1
            });
        }
        
        elements.verseSelect.innerHTML = '<option value="">V</option>' +
            verses.map(v => `<option value="${v.id}">${v.num}</option>`).join('');
        elements.verseSelect.disabled = false;
        
        // Select current verse
        elements.verseSelect.value = currentVerse.id;
    }

    // ============================================
    // Search Functions
    // ============================================

    async function handleSearch() {
        const query = elements.searchInput.value.trim();
        if (!query) return;

        // Dismiss the keyboard on mobile
        elements.searchInput.blur();

        // Bible reference: jump directly unless Matching Passages has hits to offer.
        try {
            const refResult = await parseReference(query);
            if (refResult.valid && refResult.verseId) {
                const opened = await maybeOpenReferencePassageDiscovery(query, refResult);
                if (opened) return;
                const wasPlaying = state.audioWasPlayingBeforeModal;
                closeSearch();
                await goToVerse(refResult.verseId);
                elements.searchInput.value = '';
                elements.searchInput.blur();
                if (wasPlaying) restartAudioIfPlaying(wasPlaying);
                return;
            }
        } catch (e) {
            // Not a reference, continue with text search
        }

        // Full-text search: display top verses; load id-only hits for passage overlap.
        try {
            const results = await searchBible(query, 50);
            state.lastSearchQuery = query;
            state.lastSearchResults = results;
            state.lastSearchHitIds = new Set((results.verses || []).map(v => v.id));
            state.searchResultTab = 'verses';
            const hasPassages = await prepareSearchResultTabs();
            if (hasPassages) {
                await loadSearchHitIdsForPassages(query);
            }
            openSearch();
            renderSearchBrowse();
        } catch (e) {
            console.error('Search failed', e);
        }
    }

    /**
     * When a typed reference overlaps a saved/Featured passage, open the search
     * overlay on Matching Passages instead of jumping straight to the verse.
     * @returns {Promise<boolean>} true if the overlay was opened
     */
    async function maybeOpenReferencePassageDiscovery(query, refResult) {
        const hasPassages = await prepareSearchResultTabs();
        if (!hasPassages) return false;

        const verseId = refResult.verseId;
        const v = refResult.verse || {};
        state.lastSearchQuery = query;
        state.lastSearchHitIds = new Set([verseId]);
        state.lastSearchResults = {
            query,
            count: 1,
            verses: [{
                id: verseId,
                book: v.book || '',
                chapter: v.chapter,
                verse: v.verse ?? 1,
                text: v.text || '',
                highlight: null
            }]
        };

        const matching = filterMatchingPassages(query);
        if (matching.length === 0) return false;

        state.searchResultTab = 'passages';
        syncSearchResultTabs(true);
        openSearch();
        renderSearchBrowse();
        return true;
    }

    async function loadSearchHitIdsForPassages(query) {
        try {
            const idsResult = await searchBibleIds(query, 5000);
            state.lastSearchHitIds = new Set(idsResult.ids || []);
        } catch (err) {
            console.error('Failed to load search hit ids for passages', err);
        }
    }

    /** Show Matching Passages only when the user has a non-empty catalog. */
    async function prepareSearchResultTabs() {
        if (state.currentUser && (!state.passages || state.passages.length === 0)) {
            try {
                await loadPassagesFromApi();
            } catch (_) { /* leave empty */ }
        }
        const hasPassages = !!(state.currentUser && state.passages && state.passages.length > 0);
        if (!hasPassages && state.searchResultTab === 'passages') {
            state.searchResultTab = 'verses';
        }
        syncSearchResultTabs(hasPassages);
        return hasPassages;
    }

    function syncSearchResultTabs(hasPassages) {
        if (!elements.searchResultTabs) return;
        // Omit the whole strip when there's only Matching Verses — leaves room
        // for future lanes (collections, plans, etc.) without empty chrome.
        elements.searchResultTabs.hidden = !hasPassages;
        if (elements.searchPassagesTab) {
            elements.searchPassagesTab.hidden = !hasPassages;
        }
        elements.searchResultTabs.querySelectorAll('.search-result-tab').forEach(btn => {
            const active = btn.dataset.tab === state.searchResultTab;
            btn.classList.toggle('active', active);
            btn.setAttribute('aria-selected', active ? 'true' : 'false');
        });
    }

    function setSearchResultTab(tab) {
        if (tab === 'passages') {
            const hasPassages = !!(state.currentUser && state.passages && state.passages.length > 0);
            if (!hasPassages) return;
        }
        state.searchResultTab = tab === 'passages' ? 'passages' : 'verses';
        syncSearchResultTabs(!!(state.currentUser && state.passages && state.passages.length > 0));
        renderSearchBrowse();
    }

    function renderSearchBrowse() {
        if (state.searchResultTab === 'passages') {
            renderSearchPassages();
        } else {
            renderSearchVerses();
        }
    }

    function renderSearchVerses() {
        const results = state.lastSearchResults;
        if (!results) {
            elements.searchResultsTitle.textContent = 'Search Results';
            elements.searchResultsList.innerHTML = '<p class="no-results">No verses found.</p>';
            return;
        }

        elements.searchResultsTitle.textContent =
            `${results.count} verse${results.count !== 1 ? 's' : ''} for "${results.query}"`;

        if (!results.verses || results.verses.length === 0) {
            elements.searchResultsList.innerHTML = '<p class="no-results">No verses found.</p>';
            return;
        }

        elements.searchResultsList.innerHTML = results.verses.map(v => `
            <div class="search-result-item" data-verse-id="${v.id}" tabindex="0">
                <div class="search-result-ref">${escapeHtml(v.book)} ${v.chapter}:${v.verse}</div>
                <div class="search-result-text">${v.highlight || escapeHtml(v.text)}</div>
            </div>
        `).join('');

        elements.searchResultsList.querySelectorAll('.search-result-item').forEach(item => {
            item.addEventListener('click', async () => {
                const wasPlaying = state.audioWasPlayingBeforeModal;
                const verseId = parseInt(item.dataset.verseId, 10);
                closeSearch();
                await goToVerse(verseId);
                if (wasPlaying) restartAudioIfPlaying(wasPlaying);
            });
        });

        const firstResult = elements.searchResultsList.querySelector('.search-result-item');
        if (firstResult) firstResult.focus();
    }

    function passageOverlapsHitIds(passage, hitIds) {
        if (!hitIds || hitIds.size === 0) return false;
        // Prefer natural-key segments when present; fall back to span endpoints.
        try {
            if (passage.naturalKey) {
                return rangesFromNaturalKey(passage.naturalKey)
                    .some(r => {
                        for (const id of hitIds) {
                            if (id >= r.from && id <= r.to) return true;
                        }
                        return false;
                    });
            }
        } catch (_) { /* fall through */ }
        const from = passage.fromVerseId;
        const to = passage.toVerseId;
        if (!Number.isFinite(from) || !Number.isFinite(to)) return false;
        for (const id of hitIds) {
            if (id >= from && id <= to) return true;
        }
        return false;
    }

    function filterMatchingPassages(query) {
        const q = (query || '').trim().toLowerCase();
        const hitIds = state.lastSearchHitIds || new Set();
        return (state.passages || []).filter(p => {
            if (q) {
                const label = passageDisplayLabel(p).toLowerCase();
                if (label.includes(q)
                    || (p.reference && p.reference.toLowerCase().includes(q))
                    || (p.title && p.title.toLowerCase().includes(q))) {
                    return true;
                }
            }
            return passageOverlapsHitIds(p, hitIds);
        });
    }

    function renderSearchPassages() {
        const query = state.lastSearchQuery || '';
        const list = filterMatchingPassages(query);

        elements.searchResultsTitle.textContent =
            `${list.length} passage${list.length !== 1 ? 's' : ''} for "${query}"`;

        if (list.length === 0) {
            elements.searchResultsList.innerHTML =
                '<p class="no-results">No matching passages.<br>' +
                'Try a passage title, reference, or a verse that overlaps a saved passage.</p>';
            return;
        }

        elements.searchResultsList.innerHTML = list.map(p => `
            <div class="search-result-item search-passage-item" data-passage-id="${p.id}" tabindex="0">
                <div class="search-result-ref">${escapeHtml(passageDisplayLabel(p))}${p.global ? '<span class="passage-insert-badge">Featured</span>' : ''}</div>
                <div class="search-result-text">${escapeHtml(p.reference || '')}</div>
            </div>
        `).join('');

        elements.searchResultsList.querySelectorAll('.search-passage-item').forEach(item => {
            item.addEventListener('click', async () => {
                const wasPlaying = state.audioWasPlayingBeforeModal;
                const id = item.dataset.passageId;
                closeSearch();
                await enterPassageMode(id);
                if (wasPlaying) restartAudioIfPlaying(wasPlaying);
            });
        });

        const first = elements.searchResultsList.querySelector('.search-result-item');
        if (first) first.focus();
    }

    function openSearch() {
        state.audioWasPlayingBeforeModal = state.audioPlaying;
        stopAudioOnUIEvent();
        state.searchOpen = true;
        elements.searchOverlay.hidden = false;
    }

    function closeSearch() {
        state.searchOpen = false;
        state.searchResultTab = 'verses';
        state.lastSearchHitIds = null;
        elements.searchOverlay.hidden = true;
        if (elements.searchResultTabs) elements.searchResultTabs.hidden = true;
        hideSearchAutocomplete();
        document.body.classList.remove('mobile-search-open');
        if (elements.mobileSearchCancel) elements.mobileSearchCancel.hidden = true;
    }

    // ── Search-bar autocomplete for passages + collections ──
    const searchAc = { open: false, activeIndex: -1, matches: [] };

    function updateSearchAutocomplete() {
        const q = elements.searchInput.value.trim().toLowerCase();
        if (!state.currentUser || !q) {
            hideSearchAutocomplete();
            return;
        }
        const passageMatches = state.passages
            .filter(p => {
                const label = passageDisplayLabel(p).toLowerCase();
                return label.includes(q) || (p.reference && p.reference.toLowerCase().includes(q));
            })
            .slice(0, 6)
            .map(p => ({ type: 'passage', id: p.id, label: passageDisplayLabel(p) }));
        const collectionMatches = state.collections
            .filter(c => c.label.toLowerCase().includes(q))
            .slice(0, 6)
            .map(c => ({ type: 'collection', id: c.id, label: c.label }));
        const matches = [...passageMatches, ...collectionMatches].slice(0, 10);
        if (matches.length === 0) {
            hideSearchAutocomplete();
            return;
        }
        searchAc.open = true;
        searchAc.activeIndex = -1;
        searchAc.matches = matches;
        let html = '';
        const pass = matches.filter(m => m.type === 'passage');
        const cols = matches.filter(m => m.type === 'collection');
        if (pass.length) {
            html += '<div class="search-ac-heading">Passages</div>' +
                pass.map((m) => {
                    const i = matches.indexOf(m);
                    return highlightAcItem(m, q, i);
                }).join('');
        }
        if (cols.length) {
            html += '<div class="search-ac-heading">Collections</div>' +
                cols.map((m) => {
                    const i = matches.indexOf(m);
                    return highlightAcItem(m, q, i);
                }).join('');
        }
        elements.searchAutocomplete.innerHTML = html;
        elements.searchAutocomplete.hidden = false;
    }

    function highlightAcItem(m, q, i) {
        const label = m.label;
        const idx = label.toLowerCase().indexOf(q);
        const highlighted = idx < 0 ? escapeHtml(label)
            : escapeHtml(label.slice(0, idx)) +
              '<strong>' + escapeHtml(label.slice(idx, idx + q.length)) + '</strong>' +
              escapeHtml(label.slice(idx + q.length));
        const dataAttr = m.type === 'passage'
            ? `data-passage-id="${m.id}"`
            : `data-collection-id="${m.id}"`;
        return `<button class="search-ac-item" ${dataAttr} data-index="${i}">${highlighted}</button>`;
    }

    function hideSearchAutocomplete() {
        searchAc.open = false;
        searchAc.activeIndex = -1;
        searchAc.matches = [];
        if (elements.searchAutocomplete) elements.searchAutocomplete.hidden = true;
    }

    function moveSearchAcActive(delta) {
        const n = searchAc.matches.length;
        if (n === 0) return;
        searchAc.activeIndex = (searchAc.activeIndex + delta + n) % n;
        elements.searchAutocomplete.querySelectorAll('.search-ac-item').forEach((el, i) => {
            el.classList.toggle('active', i === searchAc.activeIndex);
        });
    }

    async function selectCollectionSuggestion(id) {
        hideSearchAutocomplete();
        elements.searchInput.value = '';
        elements.searchInput.blur();
        closeSearch();
        await enterCollectionMode(id);
    }

    async function selectPassageSuggestion(id) {
        hideSearchAutocomplete();
        elements.searchInput.value = '';
        elements.searchInput.blur();
        closeSearch();
        await enterPassageMode(id);
    }

    // ============================================
    // Help Modal
    // ============================================

    function openHelp() {
        stopAudioOnUIEvent();
        state.helpOpen = true;
        elements.helpOverlay.hidden = false;
    }

    function closeHelp() {
        state.helpOpen = false;
        elements.helpOverlay.hidden = true;
    }

    function toggleHelp() {
        if (state.helpOpen) {
            closeHelp();
        } else {
            openHelp();
        }
    }

    // ============================================
    // Font Size Controls
    // ============================================

    function increaseFontSize() {
        state.fontSizeMultiplier = Math.min(state.fontSizeMultiplier + 0.1, 1.8);
        applyFontSize();
    }

    function decreaseFontSize() {
        state.fontSizeMultiplier = Math.max(state.fontSizeMultiplier - 0.1, 0.7);
        applyFontSize();
    }

    function applyFontSize() {
        const baseSizePx = 20 * state.fontSizeMultiplier;
        document.documentElement.style.setProperty('--font-size-base', `${baseSizePx}px`);
        localStorage.setItem(STORAGE_KEYS.FONT_SIZE, state.fontSizeMultiplier.toString());
        
        // Reload page with new font size
        loadPage(state.pageStartVerseId);
    }

    function loadFontSize() {
        const saved = localStorage.getItem(STORAGE_KEYS.FONT_SIZE);
        if (saved) {
            state.fontSizeMultiplier = parseFloat(saved);
            applyFontSize();
        }
    }

    // ============================================
    // State Persistence
    // ============================================

    function saveState() {
        localStorage.setItem(STORAGE_KEYS.CURRENT_VERSE, state.currentVerseId.toString());
    }

    function loadState() {
        const saved = localStorage.getItem(STORAGE_KEYS.CURRENT_VERSE);
        if (saved) {
            state.currentVerseId = parseInt(saved) || 1;
        }

        // Check URL parameter
        const urlParams = new URLSearchParams(window.location.search);
        const vidParam = urlParams.get('vid');
        if (vidParam) {
            state.currentVerseId = parseInt(vidParam) || state.currentVerseId;
        }
    }

    // ============================================
    // Saved Verses & Tags Storage
    // ============================================

    function loadSavedVerses() {
        const saved = localStorage.getItem(STORAGE_KEYS.SAVED_VERSES);
        state.savedVerses = saved ? JSON.parse(saved) : {};
    }

    function saveSavedVerses() {
        localStorage.setItem(STORAGE_KEYS.SAVED_VERSES, JSON.stringify(state.savedVerses));
    }

    function loadTags() {
        const saved = localStorage.getItem(STORAGE_KEYS.TAGS);
        state.tags = saved ? JSON.parse(saved) : {};
    }

    function saveTags() {
        localStorage.setItem(STORAGE_KEYS.TAGS, JSON.stringify(state.tags));
    }

    async function loadLibraryFromApi() {
        try {
            const [verses, tags] = await Promise.all([
                libApi('/api/library/verses'),
                libApi('/api/library/tags')
            ]);
            state.savedVerses = {};
            verses.forEach(v => {
                state.savedVerses[v.verseId] = {
                    id: v.verseId,
                    savedAt: new Date(v.savedAt).getTime(),
                    tagIds: v.tagIds.map(String),
                    note: v.note || ''
                };
            });
            state.tags = {};
            tags.forEach(t => {
                state.tags[t.id] = {
                    id: t.id,
                    name: t.name,
                    colorIndex: t.colorIndex,
                    createdAt: new Date(t.createdAt).getTime()
                };
            });
        } catch (err) {
            console.error('Failed to load library from API:', err);
        }
    }

    async function loadMemorizationFromApi() {
        try {
            const entries = await libApi('/api/memorization/queue');
            state.memorizedPassages = {};
            state.memorizedEntries = [];
            entries.forEach(entry => {
                state.memorizedPassages[entry.passage.naturalKey] = entry.id;
                state.memorizedEntries.push({
                    id:          entry.id,
                    fromVerseId: entry.passage.fromVerseId,
                    toVerseId:   entry.passage.toVerseId,
                    naturalKey:  entry.passage.naturalKey
                });
            });
        } catch (err) {
            console.error('Failed to load memorization queue:', err);
        }
    }

    // ============================================
    // Chapter Notes (account-only)
    // ============================================

    function chapterKey(bookId, chapter) {
        return `${bookId}:${chapter}`;
    }

    /**
     * Book + chapter of the currently selected verse, with a display label
     * ("Genesis 3", "Psalm 23"). Null until a page is rendered.
     */
    function getCurrentChapterRef() {
        const verse = state.pageVerses.find(v => v.id === state.currentVerseId);
        if (!verse) return null;
        const isPsalm = verse.book === 'Psalms' || verse.book === 'Psalm';
        const label = `${isPsalm ? 'Psalm' : verse.book} ${verse.chapter}`;
        return { bookId: verse.bookId, chapter: verse.chapter, label };
    }

    async function loadChapterNotesFromApi() {
        try {
            const notes = await libApi('/api/chapter-notes');
            state.chapterNotes = {};
            notes.forEach(n => {
                state.chapterNotes[chapterKey(n.bookId, n.chapter)] = n;
            });
        } catch (err) {
            console.error('Failed to load chapter notes from API:', err);
        }
    }

    async function saveChapterNoteToApi(bookId, chapter, note) {
        const saved = await libApi(`/api/chapter-notes/${bookId}/${chapter}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ note })
        });
        state.chapterNotes[chapterKey(bookId, chapter)] = saved;
        return saved;
    }

    async function deleteChapterNoteFromApi(bookId, chapter) {
        await libApi(`/api/chapter-notes/${bookId}/${chapter}`, { method: 'DELETE' });
        delete state.chapterNotes[chapterKey(bookId, chapter)];
    }

    // ============================================
    // Book Notes (account-only)
    // ============================================

    /**
     * Book of the currently selected verse, as a note-editor target.
     * Null until a page is rendered.
     */
    function getCurrentBookRef() {
        const verse = state.pageVerses.find(v => v.id === state.currentVerseId);
        if (!verse) return null;
        return { type: 'book', bookId: verse.bookId, bookName: verse.book, label: verse.book };
    }

    async function loadBookNotesFromApi() {
        try {
            const notes = await libApi('/api/book-notes');
            state.bookNotes = {};
            notes.forEach(n => {
                state.bookNotes[n.bookId] = n;
            });
        } catch (err) {
            console.error('Failed to load book notes from API:', err);
        }
    }

    async function saveBookNoteToApi(bookId, note) {
        const saved = await libApi(`/api/book-notes/${bookId}`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ note })
        });
        state.bookNotes[bookId] = saved;
        return saved;
    }

    async function deleteBookNoteFromApi(bookId) {
        await libApi(`/api/book-notes/${bookId}`, { method: 'DELETE' });
        delete state.bookNotes[bookId];
    }

    // ============================================
    // Note Markdown Renderer
    // ============================================
    // Markdown-lite: escapes ALL html first, then applies a small set of
    // patterns, so the output can never contain user-supplied markup.
    // Supported: # ## ### headings, **bold**, *italic*, - / * / 1. lists,
    // and verse links: [12] (verse in this chapter) or [John 3:16] (any reference).

    function renderNoteInline(text, ctx) {
        let html = escapeHtml(text);
        html = html.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
        html = html.replace(/\*([^*]+)\*/g, '<em>$1</em>');
        // Verse links — brackets are reserved for verse refs, not markdown URLs
        html = html.replace(/\[([^\]]+)\]/g, (match, ref) => {
            const trimmed = ref.trim();
            // [v=26136-26138] — portable scripture pointer
            const vTok = trimmed.match(/^v=(.+)$/i);
            if (vTok) {
                try {
                    const body = serializeVBody(parseVToken(trimmed));
                    const p = findPassageByVBody(body);
                    const label = p ? passageDisplayLabel(p) : body;
                    return `<a class="note-range-link" data-v="${escapeHtml(body)}" href="#">${escapeHtml(label)}</a>`;
                } catch {
                    return match;
                }
            }
            // [passage=<uuid>] — legacy compat → focused reader via passage id
            const passageTok = trimmed.match(/^passage=([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})$/i);
            if (passageTok) {
                const p = state.passages.find(x => x.id === passageTok[1]);
                const label = p ? passageDisplayLabel(p) : 'Passage';
                return `<a class="note-passage-link" data-passage-id="${passageTok[1]}" href="#">${escapeHtml(label)}</a>`;
            }
            // [pid=123] — legacy collection link (still supported)
            const pid = trimmed.match(/^pid=(\d+)$/);
            if (pid) {
                const collection = state.collections.find(c => c.id === parseInt(pid[1], 10));
                const label = collection ? collection.label : `Collection #${pid[1]}`;
                return `<a class="note-collection-link" data-collection-id="${pid[1]}" href="#">${escapeHtml(label)}</a>`;
            }
            if (ctx && ctx.type === 'book') {
                if (/^\d+$/.test(trimmed) || /^\d+:\d+$/.test(trimmed)) {
                    return `<a class="note-verse-link" data-ref="${ctx.bookName} ${trimmed}" href="#">${match}</a>`;
                }
                return `<a class="note-verse-link" data-ref="${trimmed}" href="#">${match}</a>`;
            }
            if (/^\d+$/.test(trimmed)) {
                const verseNum = parseInt(trimmed);
                if (ctx && verseNum >= 1 && verseNum <= ctx.verseCount) {
                    const verseId = ctx.firstVerseId + verseNum - 1;
                    return `<a class="note-verse-link" data-verse-id="${verseId}" href="#">${match}</a>`;
                }
                return match;
            }
            return `<a class="note-verse-link" data-ref="${trimmed}" href="#">${match}</a>`;
        });
        return html;
    }

    /** Fill human-readable labels on [v=…] links that only had a range body. */
    async function hydrateRangeLinkLabels(root) {
        if (!root) return;
        const links = [...root.querySelectorAll('.note-range-link[data-v]')];
        for (const link of links) {
            const body = link.dataset.v;
            const p = findPassageByVBody(body);
            if (p) {
                link.textContent = passageDisplayLabel(p);
                continue;
            }
            if (link.dataset.labelReady) continue;
            try {
                const res = await fetch(`/api/ranges?v=${encodeURIComponent(body)}`);
                if (!res.ok) continue;
                const data = await res.json();
                link.textContent = data.reference || body;
                link.dataset.labelReady = '1';
            } catch (_) { /* leave body as label */ }
        }
    }

    /**
     * Render a note's plain text to safe HTML.
     * ctx = { firstVerseId, verseCount } of the note's chapter (for [N] links).
     */
    function renderNoteMarkdown(text, ctx) {
        const lines = text.split('\n');
        const out = [];
        let list = null;        // 'ul' | 'ol' | null
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
                out.push(`<h${level + 3}>${renderNoteInline(heading[2], ctx)}</h${level + 3}>`);
            } else if (bullet || numbered) {
                flushParagraph();
                const type = bullet ? 'ul' : 'ol';
                if (list !== type) {
                    closeList();
                    out.push(`<${type}>`);
                    list = type;
                }
                out.push(`<li>${renderNoteInline((bullet || numbered)[1], ctx)}</li>`);
            } else {
                closeList();
                paragraph.push(renderNoteInline(line, ctx));
            }
        }
        flushParagraph();
        closeList();
        return out.join('');
    }

    /** Plain-text version of a note for previews (drops markers, keeps content). */
    function stripNoteMarkdown(text) {
        return text
            .replace(/^#{1,3}\s+/gm, '')
            .replace(/\*\*([^*]+)\*\*/g, '$1')
            .replace(/\*([^*]+)\*/g, '$1')
            .replace(/^[-*]\s+/gm, '')
            .replace(/\s+/g, ' ')
            .trim();
    }

    /** Resolve a clicked verse link (data-verse-id or data-ref) and open focused range. */
    async function handleNoteVerseLinkClick(link) {
        let verseId = parseInt(link.dataset.verseId);
        if (!verseId && link.dataset.ref) {
            try {
                const res = await fetch(`/api/reference?ref=${encodeURIComponent(link.dataset.ref)}`);
                if (res.ok) {
                    const parsed = await res.json();
                    if (parsed.valid) verseId = parsed.verseId;
                }
            } catch (_) { /* handled below */ }
        }
        if (!verseId) {
            showToast(`Couldn't find "${link.dataset.ref}"`);
            return;
        }
        stageNoteReturnFromOpenEditors();
        closeChapterNoteEditor();
        closeNoteEditor();
        closeLibrary();
        // Single-verse focused session so ← Back can restore the note/context
        await enterRangeMode(String(verseId));
    }

    /**
     * One-time migration: syncs any localStorage saved verses/tags to the DB.
     * Called at login time with a snapshot captured before loadLibraryFromApi() overwrites state.
     * After migration, clears localStorage so data isn't duplicated on subsequent logins.
     */
    async function migrateLocalStorageToDb(localVerses, localTags) {
        if (Object.keys(localVerses).length === 0 && Object.keys(localTags).length === 0) {
            return; // nothing to migrate
        }

        // Map old localStorage tag id ("tag-TIMESTAMP") → new DB UUID
        const tagIdMap = {};

        // 1. Migrate tags (skip if a same-named tag already exists in DB)
        for (const localTag of Object.values(localTags)) {
            const existing = Object.values(state.tags).find(
                t => t.name.toLowerCase() === localTag.name.toLowerCase()
            );
            if (existing) {
                tagIdMap[localTag.id] = existing.id;
            } else {
                try {
                    const tag = await libApi('/api/library/tags', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ name: localTag.name, colorIndex: localTag.colorIndex })
                    });
                    state.tags[tag.id] = {
                        id: tag.id,
                        name: tag.name,
                        colorIndex: tag.colorIndex,
                        createdAt: new Date(tag.createdAt).getTime()
                    };
                    tagIdMap[localTag.id] = tag.id;
                } catch (err) {
                    console.error('Migration: failed to create tag', localTag.name, err);
                }
            }
        }

        // 2. Migrate verses not already in DB
        for (const localVerse of Object.values(localVerses)) {
            const verseId = localVerse.id;
            if (state.savedVerses[verseId]) continue; // already in DB

            try {
                const sv = await libApi('/api/library/verses', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ verseId })
                });
                state.savedVerses[verseId] = {
                    id: sv.verseId,
                    savedAt: new Date(sv.savedAt).getTime(),
                    tagIds: [],
                    note: sv.note || ''
                };

                // Migrate tag links (using remapped UUIDs)
                for (const localTagId of (localVerse.tagIds || [])) {
                    const dbTagId = tagIdMap[localTagId];
                    if (!dbTagId) continue;
                    try {
                        await libApi(`/api/library/verses/${verseId}/tags/${dbTagId}`, { method: 'POST' });
                        state.savedVerses[verseId].tagIds.push(dbTagId);
                    } catch (err) {
                        console.error('Migration: failed to add tag to verse', verseId, err);
                    }
                }

                // Migrate note
                if (localVerse.note) {
                    try {
                        await libApi(`/api/library/verses/${verseId}/note`, {
                            method: 'PATCH',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({ note: localVerse.note })
                        });
                        state.savedVerses[verseId].note = localVerse.note;
                    } catch (err) {
                        console.error('Migration: failed to migrate note for verse', verseId, err);
                    }
                }
            } catch (err) {
                console.error('Migration: failed to save verse', verseId, err);
            }
        }

        // Clear localStorage — data now lives in DB
        localStorage.removeItem(STORAGE_KEYS.SAVED_VERSES);
        localStorage.removeItem(STORAGE_KEYS.TAGS);
    }

    // ============================================
    // Saved Verses Core Functions
    // ============================================

    function isVerseSaved(verseId) {
        return !!state.savedVerses[verseId];
    }

    async function toggleSaveVerse(verseId) {
        if (state.currentUser) {
            if (state.savedVerses[verseId]) {
                // Optimistic delete
                delete state.savedVerses[verseId];
                await remeasureCurrentPage();
                try {
                    await libApi(`/api/library/verses/${verseId}`, { method: 'DELETE' });
                } catch (err) {
                    console.error('Failed to unsave verse:', err);
                }
            } else {
                // Save via API — need the server-assigned savedAt timestamp
                try {
                    const sv = await libApi('/api/library/verses', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ verseId })
                    });
                    state.savedVerses[verseId] = {
                        id: sv.verseId,
                        savedAt: new Date(sv.savedAt).getTime(),
                        tagIds: sv.tagIds.map(String),
                        note: sv.note || ''
                    };
                } catch (err) {
                    console.error('Failed to save verse:', err);
                }
                await remeasureCurrentPage();
            }
        } else {
            // Anonymous — localStorage only
            if (state.savedVerses[verseId]) {
                delete state.savedVerses[verseId];
            } else {
                state.savedVerses[verseId] = {
                    id: verseId,
                    savedAt: Date.now(),
                    tagIds: [],
                    note: ''
                };
            }
            saveSavedVerses();
            await remeasureCurrentPage();
        }
    }

    // ─── Passage Picker ───────────────────────────────────────────────────────

    /** Parse a natural key string into [{from,to}] segments (mirrors NaturalKeyParser.java) */
    function parseNaturalKey(key) {
        return key.split(',').map(part => {
            part = part.trim();
            if (part.includes(':')) {
                const [f, t] = part.split(':').map(Number);
                return { from: f, to: t };
            }
            const v = Number(part);
            return { from: v, to: v };
        });
    }

    /** Build a natural key string from a sorted array of verse IDs (consecutive runs → ranges). */
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

    // ── Portable [v=…] helpers (mirrors VerseRangeParser) ──

    function parseVToken(raw) {
        let s = String(raw).trim();
        const m = s.match(/^\[?v=(.+)\]?$/i);
        if (m) s = m[1].trim();
        const ranges = [];
        for (const part of s.split(',')) {
            const p = part.trim();
            if (!p) throw new Error('empty segment');
            if (p.includes('-')) {
                const [a, b] = p.split('-', 2).map(x => parseInt(x.trim(), 10));
                if (!Number.isFinite(a) || !Number.isFinite(b)) throw new Error('bad range');
                ranges.push({ from: Math.min(a, b), to: Math.max(a, b) });
            } else {
                const v = parseInt(p, 10);
                if (!Number.isFinite(v)) throw new Error('bad id');
                ranges.push({ from: v, to: v });
            }
        }
        return normalizeVRanges(ranges);
    }

    function normalizeVRanges(ranges) {
        const sorted = ranges
            .map(r => ({ from: Math.min(r.from, r.to), to: Math.max(r.from, r.to) }))
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

    function serializeVBody(ranges) {
        return normalizeVRanges(ranges).map(r =>
            r.from === r.to ? String(r.from) : `${r.from}-${r.to}`
        ).join(',');
    }

    function serializeVToken(ranges) {
        return `[v=${serializeVBody(ranges)}]`;
    }

    function rangesFromNaturalKey(naturalKey) {
        const ranges = [];
        for (const part of String(naturalKey).split(',')) {
            const p = part.trim();
            if (p.includes(':')) {
                const [a, b] = p.split(':', 2).map(x => parseInt(x.trim(), 10));
                ranges.push({ from: a, to: b });
            } else {
                const v = parseInt(p, 10);
                ranges.push({ from: v, to: v });
            }
        }
        return normalizeVRanges(ranges);
    }

    function findPassageByVBody(vBody) {
        const target = serializeVBody(parseVToken(vBody));
        return state.passages.find(p => {
            try {
                return serializeVBody(rangesFromNaturalKey(p.naturalKey)) === target;
            } catch {
                return false;
            }
        });
    }

    function findPassageByNaturalKey(naturalKey) {
        try {
            return findPassageByVBody(serializeVBody(rangesFromNaturalKey(naturalKey)));
        } catch {
            return null;
        }
    }

    /**
     * Rewrite typed scripture refs in a note body to portable [v=…] tokens.
     * Leaves [v=…], [pid=…], [passage=…] and markdown alone.
     */
    async function normalizeNoteLinksOnSave(text, ctx) {
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
                || /^passage=[0-9a-f-]{36}$/i.test(inner)) {
                parts.push(m[0]);
                continue;
            }

            try {
                // Book notes: [12] means the whole chapter, not verse 1 of ch. 12
                if (ctx && ctx.type === 'book' && ctx.bookId && /^\d+$/.test(inner)) {
                    const chapterNum = parseInt(inner, 10);
                    const chapters = await getChaptersForBook(ctx.bookId);
                    const ch = chapters.find(c => c.chapter === chapterNum);
                    if (ch && ch.verseCount > 0) {
                        parts.push(serializeVToken([{
                            from: ch.firstVerseId,
                            to: ch.firstVerseId + ch.verseCount - 1
                        }]));
                        continue;
                    }
                }

                let verseId = null;
                if (ctx && ctx.type !== 'book' && /^\d+$/.test(inner)
                    && ctx.firstVerseId && ctx.verseCount) {
                    const n = parseInt(inner, 10);
                    if (n >= 1 && n <= ctx.verseCount) {
                        verseId = ctx.firstVerseId + n - 1;
                    }
                }
                if (verseId == null) {
                    let ref = inner;
                    if (ctx && ctx.type === 'book' && (/^\d+$/.test(inner) || /^\d+:\d+$/.test(inner))) {
                        ref = `${ctx.bookName} ${inner}`;
                    }
                    const res = await fetch(`/api/reference?ref=${encodeURIComponent(ref)}`);
                    if (res.ok) {
                        const parsed = await res.json();
                        if (parsed.valid && parsed.verseId) verseId = parsed.verseId;
                    }
                }
                if (verseId != null) {
                    parts.push(serializeVToken([{ from: verseId, to: verseId }]));
                } else {
                    parts.push(m[0]);
                }
            } catch {
                parts.push(m[0]);
            }
        }
        parts.push(text.slice(last));
        return parts.join('');
    }

    /** Returns true if verseId falls within any segment of the given natural key. */
    function verseInNaturalKey(verseId, naturalKey) {
        return parseNaturalKey(naturalKey).some(s => verseId >= s.from && verseId <= s.to);
    }

    async function openPassagePicker(verseId) {
        if (!state.currentUser) { showToast('Sign in to memorize verses'); return; }

        state.passagePickerOpen = true;
        elements.passagePickerOverlay.hidden = false;
        elements.passagePickerOverlay.dataset.editEntryId = '';
        elements.passagePickerChapters.innerHTML = '<div class="passage-picker-loading">Loading…</div>';
        elements.passagePickerAdd.disabled = true;
        elements.passagePickerAdd.textContent = 'Add to Queue';
        elements.passagePickerRemove.hidden = true;

        // Find existing entry covering this verse (edit mode)
        const existingEntry = state.memorizedEntries.find(e =>
            verseId >= e.fromVerseId && verseId <= e.toVerseId
        ) || null;

        let context;
        try {
            context = await libApi(`/api/memorization/context/${verseId}`);
        } catch (err) {
            elements.passagePickerChapters.innerHTML =
                '<div class="passage-picker-loading">Could not load verses.</div>';
            return;
        }

        // Build initial checked set
        const checkedIds = new Set();
        if (existingEntry) {
            // Pre-check all verses in the existing passage
            parseNaturalKey(existingEntry.naturalKey).forEach(seg => {
                for (let id = seg.from; id <= seg.to; id++) checkedIds.add(id);
            });
            elements.passagePickerRemove.hidden = false;
            elements.passagePickerAdd.textContent = 'Update';
            elements.passagePickerOverlay.dataset.editEntryId = existingEntry.id;
        } else {
            checkedIds.add(verseId);
        }

        renderPickerChapters(context, checkedIds, existingEntry, verseId);
    }

    function renderPickerChapters(context, checkedIds, existingEntry, anchorVerseId) {
        const sections = [context.prevChapter, context.currentChapter, context.nextChapter]
            .filter(Boolean);

        elements.passagePickerChapters.innerHTML = sections.map(ch => {
            const allIds = ch.verses.map(v => v.id);
            const headerLabel = `${ch.bookName} ${ch.chapter}`;
            const verseRows = ch.verses.map(v => `
                <label class="pp-verse-row">
                    <input type="checkbox" class="pp-verse-cb" data-verse-id="${v.id}"
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

        updatePickerSelectAllStates();
        updatePickerAddButton(existingEntry);

        // Scroll current verse into view
        const anchorCb = elements.passagePickerChapters
            .querySelector(`[data-verse-id="${anchorVerseId}"]`);
        if (anchorCb) anchorCb.closest('.pp-verse-row').scrollIntoView({ block: 'center' });

        // Verse checkbox events
        elements.passagePickerChapters.querySelectorAll('.pp-verse-cb').forEach(cb => {
            cb.addEventListener('change', () => {
                updatePickerSelectAllStates();
                updatePickerAddButton(existingEntry);
            });
        });

        // Select-all per chapter
        elements.passagePickerChapters.querySelectorAll('.pp-select-all-cb').forEach(cb => {
            cb.addEventListener('change', () => {
                const section = cb.closest('.pp-chapter-section');
                section.querySelectorAll('.pp-verse-cb')
                    .forEach(v => { v.checked = cb.checked; });
                updatePickerAddButton(existingEntry);
            });
        });
    }

    function updatePickerSelectAllStates() {
        elements.passagePickerChapters.querySelectorAll('.pp-chapter-section').forEach(section => {
            const all  = section.querySelectorAll('.pp-verse-cb');
            const chk  = section.querySelectorAll('.pp-verse-cb:checked');
            const sa   = section.querySelector('.pp-select-all-cb');
            if (!sa) return;
            sa.checked       = chk.length === all.length;
            sa.indeterminate = chk.length > 0 && chk.length < all.length;
        });
    }

    function updatePickerAddButton(existingEntry) {
        const checked = [...elements.passagePickerChapters
            .querySelectorAll('.pp-verse-cb:checked')]
            .map(cb => parseInt(cb.dataset.verseId, 10));
        const count = checked.length;
        elements.passagePickerAdd.disabled = count === 0;
        elements.passagePickerCount.textContent =
            count === 0 ? '0 verses selected' :
            count === 1 ? '1 verse selected' :
            `${count} verses selected`;
    }

    function closePassagePicker() {
        state.passagePickerOpen = false;
        elements.passagePickerOverlay.hidden = true;
    }

    async function submitPassagePicker(existingEntry) {
        const checkedIds = [...elements.passagePickerChapters
            .querySelectorAll('.pp-verse-cb:checked')]
            .map(cb => parseInt(cb.dataset.verseId, 10));
        if (!checkedIds.length) return;

        const naturalKey = buildNaturalKeyFromIds(checkedIds);
        elements.passagePickerAdd.disabled = true;

        try {
            let entry;
            if (existingEntry) {
                entry = await libApi(`/api/memorization/queue/${existingEntry.id}`, {
                    method: 'PATCH',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ naturalKey })
                });
            } else {
                entry = await libApi('/api/memorization/queue', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ naturalKey })
                });
            }
            // Update local state
            state.memorizedPassages[entry.passage.naturalKey] = entry.id;
            state.memorizedEntries = state.memorizedEntries.filter(e => e.id !== entry.id);
            state.memorizedEntries.push({
                id: entry.id,
                fromVerseId: entry.passage.fromVerseId,
                toVerseId:   entry.passage.toVerseId,
                naturalKey:  entry.passage.naturalKey
            });
            await remeasureCurrentPage();
            closePassagePicker();
        } catch (err) {
            console.error('Failed to save passage:', err);
            elements.passagePickerAdd.disabled = false;
        }
    }

    async function removePassageFromPicker(entryId) {
        elements.passagePickerRemove.disabled = true;
        try {
            await libApi(`/api/memorization/queue/${entryId}`, { method: 'DELETE' });
            // Update local state
            state.memorizedEntries = state.memorizedEntries.filter(e => e.id !== entryId);
            Object.keys(state.memorizedPassages).forEach(k => {
                if (state.memorizedPassages[k] === entryId) delete state.memorizedPassages[k];
            });
            await remeasureCurrentPage();
            closePassagePicker();
        } catch (err) {
            console.error('Failed to remove passage:', err);
            elements.passagePickerRemove.disabled = false;
        }
    }

    // ─── Passage Collections ──────────────────────────────────────────────────
    // Account-only: ordered groups of first-class passages under a label.

    /** "Matthew 4:24" for a verse object with book/chapter/verse fields. */
    function verseRefStr(v) {
        return `${v.book} ${v.chapter}:${v.verse}`;
    }

    /**
     * Group an ordered verse array into contiguous runs.
     * Returns [{startIndex, count, reference, verseIds}].
     */
    function groupIntoPassages(verses) {
        const passages = [];
        for (let i = 0; i < verses.length; i++) {
            if (i === 0 || verses[i].id !== verses[i - 1].id + 1) {
                passages.push({ startIndex: i, count: 1 });
            } else {
                passages[passages.length - 1].count++;
            }
        }
        passages.forEach(p => {
            const first = verses[p.startIndex];
            const last = verses[p.startIndex + p.count - 1];
            p.verseIds = verses.slice(p.startIndex, p.startIndex + p.count).map(v => v.id);
            if (p.count === 1) {
                p.reference = verseRefStr(first);
            } else if (first.bookId === last.bookId && first.chapter === last.chapter) {
                p.reference = `${first.book} ${first.chapter}:${first.verse}–${last.verse}`;
            } else if (first.bookId === last.bookId) {
                p.reference = `${first.book} ${first.chapter}:${first.verse}–${last.chapter}:${last.verse}`;
            } else {
                p.reference = `${verseRefStr(first)} – ${verseRefStr(last)}`;
            }
        });
        return passages;
    }

    async function loadCollectionsFromApi() {
        try {
            state.collections = await libApi('/api/collections');
        } catch (err) {
            console.error('Failed to load collections:', err);
            state.collections = [];
        }
    }

    async function loadPassagesFromApi(q) {
        try {
            const url = q ? `/api/passages?q=${encodeURIComponent(q)}` : '/api/passages';
            state.passages = await libApi(url);
            refreshScopedRangeLabelFromCatalog();
        } catch (err) {
            console.error('Failed to load passages:', err);
            state.passages = [];
        }
    }

    /** Apply a titled Passage label once the catalog arrives after a range deep link. */
    function refreshScopedRangeLabelFromCatalog() {
        const col = state.collection;
        if (!col || !col.naturalKey) return;
        if (col.kind !== 'range' && col.kind !== 'passage') return;
        const matched = findPassageByNaturalKey(col.naturalKey);
        if (!matched) return;
        const newLabel = passageDisplayLabel(matched);
        if (newLabel === col.label) return;
        col.label = newLabel;
        if (col.passageRefs && col.passageRefs[0] !== undefined) {
            col.passageRefs[0] = newLabel;
        }
        renderCollectionPage();
    }

    function fetchCollectionVerses(id) {
        return libApi(`/api/collections/${id}/verses`);
    }

    // ── Insert scripture into note textarea ──

    const PASSAGE_INSERT_MAX_VERSES = 500;

    async function openPassageInsertPicker(textarea) {
        // Matching Verses + [v=…] insert work signed-out (localStorage verse notes).
        // My Passages stays account-only.
        state.passageInsertTarget = textarea;
        state.passageInsertOpen = true;
        state.passageInsertTab = 'verses';
        state.passageInsertMode = 'browse';
        if (state.passageInsertSearchTimer) {
            clearTimeout(state.passageInsertSearchTimer);
            state.passageInsertSearchTimer = null;
        }
        elements.passageInsertOverlay.hidden = false;
        elements.passageInsertSearch.value = '';
        syncPassageInsertTabs();
        showPassageInsertBrowse();
        if (state.currentUser) {
            await loadPassagesFromApi();
        } else {
            state.passages = [];
        }
        renderPassageInsertBrowse();
        elements.passageInsertSearch.focus();
    }

    function closePassageInsertPicker() {
        state.passageInsertOpen = false;
        state.passageInsertTarget = null;
        state.passageInsertMode = 'browse';
        state.passageInsertSearchGen++;
        state.passageInsertExpandGen++;
        if (state.passageInsertSearchTimer) {
            clearTimeout(state.passageInsertSearchTimer);
            state.passageInsertSearchTimer = null;
        }
        elements.passageInsertOverlay.hidden = true;
    }

    function syncPassageInsertTabs() {
        if (!elements.passageInsertTabs) return;
        elements.passageInsertTabs.querySelectorAll('.passage-insert-tab').forEach(btn => {
            const active = btn.dataset.tab === state.passageInsertTab;
            btn.classList.toggle('active', active);
            btn.setAttribute('aria-selected', active ? 'true' : 'false');
        });
        elements.passageInsertSearch.placeholder = state.passageInsertTab === 'verses'
            ? 'Search scripture or reference…'
            : 'Filter by title or reference…';
    }

    function showPassageInsertBrowse() {
        state.passageInsertMode = 'browse';
        state.passageInsertExpandGen++;
        if (elements.passageInsertBrowse) elements.passageInsertBrowse.hidden = false;
        if (elements.passageInsertExpand) elements.passageInsertExpand.hidden = true;
        // Hide tab bar when signed out — Matching Verses is the only lane.
        if (elements.passageInsertTabs) {
            elements.passageInsertTabs.hidden = !state.currentUser;
        }
        elements.passageInsertSearch.hidden = false;
    }

    function setPassageInsertTab(tab) {
        if (tab === 'passages' && !state.currentUser) {
            showToast('Sign in to use saved passages');
            return;
        }
        state.passageInsertTab = tab === 'passages' ? 'passages' : 'verses';
        state.passageInsertSearchGen++;
        if (state.passageInsertSearchTimer) {
            clearTimeout(state.passageInsertSearchTimer);
            state.passageInsertSearchTimer = null;
        }
        syncPassageInsertTabs();
        showPassageInsertBrowse();
        renderPassageInsertBrowse();
        elements.passageInsertSearch.focus();
    }

    function onPassageInsertSearchInput() {
        if (state.passageInsertMode === 'expand') showPassageInsertBrowse();
        if (state.passageInsertTab === 'passages') {
            state.passageInsertSearchGen++;
            if (state.passageInsertSearchTimer) {
                clearTimeout(state.passageInsertSearchTimer);
                state.passageInsertSearchTimer = null;
            }
            renderPassageInsertPassages();
            return;
        }
        if (state.passageInsertSearchTimer) clearTimeout(state.passageInsertSearchTimer);
        state.passageInsertSearchTimer = setTimeout(() => {
            state.passageInsertSearchTimer = null;
            runPassageInsertVerseSearch();
        }, 280);
    }

    function renderPassageInsertBrowse() {
        if (state.passageInsertTab === 'passages') {
            renderPassageInsertPassages();
        } else {
            runPassageInsertVerseSearch();
        }
    }

    async function runPassageInsertVerseSearch() {
        const q = (elements.passageInsertSearch.value || '').trim();
        const gen = ++state.passageInsertSearchGen;
        if (!q) {
            elements.passageInsertCount.textContent = 'Type to search verses';
            elements.passageInsertList.innerHTML =
                '<p class="collections-empty">Search by words or a reference like John 3:16.<br>' +
                'Then optionally include surrounding verses before inserting.</p>';
            return;
        }

        elements.passageInsertCount.textContent = 'Searching…';
        elements.passageInsertList.innerHTML =
            '<p class="collections-empty">Searching…</p>';

        let refHit = null;
        let searchVerses = [];
        try {
            const refResult = await parseReference(q);
            if (refResult.valid && refResult.verseId) {
                const v = refResult.verse;
                refHit = {
                    id: refResult.verseId,
                    book: v?.book || refResult.parsed?.book || '',
                    chapter: v?.chapter ?? refResult.parsed?.chapter,
                    verse: v?.verse ?? refResult.parsed?.verse ?? 1,
                    text: v?.text || '',
                    highlight: null,
                    fromReference: true
                };
            }
        } catch (_) { /* not a reference */ }

        try {
            const results = await searchBible(q);
            searchVerses = results.verses || [];
        } catch (err) {
            console.error('Insert scripture search failed', err);
            if (gen !== state.passageInsertSearchGen) return;
            if (!refHit) {
                elements.passageInsertCount.textContent = 'Search failed';
                elements.passageInsertList.innerHTML =
                    '<p class="collections-empty">Could not search scripture. Try again.</p>';
                return;
            }
        }

        if (gen !== state.passageInsertSearchGen) return;

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

        elements.passageInsertCount.textContent =
            hits.length === 1 ? '1 matching verse' : `${hits.length} matching verses`;

        if (hits.length === 0) {
            elements.passageInsertList.innerHTML =
                '<p class="collections-empty">No verses found.</p>';
            return;
        }

        elements.passageInsertList.innerHTML = hits.map(v => {
            const ref = `${v.book} ${v.chapter}:${v.verse}`;
            const snippet = v.highlight || escapeHtml(v.text || '');
            const badge = v.fromReference
                ? '<span class="passage-insert-badge">Reference</span>'
                : '';
            return `
                <div class="search-result-item passage-insert-verse-hit" data-verse-id="${v.id}" tabindex="0">
                    <div class="search-result-ref">${escapeHtml(ref)}${badge}</div>
                    <div class="search-result-text">${snippet || '&nbsp;'}</div>
                </div>`;
        }).join('');

        elements.passageInsertList.querySelectorAll('.passage-insert-verse-hit').forEach(item => {
            const open = () => openPassageInsertExpand(parseInt(item.dataset.verseId, 10));
            item.addEventListener('click', open);
            item.addEventListener('keydown', e => {
                if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault();
                    open();
                }
            });
        });
    }

    function renderPassageInsertPassages() {
        const q = (elements.passageInsertSearch.value || '').trim().toLowerCase();
        let list = state.passages;
        if (q) {
            list = list.filter(p => {
                const label = passageDisplayLabel(p).toLowerCase();
                return label.includes(q)
                    || (p.reference && p.reference.toLowerCase().includes(q))
                    || (p.title && p.title.toLowerCase().includes(q));
            });
        }
        elements.passageInsertCount.textContent =
            list.length === 1 ? '1 passage' : `${list.length} passages`;

        if (list.length === 0) {
            elements.passageInsertList.innerHTML =
                '<p class="collections-empty">No passages yet.<br>' +
                'Create one from a collection builder or by memorizing a selection.<br>' +
                'Or use Matching Verses to insert any scripture.</p>';
            return;
        }

        elements.passageInsertList.innerHTML = list.map(p => `
            <div class="collections-item passage-insert-item" data-passage-id="${p.id}"
                 data-natural-key="${escapeHtml(p.naturalKey || '')}">
                <div class="collections-item-body">
                    <div class="collections-item-label">${escapeHtml(passageDisplayLabel(p))}</div>
                    <div class="collections-item-meta">${escapeHtml(p.reference || '')}${p.global ? ' · Featured' : ''}</div>
                </div>
            </div>`).join('');

        elements.passageInsertList.querySelectorAll('.passage-insert-item').forEach(item => {
            item.addEventListener('click', () => {
                insertPassageVToken(item.dataset.passageId, item.dataset.naturalKey);
            });
        });
    }

    async function openPassageInsertExpand(verseId) {
        if (!Number.isFinite(verseId)) return;
        const expandGen = ++state.passageInsertExpandGen;
        state.passageInsertMode = 'expand';
        elements.passageInsertBrowse.hidden = true;
        elements.passageInsertExpand.hidden = false;
        elements.passageInsertTabs.hidden = true;
        elements.passageInsertSearch.hidden = true;
        elements.passageInsertChapters.innerHTML =
            '<div class="passage-picker-loading">Loading…</div>';
        elements.passageInsertConfirm.disabled = true;
        elements.passageInsertExpandCount.textContent = 'Loading…';

        let context;
        try {
            // Public endpoint — works for anonymous localStorage note editors too.
            const response = await fetch(`/api/ranges/context/${verseId}`);
            if (!response.ok) throw new Error('Failed to load surrounding verses');
            context = await response.json();
        } catch (err) {
            console.error(err);
            if (expandGen !== state.passageInsertExpandGen || state.passageInsertMode !== 'expand') {
                return;
            }
            elements.passageInsertChapters.innerHTML =
                '<div class="passage-picker-loading">Could not load surrounding verses.</div>';
            return;
        }

        if (expandGen !== state.passageInsertExpandGen || state.passageInsertMode !== 'expand') {
            return;
        }
        const checkedIds = new Set([verseId]);
        renderPassageInsertExpandChapters(context, checkedIds, verseId);
    }

    function renderPassageInsertExpandChapters(context, checkedIds, anchorVerseId) {
        const sections = [context.prevChapter, context.currentChapter, context.nextChapter]
            .filter(Boolean);

        elements.passageInsertChapters.innerHTML = sections.map(ch => {
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

        updatePassageInsertExpandSelection();

        const anchorCb = elements.passageInsertChapters
            .querySelector(`[data-verse-id="${anchorVerseId}"]`);
        if (anchorCb) anchorCb.closest('.pp-verse-row').scrollIntoView({ block: 'center' });

        elements.passageInsertChapters.querySelectorAll('.pi-verse-cb').forEach(cb => {
            cb.addEventListener('change', updatePassageInsertExpandSelection);
        });
        elements.passageInsertChapters.querySelectorAll('.pp-select-all-cb').forEach(cb => {
            cb.addEventListener('change', () => {
                const section = cb.closest('.pp-chapter-section');
                section.querySelectorAll('.pi-verse-cb')
                    .forEach(v => { v.checked = cb.checked; });
                updatePassageInsertExpandSelection();
            });
        });
    }

    function getPassageInsertCheckedIds() {
        return [...elements.passageInsertChapters.querySelectorAll('.pi-verse-cb:checked')]
            .map(cb => parseInt(cb.dataset.verseId, 10))
            .filter(Number.isFinite)
            .sort((a, b) => a - b);
    }

    function updatePassageInsertExpandSelection() {
        elements.passageInsertChapters.querySelectorAll('.pp-chapter-section').forEach(section => {
            const all = section.querySelectorAll('.pi-verse-cb');
            const chk = section.querySelectorAll('.pi-verse-cb:checked');
            const sa = section.querySelector('.pp-select-all-cb');
            if (!sa) return;
            sa.checked = chk.length === all.length && all.length > 0;
            sa.indeterminate = chk.length > 0 && chk.length < all.length;
        });

        const checked = getPassageInsertCheckedIds();
        const count = checked.length;
        elements.passageInsertExpandCount.textContent =
            count === 0 ? '0 verses selected' :
            count === 1 ? '1 verse selected' :
            `${count} verses selected`;
        elements.passageInsertConfirm.disabled = count === 0 || count > PASSAGE_INSERT_MAX_VERSES;
        if (count > PASSAGE_INSERT_MAX_VERSES) {
            elements.passageInsertExpandCount.textContent =
                `Too many verses (max ${PASSAGE_INSERT_MAX_VERSES})`;
        }
    }

    function confirmPassageInsertExpand() {
        const checked = getPassageInsertCheckedIds();
        if (!checked.length) return;
        if (checked.length > PASSAGE_INSERT_MAX_VERSES) {
            showToast(`Selection limited to ${PASSAGE_INSERT_MAX_VERSES} verses`);
            return;
        }
        const naturalKey = buildNaturalKeyFromIds(checked);
        insertVTokenFromNaturalKey(naturalKey);
    }

    function insertPassageVToken(passageId, naturalKey) {
        const p = state.passages.find(x => x.id === passageId);
        const key = naturalKey || (p && p.naturalKey);
        if (!key) {
            showToast('Could not resolve passage range');
            return;
        }
        insertVTokenFromNaturalKey(key);
    }

    function insertVTokenFromNaturalKey(naturalKey) {
        const ta = state.passageInsertTarget;
        if (!ta) return;
        let token;
        try {
            token = serializeVToken(rangesFromNaturalKey(naturalKey));
        } catch (err) {
            console.error(err);
            showToast('Could not insert scripture link');
            return;
        }
        const start = ta.selectionStart ?? ta.value.length;
        const end = ta.selectionEnd ?? start;
        const before = ta.value.slice(0, start);
        const after = ta.value.slice(end);
        const needsSpaceBefore = before.length > 0 && !/\s$/.test(before);
        const needsSpaceAfter = after.length > 0 && !/^\s/.test(after);
        const insert = (needsSpaceBefore ? ' ' : '') + token + (needsSpaceAfter ? ' ' : '');
        const next = before + insert + after;
        const maxLen = parseInt(ta.getAttribute('maxlength'), 10);
        if (Number.isFinite(maxLen) && next.length > maxLen) {
            showToast(`Not enough room for that link (${maxLen} char limit)`);
            return;
        }
        ta.value = next;
        const caret = before.length + insert.length;
        ta.focus();
        ta.setSelectionRange(caret, caret);
        ta.dispatchEvent(new Event('input'));
        closePassageInsertPicker();
        showToast('Scripture link inserted');
    }

    function handleNoteRangeLinkClick(link) {
        const v = link.dataset.v;
        if (!v) return;
        stageNoteReturnFromOpenEditors();
        closeNoteEditor();
        closeChapterNoteEditor();
        closeLibrary();
        enterRangeMode(v);
    }

    function handleNotePassageLinkClick(link) {
        const id = link.dataset.passageId;
        if (!id) return;
        stageNoteReturnFromOpenEditors();
        closeNoteEditor();
        closeChapterNoteEditor();
        closeLibrary();
        enterPassageMode(id);
    }

    // ── Collections hub modal ──

    async function openCollections() {
        if (!state.currentUser) { showToast('Sign in to use collections'); return; }
        state.audioWasPlayingBeforeModal = state.audioPlaying;
        stopAudioOnUIEvent();
        state.collectionsOpen = true;
        elements.collectionsOverlay.hidden = false;
        await renderCollectionsList();
    }

    function closeCollections() {
        state.collectionsOpen = false;
        elements.collectionsOverlay.hidden = true;
    }

    async function renderCollectionsList() {
        await loadCollectionsFromApi();
        const count = state.collections.length;
        elements.collectionsCount.textContent =
            count === 1 ? '1 collection' : `${count} collections`;

        if (count === 0) {
            elements.collectionsList.innerHTML =
                '<p class="collections-empty">No collections yet.<br>' +
                'A collection is an ordered group of passages from anywhere in the Bible, ' +
                'read together under one label.</p>';
            return;
        }

        elements.collectionsList.innerHTML = state.collections.map(c => {
            const parts = [];
            if (c.passageCount != null) {
                parts.push(c.passageCount === 1 ? '1 passage' : `${c.passageCount} passages`);
            }
            if (c.verseCount != null) {
                parts.push(c.verseCount === 1 ? '1 verse' : `${c.verseCount} verses`);
            }
            return `
            <div class="collections-item" data-collection-id="${c.id}">
                <div class="collections-item-body">
                    <div class="collections-item-label">${escapeHtml(c.label)}</div>
                    <div class="collections-item-meta">${parts.join(' · ')}</div>
                </div>
                <button class="collections-item-edit" data-collection-id="${c.id}" aria-label="Edit collection">Edit</button>
                <button class="collections-item-remove" data-collection-id="${c.id}" aria-label="Delete collection">&times;</button>
            </div>`;
        }).join('');

        // Row click → read the collection
        elements.collectionsList.querySelectorAll('.collections-item').forEach(item => {
            item.addEventListener('click', (e) => {
                if (e.target.closest('.collections-item-edit')) return;
                if (e.target.closest('.collections-item-remove')) return;
                closeCollections();
                enterCollectionMode(parseInt(item.dataset.collectionId, 10));
            });
        });
        elements.collectionsList.querySelectorAll('.collections-item-edit').forEach(btn => {
            btn.addEventListener('click', (e) => {
                e.stopPropagation();
                openCollectionBuilder(parseInt(btn.dataset.collectionId, 10));
            });
        });
        elements.collectionsList.querySelectorAll('.collections-item-remove').forEach(btn => {
            btn.addEventListener('click', async (e) => {
                e.stopPropagation();
                const id = parseInt(btn.dataset.collectionId, 10);
                const c = state.collections.find(x => x.id === id);
                if (!window.confirm(`Delete "${c ? c.label : 'this collection'}"?`)) return;
                try {
                    await libApi(`/api/collections/${id}`, { method: 'DELETE' });
                    await renderCollectionsList();
                } catch (err) {
                    console.error('Failed to delete collection:', err);
                    showToast('Could not delete collection');
                }
            });
        });
    }

    // ── Collection builder modal ──

    const builder = {
        editingId: null,
        queue: [],            // ordered passages [{id, title, reference, naturalKey, verses}]
        bookId: 1,
        chapter: 1,
        currentVerses: [],    // verses rendered in the browser pane
        chaptersByBook: {}    // bookId -> [{chapter, firstVerseId, verseCount}]
    };

    async function getBuilderChapters(bookId) {
        if (!builder.chaptersByBook[bookId]) {
            builder.chaptersByBook[bookId] = await fetchChapters(bookId);
        }
        return builder.chaptersByBook[bookId];
    }

    async function openCollectionBuilder(collectionId = null) {
        if (!state.currentUser) { showToast('Sign in to use collections'); return; }

        state.collectionBuilderOpen = true;
        elements.cbOverlay.hidden = false;
        builder.editingId = collectionId;
        builder.queue = [];
        elements.cbLabel.value = '';
        elements.cbTitle.textContent = collectionId ? 'Edit Collection' : 'New Collection';
        elements.cbVerseList.innerHTML = '<div class="passage-picker-loading">Loading…</div>';

        if (collectionId) {
            try {
                const data = await fetchCollectionVerses(collectionId);
                elements.cbLabel.value = data.label;
                builder.queue = (data.passages || []).map(p => ({
                    id: p.id,
                    title: p.title || '',
                    originalTitle: p.title || '',
                    reference: p.reference,
                    naturalKey: p.naturalKey,
                    verses: p.verses || []
                }));
            } catch (err) {
                console.error('Failed to load collection:', err);
                showToast('Could not load collection');
                closeCollectionBuilder();
                return;
            }
        }

        // Open the browser pane at the current reading position
        const cur = state.pageVerses.find(v => v.id === state.currentVerseId) || state.pageVerses[0];
        builder.bookId = cur ? cur.bookId : 1;
        builder.chapter = cur ? cur.chapter : 1;

        // Populate the book dropdown (books are loaded at init)
        elements.cbBookSelect.innerHTML = state.books
            .map(b => `<option value="${b.id}">${escapeHtml(b.name)}</option>`).join('');
        elements.cbBookSelect.value = builder.bookId;

        renderBuilderQueue();
        await renderBuilderChapter();
    }

    function closeCollectionBuilder() {
        state.collectionBuilderOpen = false;
        elements.cbOverlay.hidden = true;
    }

    async function renderBuilderChapter() {
        elements.cbAddChecked.disabled = true;
        elements.cbAddChecked.textContent = 'Add to collection';
        try {
            const chapters = await getBuilderChapters(builder.bookId);
            if (!chapters.some(c => c.chapter === builder.chapter)) builder.chapter = 1;

            elements.cbChapterSelect.innerHTML = chapters
                .map(c => `<option value="${c.chapter}">${c.chapter}</option>`).join('');
            elements.cbChapterSelect.value = builder.chapter;

            const info = chapters.find(c => c.chapter === builder.chapter);
            const data = await fetchVerses(info.firstVerseId, info.verseCount);
            builder.currentVerses = data.verses;

            const book = state.books.find(b => b.id === builder.bookId);
            elements.cbPrevCh.disabled = builder.bookId === 1 && builder.chapter === 1;
            elements.cbNextCh.disabled = builder.bookId === state.books[state.books.length - 1].id
                && builder.chapter === chapters.length;

            elements.cbVerseList.innerHTML = `
                <div class="pp-chapter-header">
                    <span class="pp-chapter-label">${escapeHtml(book ? book.name : '')} ${builder.chapter}</span>
                    <label class="pp-select-all-label">
                        <input type="checkbox" class="pp-select-all-cb" id="cb-select-all">
                        Select all
                    </label>
                </div>` +
                data.verses.map(v => `
                <label class="pp-verse-row">
                    <input type="checkbox" class="pp-verse-cb" data-verse-id="${v.id}">
                    <span class="pp-verse-num">${v.verse}</span>
                    <span class="pp-verse-text">${escapeHtml(v.text)}</span>
                </label>`).join('');

            elements.cbVerseList.querySelectorAll('.pp-verse-cb').forEach(cb => {
                cb.addEventListener('change', updateBuilderAddButton);
            });
            const selectAll = document.getElementById('cb-select-all');
            selectAll.addEventListener('change', () => {
                elements.cbVerseList.querySelectorAll('.pp-verse-cb')
                    .forEach(cb => { cb.checked = selectAll.checked; });
                updateBuilderAddButton();
            });
            elements.cbVerseList.scrollTop = 0;
        } catch (err) {
            console.error('Failed to load chapter:', err);
            elements.cbVerseList.innerHTML =
                '<div class="passage-picker-loading">Could not load verses.</div>';
        }
    }

    function updateBuilderAddButton() {
        const boxes = elements.cbVerseList.querySelectorAll('.pp-verse-cb');
        const checked = elements.cbVerseList.querySelectorAll('.pp-verse-cb:checked');
        const selectAll = document.getElementById('cb-select-all');
        if (selectAll) {
            selectAll.checked = checked.length === boxes.length && boxes.length > 0;
            selectAll.indeterminate = checked.length > 0 && checked.length < boxes.length;
        }
        elements.cbAddChecked.disabled = checked.length === 0;
        elements.cbAddChecked.textContent = checked.length === 0
            ? 'Add to collection'
            : checked.length === 1 ? 'Add 1 verse' : `Add ${checked.length} verses`;
    }

    async function addCheckedToQueue() {
        const checkedIds = [...elements.cbVerseList.querySelectorAll('.pp-verse-cb:checked')]
            .map(cb => parseInt(cb.dataset.verseId, 10));
        if (!checkedIds.length) return;

        const snippets = checkedIds.map(id => builder.currentVerses.find(v => v.id === id));
        const runs = groupIntoPassages(snippets);
        const pendingVerseCount = runs.reduce((n, r) => n + r.count, 0)
            + builder.queue.reduce((n, p) => n + (p.verses ? p.verses.length : 0), 0);
        if (pendingVerseCount > 500) {
            showToast('Collections are limited to 500 verses');
            return;
        }

        // Stage locally — Passage rows are created only when the collection is saved,
        // so Cancel / remove does not leave orphan catalog entries.
        const addedLabels = [];
        for (const run of runs) {
            const naturalKey = buildNaturalKeyFromIds(run.verseIds);
            const verses = snippets.slice(run.startIndex, run.startIndex + run.count);
            builder.queue.push({
                id: null,
                pending: true,
                title: '',
                originalTitle: '',
                reference: run.reference,
                naturalKey,
                verses
            });
            addedLabels.push(run.reference);
        }
        elements.cbVerseList.querySelectorAll('.pp-verse-cb:checked')
            .forEach(cb => { cb.checked = false; });
        updateBuilderAddButton();
        renderBuilderQueue();
        showToast(`Added ${addedLabels.join(', ')}`);
    }

    function renderBuilderQueue() {
        const verseCount = builder.queue.reduce((n, p) => n + (p.verses ? p.verses.length : 0), 0);
        elements.cbQueueCount.textContent = builder.queue.length === 0
            ? 'No passages yet'
            : `${builder.queue.length === 1 ? '1 passage' : `${builder.queue.length} passages`} · ` +
              `${verseCount === 1 ? '1 verse' : `${verseCount} verses`}`;

        elements.cbQueueList.innerHTML = builder.queue.map((p, i) => `
            <li class="cb-queue-item" data-seg="${i}">
                <div class="cb-queue-main">
                    <span class="cb-queue-ref">${escapeHtml(passageDisplayLabel(p))}</span>
                    <input type="text" class="cb-queue-title" data-seg="${i}"
                        placeholder="Optional title…" maxlength="100"
                        value="${escapeHtml(p.title || '')}"
                        aria-label="Optional passage title">
                </div>
                <span class="cb-queue-btns">
                    <button class="cb-seg-up" data-seg="${i}" aria-label="Move up" ${i === 0 ? 'disabled' : ''}>&#8593;</button>
                    <button class="cb-seg-down" data-seg="${i}" aria-label="Move down" ${i === builder.queue.length - 1 ? 'disabled' : ''}>&#8595;</button>
                    <button class="cb-seg-remove" data-seg="${i}" aria-label="Remove">&times;</button>
                </span>
            </li>`).join('');

        // Titles are local until Save. Same Passage id (or pending naturalKey) shares one title.
        elements.cbQueueList.querySelectorAll('.cb-queue-title').forEach(input => {
            input.addEventListener('change', () => {
                const i = parseInt(input.dataset.seg, 10);
                const item = builder.queue[i];
                if (!item) return;
                const title = input.value.trim();
                item.title = title;
                builder.queue.forEach((p, j) => {
                    if (j === i) return;
                    const same = (item.id && p.id === item.id)
                        || (!item.id && !p.id && p.naturalKey === item.naturalKey);
                    if (same) p.title = title;
                });
                elements.cbQueueList.querySelectorAll('.cb-queue-item').forEach(li => {
                    const j = parseInt(li.dataset.seg, 10);
                    const p = builder.queue[j];
                    if (!p) return;
                    const same = (item.id && p.id === item.id)
                        || (!item.id && !p.id && p.naturalKey === item.naturalKey);
                    if (!same) return;
                    const titleInput = li.querySelector('.cb-queue-title');
                    const refEl = li.querySelector('.cb-queue-ref');
                    if (titleInput && titleInput !== input) titleInput.value = title;
                    if (refEl) refEl.textContent = passageDisplayLabel(p);
                });
            });
        });

        updateBuilderSaveState();
    }

    /** Move or remove one passage in the queue. */
    function spliceQueueSegment(segIndex, action) {
        if (segIndex < 0 || segIndex >= builder.queue.length) return;
        const slice = builder.queue.splice(segIndex, 1);
        if (action === 'up') {
            builder.queue.splice(segIndex - 1, 0, ...slice);
        } else if (action === 'down') {
            builder.queue.splice(segIndex + 1, 0, ...slice);
        }
        // 'remove' → slice is simply dropped
        renderBuilderQueue();
    }

    function updateBuilderSaveState() {
        elements.cbSave.disabled =
            elements.cbLabel.value.trim() === '' || builder.queue.length === 0;
    }

    async function saveCollectionFromBuilder() {
        const label = elements.cbLabel.value.trim();
        if (!label || !builder.queue.length) return;

        elements.cbSave.disabled = true;
        try {
            // Passages + titles materialize in the same backend transaction as the collection
            const members = builder.queue.map(p => {
                if (p.id) {
                    const updateTitle = (p.title || '') !== (p.originalTitle || '');
                    return {
                        passageId: p.id,
                        updateTitle,
                        title: updateTitle ? (p.title || null) : null
                    };
                }
                const hasTitle = !!(p.title && p.title.trim());
                return {
                    naturalKey: p.naturalKey,
                    updateTitle: hasTitle,
                    title: hasTitle ? p.title.trim() : null
                };
            });
            const body = JSON.stringify({ label, members });
            if (builder.editingId) {
                await libApi(`/api/collections/${builder.editingId}`, {
                    method: 'PUT', headers: { 'Content-Type': 'application/json' }, body
                });
            } else {
                await libApi('/api/collections', {
                    method: 'POST', headers: { 'Content-Type': 'application/json' }, body
                });
            }
            await loadPassagesFromApi();
            await loadCollectionsFromApi();
            closeCollectionBuilder();
            showToast(`Saved "${label}"`);
            if (state.collectionsOpen) renderCollectionsList();
            if (state.collection && state.collection.kind === 'collection'
                && state.collection.id === builder.editingId) {
                enterCollectionMode(builder.editingId, { push: false });
            }
        } catch (err) {
            console.error('Failed to save collection:', err);
            showToast(err.message.includes('400')
                ? 'Could not save — you may already have a collection with that label'
                : 'Could not save collection');
            elements.cbSave.disabled = false;
        }
    }

    function getNextTagColorIndex() {
        const usedIndices = Object.values(state.tags).map(t => t.colorIndex);
        for (let i = 0; i < TAG_COLORS.length; i++) {
            if (!usedIndices.includes(i)) return i;
        }
        return Object.keys(state.tags).length % TAG_COLORS.length;
    }

    async function createTag(name) {
        const trimmed = name.trim().substring(0, 20);
        if (!trimmed) return null;

        if (Object.keys(state.tags).length >= 50) {
            alert('Maximum of 50 tags reached');
            return null;
        }

        // Check for duplicate name
        const exists = Object.values(state.tags).some(t =>
            t.name.toLowerCase() === trimmed.toLowerCase()
        );
        if (exists) {
            alert('A tag with this name already exists');
            return null;
        }

        if (state.currentUser) {
            try {
                const colorIndex = getNextTagColorIndex();
                const tag = await libApi('/api/library/tags', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ name: trimmed, colorIndex })
                });
                state.tags[tag.id] = {
                    id: tag.id,
                    name: tag.name,
                    colorIndex: tag.colorIndex,
                    createdAt: new Date(tag.createdAt).getTime()
                };
                return tag.id;
            } catch (err) {
                console.error('Failed to create tag:', err);
                return null;
            }
        } else {
            const id = 'tag-' + Date.now();
            state.tags[id] = {
                id,
                name: trimmed,
                colorIndex: getNextTagColorIndex(),
                createdAt: Date.now()
            };
            saveTags();
            return id;
        }
    }

    function addTagToVerse(verseId, tagId) {
        const verse = state.savedVerses[verseId];
        if (!verse) return;
        if (verse.tagIds.length >= 5) {
            alert('Maximum of 5 tags per verse');
            return;
        }
        if (!verse.tagIds.includes(tagId)) {
            verse.tagIds.push(tagId);
            if (state.currentUser) {
                libApi(`/api/library/verses/${verseId}/tags/${tagId}`, { method: 'POST' })
                    .catch(err => console.error('Failed to add tag to verse:', err));
            } else {
                saveSavedVerses();
            }
        }
    }

    function removeTagFromVerse(verseId, tagId) {
        const verse = state.savedVerses[verseId];
        if (!verse) return;
        verse.tagIds = verse.tagIds.filter(id => id !== tagId);
        if (state.currentUser) {
            libApi(`/api/library/verses/${verseId}/tags/${tagId}`, { method: 'DELETE' })
                .catch(err => console.error('Failed to remove tag from verse:', err));
        } else {
            saveSavedVerses();
        }
    }

    function deleteTag(tagId) {
        // Remove from tag registry
        delete state.tags[tagId];
        // Scrub tagId from every saved verse
        Object.values(state.savedVerses).forEach(v => {
            v.tagIds = v.tagIds.filter(id => id !== tagId);
        });
        if (state.currentUser) {
            libApi(`/api/library/tags/${tagId}`, { method: 'DELETE' })
                .catch(err => console.error('Failed to delete tag:', err));
        } else {
            saveTags();
            saveSavedVerses();
        }
        renderTagPicker();
        renderPage();
    }

    function setVerseNote(verseId, note) {
        const verse = state.savedVerses[verseId];
        if (!verse) return;
        verse.note = note.substring(0, 500);
        if (state.currentUser) {
            libApi(`/api/library/verses/${verseId}/note`, {
                method: 'PATCH',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ note: verse.note })
            }).catch(err => console.error('Failed to update note:', err));
        } else {
            saveSavedVerses();
        }
    }

    // ============================================
    // Library (Saved Verses)
    // ============================================

    let librarySearchTimeout = null;
    let categoryCombo = null;
    let bookCombo = null;
    let tagCombo = null;

    function getBookOptions() {
        const { categoryIds } = state.libraryFilters;
        let booksToShow = state.books;

        if (categoryIds.length > 0) {
            const validBookIds = new Set();
            categoryIds.forEach(catId => {
                const cat = BOOK_CATEGORIES.find(c => c.id === catId);
                if (cat) {
                    cat.bookIds.forEach(bid => validBookIds.add(bid));
                }
            });
            booksToShow = state.books.filter(b => validBookIds.has(b.id));
        }

        return booksToShow.map(b => ({ id: String(b.id), label: b.name }));
    }

    function getTagOptions() {
        return Object.values(state.tags)
            .sort((a, b) => a.name.localeCompare(b.name))
            .map(t => ({
                id: t.id,
                label: t.name,
                color: TAG_COLORS[t.colorIndex]
            }));
    }

    function initLibraryCombos() {
        // Category combo
        categoryCombo = createMultiSelectCombo(elements.libraryCategories, {
            options: BOOK_CATEGORIES.map(c => ({ id: c.id, label: c.name })),
            selected: state.libraryFilters.categoryIds,
            placeholder: 'Filter by category...',
            onChange: (selected) => {
                state.libraryFilters.categoryIds = selected;
                // Update book combo options and clear invalid selections
                updateBookFilterForCategories();
                bookCombo.setSelected(state.libraryFilters.bookIds);
                bookCombo.render();
                updateFiltersBadge();
                renderLibraryResults();
            }
        });

        // Book combo (uses dynamic options based on category selection)
        bookCombo = createMultiSelectCombo(elements.libraryBooks, {
            selected: state.libraryFilters.bookIds,
            placeholder: 'Filter by book...',
            getOptions: getBookOptions,
            onChange: (selected) => {
                state.libraryFilters.bookIds = selected;
                updateFiltersBadge();
                renderLibraryResults();
            }
        });

        // Tag combo (uses dynamic options)
        tagCombo = createMultiSelectCombo(elements.libraryTags, {
            selected: state.libraryFilters.tagIds,
            placeholder: 'Filter by tag...',
            getOptions: getTagOptions,
            onChange: (selected) => {
                state.libraryFilters.tagIds = selected;
                updateFiltersBadge();
                renderLibraryResults();
            }
        });
    }

    function updateBookFilterForCategories() {
        const { categoryIds, bookIds } = state.libraryFilters;

        if (categoryIds.length === 0) {
            return;
        }

        const validBookIds = new Set();
        categoryIds.forEach(catId => {
            const cat = BOOK_CATEGORIES.find(c => c.id === catId);
            if (cat) {
                cat.bookIds.forEach(bid => validBookIds.add(bid));
            }
        });

        state.libraryFilters.bookIds = bookIds.filter(bid => validBookIds.has(parseInt(bid)));
    }

    function openLibrary() {
        state.audioWasPlayingBeforeModal = state.audioPlaying;
        stopAudioOnUIEvent();
        state.libraryOpen = true;
        elements.libraryOverlay.hidden = false;

        // Always open on the Saved Verses tab for predictability
        state.libraryView = 'verses';
        elements.libraryTabVerses.classList.add('active');
        elements.libraryTabChapterNotes.classList.remove('active');
        elements.libraryFiltersBar.hidden = false;

        // Load expanded state from localStorage
        loadLibraryFiltersExpandedState();
        updateLibraryFiltersUI();

        // Initialize combos if not already done
        if (!categoryCombo) {
            initLibraryCombos();
        } else {
            // Reset combos to current filter state
            categoryCombo.setSelected(state.libraryFilters.categoryIds);
            bookCombo.setSelected(state.libraryFilters.bookIds);
            tagCombo.setSelected(state.libraryFilters.tagIds);
        }

        renderLibraryResults();
    }

    function closeLibrary() {
        state.libraryOpen = false;
        elements.libraryOverlay.hidden = true;
        // Reset filters when closing
        state.libraryFilters = {
            search: '',
            tagIds: [],
            categoryIds: [],
            bookIds: [],
            sort: 'date-desc'
        };
        elements.librarySearch.value = '';
        elements.librarySort.value = 'date-desc';

        // Clear combos
        if (categoryCombo) categoryCombo.clear();
        if (bookCombo) bookCombo.clear();
        if (tagCombo) tagCombo.clear();
    }

    async function openMemorization() {
        state.audioWasPlayingBeforeModal = state.audioPlaying;
        stopAudioOnUIEvent();
        state.memorizationOpen = true;
        elements.memorizationOverlay.hidden = false;
        await renderMemorizationList();
    }

    function closeMemorization() {
        state.memorizationOpen = false;
        elements.memorizationOverlay.hidden = true;
    }

    async function renderMemorizationList() {
        let entries;
        try {
            entries = await libApi('/api/memorization/queue');
        } catch (_) {
            entries = [];
        }

        // Sync richer state cache
        state.memorizedPassages = {};
        state.memorizedEntries = [];
        entries.forEach(e => {
            state.memorizedPassages[e.passage.naturalKey] = e.id;
            state.memorizedEntries.push({
                id: e.id, fromVerseId: e.passage.fromVerseId,
                toVerseId: e.passage.toVerseId, naturalKey: e.passage.naturalKey
            });
        });

        const count = entries.length;
        elements.memorizationResultsCount.textContent =
            count === 0 ? '0 passages' :
            count === 1 ? '1 passage' :
            `${count} passages`;

        // Compute entries due today (nextReviewAt null = never reviewed, counts as due)
        const todayStr = new Date().toISOString().slice(0, 10);
        const dueEntries = entries.filter(e => !e.nextReviewAt || e.nextReviewAt <= todayStr);
        const dueCount = dueEntries.length;
        state.memorizationDueEntries = dueEntries;
        if (dueCount > 0) {
            elements.memorizationDueCount.textContent =
                dueCount === 1 ? '1 due today' : `${dueCount} due today`;
            elements.memorizationDueBar.hidden = false;
        } else {
            elements.memorizationDueBar.hidden = true;
        }

        if (count === 0) {
            elements.memorizationList.innerHTML =
                '<p class="memorization-empty">No passages memorized yet.<br>' +
                'Press <kbd>m</kbd> while reading to add the current verse.</p>';
            return;
        }

        elements.memorizationList.innerHTML = entries.map(entry => {
            const dots = Array.from({ length: 5 }, (_, i) =>
                `<span class="mastery-dot${i < entry.masteryLevel ? ' filled' : ''}"></span>`
            ).join('');
            // Range reference: show "John 3:16" or "John 3:16 – 21"
            const isMulti = entry.fromVerseRef !== entry.toVerseRef;
            const refDisplay = isMulti
                ? `${escapeHtml(entry.fromVerseRef)} &ndash; ${escapeHtml(entry.toVerseRef)}`
                : escapeHtml(entry.fromVerseRef);
            // Preview: first verse text
            const previewText = entry.verses && entry.verses.length
                ? entry.verses[0].text
                : '';
            return `
            <div class="memorization-item" data-entry-id="${escapeHtml(entry.id)}"
                 data-verse-id="${entry.passage.fromVerseId}">
                <div class="memorization-item-body">
                    <div class="memorization-item-ref">${refDisplay}</div>
                    <div class="memorization-item-text">${escapeHtml(previewText)}</div>
                    <div class="memorization-item-mastery">${dots}</div>
                    <button class="memorization-practice-btn" data-entry-id="${escapeHtml(entry.id)}"
                            aria-label="Practice this passage">Practice</button>
                </div>
                <button class="memorization-item-remove" data-entry-id="${escapeHtml(entry.id)}"
                        aria-label="Remove from queue">&times;</button>
            </div>`;
        }).join('');

        // Navigate on row click (but not Practice or Remove buttons)
        elements.memorizationList.querySelectorAll('.memorization-item').forEach(item => {
            item.addEventListener('click', (e) => {
                if (e.target.closest('.memorization-item-remove')) return;
                if (e.target.closest('.memorization-practice-btn')) return;
                const verseId = parseInt(item.dataset.verseId, 10);
                closeMemorization();
                goToVerse(verseId);
            });
        });

        // Practice buttons — open training modal
        elements.memorizationList.querySelectorAll('.memorization-practice-btn').forEach(btn => {
            btn.addEventListener('click', (e) => {
                e.stopPropagation();
                const entryId = btn.dataset.entryId;
                const entry = entries.find(en => en.id === entryId);
                if (entry) {
                    sessionStorage.setItem('kjv_training_entry', JSON.stringify(entry));
                    window.location.href = '/train?from=reading';
                }
            });
        });

        // Remove buttons
        elements.memorizationList.querySelectorAll('.memorization-item-remove').forEach(btn => {
            btn.addEventListener('click', async (e) => {
                e.stopPropagation();
                const entryId = btn.dataset.entryId;
                // Remove from local state maps
                Object.keys(state.memorizedPassages).forEach(key => {
                    if (state.memorizedPassages[key] === entryId) {
                        delete state.memorizedPassages[key];
                    }
                });
                state.memorizedEntries = state.memorizedEntries.filter(e => e.id !== entryId);
                // Remove from DOM immediately
                btn.closest('.memorization-item').remove();
                const remaining = elements.memorizationList.querySelectorAll('.memorization-item').length;
                elements.memorizationResultsCount.textContent =
                    remaining === 0 ? '0 passages' :
                    remaining === 1 ? '1 passage' :
                    `${remaining} passages`;
                if (remaining === 0) {
                    elements.memorizationList.innerHTML =
                        '<p class="memorization-empty">No passages memorized yet.<br>' +
                        'Press <kbd>m</kbd> while reading to add the current verse.</p>';
                }
                await remeasureCurrentPage();
                try {
                    await libApi(`/api/memorization/queue/${entryId}`, { method: 'DELETE' });
                } catch (err) {
                    console.error('Failed to remove memorization entry:', err);
                }
            });
        });
    }

    function openMobileMenu() {
        // Reflect current verse's saved state in the bookmark label
        if (elements.mobileMenuBookmarkLabel) {
            elements.mobileMenuBookmarkLabel.textContent =
                state.savedVerses[state.currentVerseId] ? 'Unsave Verse' : 'Save Verse';
        }
        // Dot indicators when the current chapter/book has a note (headers/title pencils hidden on mobile)
        const chapterNoteLabel = document.getElementById('mobile-menu-chapter-note-label');
        if (chapterNoteLabel) {
            const ref = getCurrentChapterRef();
            const hasNote = ref && state.chapterNotes[chapterKey(ref.bookId, ref.chapter)];
            chapterNoteLabel.textContent = hasNote ? 'Chapter Note •' : 'Chapter Note';
        }
        const bookNoteLabel = document.getElementById('mobile-menu-book-note-label');
        if (bookNoteLabel) {
            const bookRef = getCurrentBookRef();
            const hasBookNote = bookRef && state.bookNotes[bookRef.bookId];
            bookNoteLabel.textContent = hasBookNote ? 'Book Note •' : 'Book Note';
        }
        state.mobileMenuOpen = true;
        elements.mobileMenuOverlay.hidden = false;
    }

    function closeMobileMenu() {
        state.mobileMenuOpen = false;
        elements.mobileMenuOverlay.hidden = true;
    }

    function toggleLibraryFilters() {
        state.libraryFiltersExpanded = !state.libraryFiltersExpanded;
        updateLibraryFiltersUI();
        // Persist to localStorage
        localStorage.setItem('kjv_library_filters_expanded', state.libraryFiltersExpanded);
    }

    function updateLibraryFiltersUI() {
        const expanded = state.libraryFiltersExpanded;
        elements.libraryFiltersToggle.setAttribute('aria-expanded', expanded);
        elements.libraryAdditionalFilters.hidden = !expanded;

        // Update badge with active filter count
        updateFiltersBadge();
    }

    function updateFiltersBadge() {
        const { categoryIds, bookIds, tagIds } = state.libraryFilters;
        const count = categoryIds.length + bookIds.length + tagIds.length;

        if (count > 0) {
            elements.libraryFiltersBadge.textContent = count;
            elements.libraryFiltersBadge.hidden = false;
        } else {
            elements.libraryFiltersBadge.hidden = true;
        }
    }

    function loadLibraryFiltersExpandedState() {
        const saved = localStorage.getItem('kjv_library_filters_expanded');
        state.libraryFiltersExpanded = saved === 'true';
    }

    function filterSavedVerses() {
        let verses = Object.values(state.savedVerses);
        const { search, tagIds, categoryId, bookId } = state.libraryFilters;

        // We need verse details for filtering, so we'll return the saved verse objects
        // and fetch details during rendering
        return verses.filter(sv => {
            // Tag filter (OR logic)
            if (tagIds.length > 0) {
                const hasAnyTag = tagIds.some(tid => sv.tagIds.includes(tid));
                if (!hasAnyTag) return false;
            }
            return true;
        });
    }

    function setLibraryView(view) {
        state.libraryView = view;
        const chapterNotesActive = view === 'chapter-notes';
        elements.libraryTabVerses.classList.toggle('active', !chapterNotesActive);
        elements.libraryTabChapterNotes.classList.toggle('active', chapterNotesActive);
        // Filter bar is verse-specific — hide it on the chapter notes tab
        elements.libraryFiltersBar.hidden = chapterNotesActive;
        renderLibraryResults();
    }

    function renderNotesList() {
        if (!state.currentUser) {
            elements.libraryResults.innerHTML =
                '<p class="library-empty">Notes are saved to your account. <a href="/login.html">Sign in</a> to create them.</p>';
            elements.libraryResultsCount.textContent = '0 notes';
            return;
        }

        const notePreview = (note) => {
            const plain = stripNoteMarkdown(note);
            return plain.length > 200 ? plain.substring(0, 200) + '...' : plain;
        };

        // Canonical order, book note (if any) leading its book's chapter notes
        const chapterNotes = Object.values(state.chapterNotes)
            .sort((a, b) => a.bookId - b.bookId || a.chapter - b.chapter);
        const bookIds = [...new Set([
            ...Object.values(state.bookNotes).map(n => n.bookId),
            ...chapterNotes.map(n => n.bookId)
        ])].sort((a, b) => a - b);

        const total = chapterNotes.length + Object.keys(state.bookNotes).length;
        elements.libraryResultsCount.textContent = `${total} note${total !== 1 ? 's' : ''}`;

        if (total === 0) {
            elements.libraryResults.innerHTML =
                '<p class="library-empty">No notes yet. Press <kbd>c</kbd> for a chapter note or <kbd>B</kbd> for a book note while reading.</p>';
            return;
        }

        elements.libraryResults.innerHTML = bookIds.map(bookId => {
            let html = '';
            const bookNote = state.bookNotes[bookId];
            if (bookNote) {
                html += `
                    <div class="library-item library-item-book-note" data-first-verse-id="${bookNote.firstVerseId}">
                        <div class="library-item-ref">${escapeHtml(bookNote.bookName)} <span class="book-note-badge">Book note</span></div>
                        <div class="library-item-note">${escapeHtml(notePreview(bookNote.note))}</div>
                    </div>
                `;
            }
            html += chapterNotes.filter(n => n.bookId === bookId).map(n => {
                const isPsalm = n.bookName === 'Psalms' || n.bookName === 'Psalm';
                const ref = `${isPsalm ? 'Psalm' : n.bookName} ${n.chapter}`;
                return `
                    <div class="library-item" data-first-verse-id="${n.firstVerseId}">
                        <div class="library-item-ref">${ref}</div>
                        <div class="library-item-note">${escapeHtml(notePreview(n.note))}</div>
                    </div>
                `;
            }).join('');
            return html;
        }).join('');

        elements.libraryResults.querySelectorAll('.library-item').forEach(item => {
            item.addEventListener('click', async () => {
                const verseId = parseInt(item.dataset.firstVerseId);
                closeLibrary();
                await goToVerse(verseId);
            });
        });
    }

    async function renderLibraryResults() {
        if (state.libraryView === 'chapter-notes') {
            renderNotesList();
            return;
        }

        let filteredVerses = filterSavedVerses();

        if (filteredVerses.length === 0 && Object.keys(state.savedVerses).length === 0) {
            elements.libraryResults.innerHTML = '<p class="library-empty">No saved verses yet. Press <kbd>b</kbd> while reading to save a verse.</p>';
            elements.libraryResultsCount.textContent = '0 saved verses';
            return;
        }

        // Fetch all verse details
        const verseDetails = await Promise.all(
            filteredVerses.map(sv => fetchVerse(sv.id))
        );

        // Create verse map for easier access
        const verseMap = {};
        filteredVerses.forEach((sv, i) => {
            verseMap[sv.id] = verseDetails[i];
        });

        // Apply text search filter (needs verse text)
        const { search, categoryIds, bookIds, sort } = state.libraryFilters;

        if (search) {
            const searchLower = search.toLowerCase();
            filteredVerses = filteredVerses.filter(sv => {
                const vd = verseMap[sv.id];
                const textMatch = vd.text.toLowerCase().includes(searchLower);
                const noteMatch = sv.note && sv.note.toLowerCase().includes(searchLower);
                return textMatch || noteMatch;
            });
        }

        // Apply category filter (OR logic - matches any selected category)
        if (categoryIds.length > 0) {
            const validBookIds = new Set();
            categoryIds.forEach(catId => {
                const cat = BOOK_CATEGORIES.find(c => c.id === catId);
                if (cat) {
                    cat.bookIds.forEach(bid => validBookIds.add(bid));
                }
            });
            filteredVerses = filteredVerses.filter(sv => {
                const vd = verseMap[sv.id];
                return validBookIds.has(vd.bookId);
            });
        }

        // Apply book filter (OR logic - matches any selected book)
        if (bookIds.length > 0) {
            const bookIdSet = new Set(bookIds.map(id => parseInt(id)));
            filteredVerses = filteredVerses.filter(sv => {
                const vd = verseMap[sv.id];
                return bookIdSet.has(vd.bookId);
            });
        }

        // Sort
        if (sort === 'date-desc') {
            filteredVerses.sort((a, b) => b.savedAt - a.savedAt);
        } else if (sort === 'date-asc') {
            filteredVerses.sort((a, b) => a.savedAt - b.savedAt);
        } else if (sort === 'canonical') {
            filteredVerses.sort((a, b) => a.id - b.id);
        }

        // Update count
        elements.libraryResultsCount.textContent =
            `${filteredVerses.length} saved verse${filteredVerses.length !== 1 ? 's' : ''}`;

        if (filteredVerses.length === 0) {
            elements.libraryResults.innerHTML = '<p class="library-no-results">No verses match your filters.</p>';
            return;
        }

        // Render results
        elements.libraryResults.innerHTML = filteredVerses.map(sv => {
            const v = verseMap[sv.id];
            const tagHtml = sv.tagIds.map(tid => {
                const tag = state.tags[tid];
                return tag ? `<span class="library-item-tag" style="background:${TAG_COLORS[tag.colorIndex]}">${escapeHtml(tag.name)}</span>` : '';
            }).join('');

            const textPreview = v.text.length > 150 ? v.text.substring(0, 150) + '...' : v.text;
            const notePreview = sv.note ? (sv.note.length > 100 ? sv.note.substring(0, 100) + '...' : sv.note) : '';

            return `
                <div class="library-item" data-verse-id="${sv.id}">
                    <div class="library-item-ref">${v.book} ${v.chapter}:${v.verse}</div>
                    <div class="library-item-text">${escapeHtml(textPreview)}</div>
                    ${tagHtml ? `<div class="library-item-tags">${tagHtml}</div>` : ''}
                    ${notePreview ? `<div class="library-item-note">${escapeHtml(notePreview)}</div>` : ''}
                </div>
            `;
        }).join('');

        // Click handlers
        elements.libraryResults.querySelectorAll('.library-item').forEach(item => {
            item.addEventListener('click', async () => {
                const wasPlaying = state.audioWasPlayingBeforeModal;
                const verseId = parseInt(item.dataset.verseId);
                closeLibrary();
                await goToVerse(verseId);
                if (wasPlaying) restartAudioIfPlaying(wasPlaying);
            });
        });
    }

    function handleLibrarySearchInput() {
        clearTimeout(librarySearchTimeout);
        librarySearchTimeout = setTimeout(() => {
            state.libraryFilters.search = elements.librarySearch.value.trim();
            renderLibraryResults();
        }, 300);
    }

    function handleLibrarySortChange() {
        state.libraryFilters.sort = elements.librarySort.value;
        renderLibraryResults();
    }

    // ============================================
    // Tag Picker
    // ============================================

    async function openTagPicker(verseId) {
        stopAudioOnUIEvent();
        if (!state.savedVerses[verseId]) {
            await toggleSaveVerse(verseId);
        }
        state.tagPickerVerseId = verseId;
        state.tagPickerOpen = true;
        elements.tagPickerOverlay.hidden = false;

        // Set verse reference
        const verse = state.pageVerses.find(v => v.id === verseId);
        if (verse) {
            elements.tagPickerVerseRef.textContent = `${verse.book} ${verse.chapter}:${verse.verse}`;
        }

        renderTagPicker();
    }

    function closeTagPicker() {
        state.tagPickerOpen = false;
        state.tagPickerVerseId = null;
        elements.tagPickerOverlay.hidden = true;
        elements.newTagInput.value = '';
    }

    function renderTagPicker() {
        const verse = state.savedVerses[state.tagPickerVerseId];
        if (!verse) return;

        const tags = Object.values(state.tags).sort((a, b) => a.name.localeCompare(b.name));

        if (tags.length === 0) {
            elements.tagList.innerHTML = '<p class="no-tags">No tags yet. Create one below.</p>';
        } else {
            elements.tagList.innerHTML = tags.map(tag => {
                const isChecked = verse.tagIds.includes(tag.id);
                const isDisabled = !isChecked && verse.tagIds.length >= 5;
                return `
                    <label class="tag-checkbox-item" style="--tag-color: ${TAG_COLORS[tag.colorIndex]}">
                        <input type="checkbox"
                               data-tag-id="${tag.id}"
                               ${isChecked ? 'checked' : ''}
                               ${isDisabled ? 'disabled' : ''}>
                        <span class="tag-color-dot"></span>
                        <span class="tag-name">${escapeHtml(tag.name)}</span>
                        <button class="tag-delete-btn" data-tag-id="${tag.id}" title="Delete tag" aria-label="Delete tag ${escapeHtml(tag.name)}">×</button>
                    </label>
                `;
            }).join('');

            // Checkbox handlers
            elements.tagList.querySelectorAll('input[type="checkbox"]').forEach(cb => {
                cb.addEventListener('change', () => {
                    const tagId = cb.dataset.tagId;
                    if (cb.checked) {
                        addTagToVerse(state.tagPickerVerseId, tagId);
                    } else {
                        removeTagFromVerse(state.tagPickerVerseId, tagId);
                    }
                    renderTagPicker();
                    renderPage();
                });
            });

            // Delete tag handlers
            elements.tagList.querySelectorAll('.tag-delete-btn').forEach(btn => {
                btn.addEventListener('click', (e) => {
                    e.preventDefault();
                    const tagId = btn.dataset.tagId;
                    const tagName = state.tags[tagId]?.name || 'this tag';
                    if (confirm(`Delete tag "${tagName}"? It will be removed from all saved verses.`)) {
                        deleteTag(tagId);
                    }
                });
            });
        }
    }

    async function handleCreateTag() {
        const name = elements.newTagInput.value.trim();
        if (!name) return;

        const tagId = await createTag(name);
        if (tagId && state.tagPickerVerseId) {
            addTagToVerse(state.tagPickerVerseId, tagId);
            elements.newTagInput.value = '';
            renderTagPicker();
            renderPage();
        }
    }

    // ============================================
    // Note Editor
    // ============================================

    // bookId -> [{chapter, firstVerseId, verseCount}], fetched on demand. Kept
    // separate from state.chapters (which tracks the book/chapter selector's
    // currently-displayed book) since a verse note's own book may differ —
    // e.g. while reading a cross-book passage collection.
    const chaptersByBookCache = {};
    async function getChaptersForBook(bookId) {
        if (!chaptersByBookCache[bookId]) {
            chaptersByBookCache[bookId] = await fetchChapters(bookId);
        }
        return chaptersByBookCache[bookId];
    }

    /** Renderer ctx for a verse note's [N] links: same-chapter verse shorthand. */
    async function getRenderCtxForVerse(verseId) {
        const verse = state.pageVerses.find(v => v.id === verseId);
        if (!verse) return null;
        const chapters = await getChaptersForBook(verse.bookId);
        const chapterInfo = chapters.find(c => c.chapter === verse.chapter);
        if (!chapterInfo) return null;
        return { type: 'verse', firstVerseId: chapterInfo.firstVerseId, verseCount: chapterInfo.verseCount };
    }

    async function openNoteEditor(verseId) {
        stopAudioOnUIEvent();
        if (!state.savedVerses[verseId]) {
            await toggleSaveVerse(verseId);
        }
        state.noteEditorVerseId = verseId;
        state.noteEditorOpen = true;
        elements.noteEditorOverlay.hidden = false;

        // Set verse reference
        const verse = state.pageVerses.find(v => v.id === verseId);
        if (verse) {
            elements.noteEditorVerseRef.textContent = `${verse.book} ${verse.chapter}:${verse.verse}`;
        }

        // View-first when a note exists; straight to edit when empty
        const savedVerse = state.savedVerses[verseId];
        await setNoteMode(savedVerse && savedVerse.note ? 'view' : 'edit');
    }

    async function setNoteMode(mode) {
        const verseId = state.noteEditorVerseId;
        const savedVerse = state.savedVerses[verseId];
        const note = savedVerse ? savedVerse.note : '';

        if (mode === 'view') {
            elements.noteView.innerHTML = note ? renderNoteMarkdown(note, await getRenderCtxForVerse(verseId)) : '';
            // The editor may have been closed (or moved to a different verse)
            // while the chapter lookup above was in flight.
            if (state.noteEditorVerseId !== verseId || !state.noteEditorOpen) return;
            elements.noteView.hidden = false;
            elements.noteViewActions.hidden = false;
            elements.noteEdit.hidden = true;
            hydrateRangeLinkLabels(elements.noteView);
        } else {
            elements.noteTextarea.value = note || '';
            updateNoteCharCount();
            elements.noteView.hidden = true;
            elements.noteViewActions.hidden = true;
            elements.noteEdit.hidden = false;
            elements.noteTextarea.focus();
        }
    }

    function closeNoteEditor() {
        state.noteEditorOpen = false;
        state.noteEditorVerseId = null;
        elements.noteEditorOverlay.hidden = true;
    }

    function updateNoteCharCount() {
        elements.noteCharCurrent.textContent = elements.noteTextarea.value.length;
    }

    /** Cancel from edit mode: back to view if a note exists, otherwise close. */
    function cancelNoteEdit() {
        const savedVerse = state.savedVerses[state.noteEditorVerseId];
        if (savedVerse && savedVerse.note) {
            setNoteMode('view');
        } else {
            closeNoteEditor();
        }
    }

    async function saveNote() {
        const verseId = state.noteEditorVerseId;
        const VERSE_NOTE_LIMIT = 500;
        let note = elements.noteTextarea.value.trim();
        try {
            const ctx = await getRenderCtxForVerse(verseId);
            note = await normalizeNoteLinksOnSave(note, ctx);
            elements.noteTextarea.value = note;
            updateNoteCharCount();
            if (note.length > VERSE_NOTE_LIMIT) {
                showToast(`Note is too long after converting scripture links (${VERSE_NOTE_LIMIT} char limit)`);
                return;
            }
        } catch (err) {
            console.error('Failed to normalize note links:', err);
        }
        setVerseNote(verseId, note);
        if (note) {
            setNoteMode('view');
        } else {
            closeNoteEditor();
        }
    }

    // ============================================
    // Note Editor (shared by chapter + book notes)
    // ============================================

    const NOTE_LIMITS = { chapter: 5000, book: 10000 };

    /** The saved note (if any) for an editor target. */
    function getNoteForTarget(ref) {
        return ref.type === 'book'
            ? state.bookNotes[ref.bookId]
            : state.chapterNotes[chapterKey(ref.bookId, ref.chapter)];
    }

    /** Renderer ctx for an editor target's verse links. */
    function getRenderCtxForTarget(ref) {
        if (ref.type === 'book') {
            return { type: 'book', bookId: ref.bookId, bookName: ref.bookName };
        }
        return getNoteForTarget(ref); // chapter note object carries firstVerseId/verseCount
    }

    /** Ensure chapter notes have firstVerseId/verseCount for [N] → [v=…] normalize. */
    async function resolveNormalizeCtx(ref) {
        if (!ref) return null;
        if (ref.type === 'book') {
            return {
                type: 'book',
                bookId: ref.bookId,
                bookName: ref.bookName || ref.label
            };
        }
        const existing = getNoteForTarget(ref);
        if (existing && existing.firstVerseId != null && existing.verseCount != null) {
            return existing;
        }
        try {
            const chapters = await fetchChapters(ref.bookId);
            const ch = chapters.find(c => c.chapter === ref.chapter);
            if (ch) {
                return {
                    firstVerseId: ch.firstVerseId,
                    verseCount: ch.verseCount,
                    bookId: ref.bookId,
                    chapter: ref.chapter
                };
            }
        } catch (_) { /* fall through */ }
        return existing || ref;
    }

    function openChapterNoteEditor(target) {
        const ref = target || getCurrentChapterRef();
        if (!ref) return;
        if (!ref.type) ref.type = 'chapter';
        stopAudioOnUIEvent();
        state.chapterNoteEditorTarget = ref;
        state.chapterNoteEditorOpen = true;
        elements.chapterNoteOverlay.hidden = false;
        elements.chapterNoteRef.textContent = ref.label;

        // Scope-dependent chrome — set on every open (modal is shared)
        const limit = NOTE_LIMITS[ref.type];
        elements.chapterNoteTitle.textContent = ref.type === 'book' ? 'Book Note' : 'Chapter Note';
        elements.chapterNoteTextarea.maxLength = limit;
        elements.chapterNoteCharMax.textContent = limit;
        elements.chapterNoteHintLinks.innerHTML = ref.type === 'book'
            ? '<code>[12]</code> chapter link <code>[3:16]</code> verse link'
            : '<code>[12]</code> verse link';

        if (!state.currentUser) {
            elements.chapterNoteSignin.hidden = false;
            elements.chapterNoteView.hidden = true;
            elements.chapterNoteViewActions.hidden = true;
            elements.chapterNoteEdit.hidden = true;
            return;
        }
        elements.chapterNoteSignin.hidden = true;

        // View-first when a note exists; straight to edit when empty
        setChapterNoteMode(getNoteForTarget(ref) ? 'view' : 'edit');
    }

    function setChapterNoteMode(mode) {
        const ref = state.chapterNoteEditorTarget;
        if (!ref) return;
        const existing = getNoteForTarget(ref);

        if (mode === 'view') {
            elements.chapterNoteView.innerHTML = existing
                ? renderNoteMarkdown(existing.note, getRenderCtxForTarget(ref))
                : '';
            elements.chapterNoteView.hidden = false;
            elements.chapterNoteViewActions.hidden = false;
            elements.chapterNoteEdit.hidden = true;
            hydrateRangeLinkLabels(elements.chapterNoteView);
        } else {
            elements.chapterNoteTextarea.value = existing ? existing.note : '';
            updateChapterNoteCharCount();
            elements.chapterNoteView.hidden = true;
            elements.chapterNoteViewActions.hidden = true;
            elements.chapterNoteEdit.hidden = false;
            elements.chapterNoteTextarea.focus();
        }
    }

    function closeChapterNoteEditor() {
        state.chapterNoteEditorOpen = false;
        state.chapterNoteEditorTarget = null;
        elements.chapterNoteOverlay.hidden = true;
    }

    function updateChapterNoteCharCount() {
        elements.chapterNoteCharCurrent.textContent = elements.chapterNoteTextarea.value.length;
    }

    /** Cancel from edit mode: back to view if a note exists, otherwise close. */
    function cancelChapterNoteEdit() {
        const ref = state.chapterNoteEditorTarget;
        if (ref && getNoteForTarget(ref)) {
            setChapterNoteMode('view');
        } else {
            closeChapterNoteEditor();
        }
    }

    async function saveChapterNote() {
        const ref = state.chapterNoteEditorTarget;
        if (!ref) return;
        let note = elements.chapterNoteTextarea.value.trim();
        const existing = getNoteForTarget(ref);
        const limit = NOTE_LIMITS[ref.type] || NOTE_LIMITS.chapter;
        try {
            note = await normalizeNoteLinksOnSave(note, await resolveNormalizeCtx(ref));
            elements.chapterNoteTextarea.value = note;
            updateChapterNoteCharCount();
            if (note.length > limit) {
                showToast(`Note is too long after converting scripture links (${limit} char limit)`);
                return;
            }
            if (note) {
                if (ref.type === 'book') {
                    await saveBookNoteToApi(ref.bookId, note);
                } else {
                    await saveChapterNoteToApi(ref.bookId, ref.chapter, note);
                }
                setChapterNoteMode('view');
            } else {
                if (existing) {
                    if (ref.type === 'book') {
                        await deleteBookNoteFromApi(ref.bookId);
                    } else {
                        await deleteChapterNoteFromApi(ref.bookId, ref.chapter);
                    }
                }
                closeChapterNoteEditor();
            }
            renderPage(); // refresh header/title indicators
        } catch (err) {
            console.error('Failed to save note:', err);
            showToast('Failed to save note');
        }
    }

    // ============================================
    // TTS Audio Functions
    // ============================================

    async function checkTtsStatus() {
        try {
            const response = await fetch('/api/tts/status');
            if (!response.ok) {
                state.audioEnabled = false;
                return;
            }
            const data = await response.json();
            state.audioEnabled = data.enabled === true;
            updateAudioControlsVisibility();
        } catch (e) {
            console.error('Failed to check TTS status', e);
            state.audioEnabled = false;
        }
    }

    function updateAudioControlsVisibility() {
        if (state.audioEnabled) {
            elements.audioControls.hidden = false;
            // Show audio shortcuts in help modal
            document.querySelectorAll('.audio-shortcut').forEach(el => {
                el.hidden = false;
            });
        } else {
            elements.audioControls.hidden = true;
            document.querySelectorAll('.audio-shortcut').forEach(el => {
                el.hidden = true;
            });
        }
    }

    function loadAudioSpeed() {
        const saved = localStorage.getItem(STORAGE_KEYS.AUDIO_SPEED);
        if (saved) {
            state.audioSpeed = parseFloat(saved) || 1.0;
        }
        updateAudioSpeedDisplay();
    }

    function saveAudioSpeed() {
        localStorage.setItem(STORAGE_KEYS.AUDIO_SPEED, state.audioSpeed.toString());
    }

    function updateAudioSpeedDisplay() {
        elements.audioSpeedBadge.textContent = state.audioSpeed + 'x';
        if (elements.ttsAudio) {
            elements.ttsAudio.playbackRate = state.audioSpeed;
        }
        if (elements.ttsAudioBuffer) {
            elements.ttsAudioBuffer.playbackRate = state.audioSpeed;
        }
    }

    function cycleAudioSpeed() {
        const speeds = [1, 1.25, 1.5, 1.75, 2];
        const currentIndex = speeds.indexOf(state.audioSpeed);
        const nextIndex = (currentIndex + 1) % speeds.length;
        state.audioSpeed = speeds[nextIndex];
        updateAudioSpeedDisplay();
        saveAudioSpeed();
    }

    function toggleAudio() {
        if (state.audioPlaying) {
            stopAudio();
        } else {
            startAudio();
        }
    }

    function startAudio() {
        state.audioPlaying = true;
        elements.audioToggle.classList.add('playing');
        playVerseAudio(state.currentVerseId);
    }

    function stopAudio() {
        state.audioPlaying = false;
        state.audioPendingChapter = null;
        elements.audioToggle.classList.remove('playing');
        if (elements.ttsAudio) {
            elements.ttsAudio.pause();
            elements.ttsAudio.src = '';
        }
        if (elements.ttsAudioBuffer) {
            elements.ttsAudioBuffer.pause();
            elements.ttsAudioBuffer.src = '';
        }
    }

    async function playVerseAudio(verseId, retryCount = 0) {
        if (!state.audioPlaying || !elements.ttsAudio) return;

        try {
            const url = await getAudioUrl(verseId);
            elements.ttsAudio.src = url;
            elements.ttsAudio.playbackRate = state.audioSpeed;
            await elements.ttsAudio.play();

            // While this verse plays, warm caches for upcoming verses and
            // pre-buffer the immediate next verse into the browser's media cache.
            warmUrlCache(verseId);
            preBufferNextVerse(verseId);
        } catch (e) {
            console.error('Failed to play audio', e);
            if (retryCount < 1) {
                console.log('Retrying audio playback...');
                setTimeout(() => playVerseAudio(verseId, retryCount + 1), 500);
            } else {
                stopAudio();
            }
        }
    }

    async function playChapterAudio(book, chapter, retryCount = 0) {
        if (!state.audioPlaying || !elements.ttsAudio) return;

        try {
            const url = await getChapterAudioUrl(book, chapter);
            elements.ttsAudio.src = url;
            elements.ttsAudio.playbackRate = state.audioSpeed;
            await elements.ttsAudio.play();
        } catch (e) {
            console.error('Failed to play chapter audio', e);
            if (retryCount < 1) {
                console.log('Retrying chapter audio playback...');
                setTimeout(() => playChapterAudio(book, chapter, retryCount + 1), 500);
            } else {
                stopAudio();
            }
        }
    }

    async function handleAudioEnded() {
        if (!state.audioPlaying) return;

        // If we just played a chapter announcement, now play the verse
        if (state.audioPendingChapter) {
            state.audioPendingChapter = null;
            playVerseAudio(state.currentVerseId);
            return;
        }

        // Get current verse info before advancing
        const currentVerse = state.pageVerses.find(v => v.id === state.currentVerseId);
        const prevBook = currentVerse ? currentVerse.book : null;
        const prevChapter = currentVerse ? currentVerse.chapter : null;

        // Advance to next verse (autoAdvance=true to skip restart logic)
        await nextVerse(true);

        // Get new verse info
        const newVerse = state.pageVerses.find(v => v.id === state.currentVerseId);

        // Check if we crossed into a new chapter
        if (newVerse && (newVerse.book !== prevBook || newVerse.chapter !== prevChapter)) {
            // Play chapter announcement first
            state.audioPendingChapter = { book: newVerse.book, chapter: newVerse.chapter };
            playChapterAudio(newVerse.book, newVerse.chapter);
        } else {
            // Same chapter, just play the verse
            playVerseAudio(state.currentVerseId);
        }
    }

    function handleAudioError(e) {
        // Ignore errors when audio was intentionally stopped (src set to '')
        if (!state.audioPlaying) return;
        console.error('Audio playback error', e);
        stopAudio();
    }

    function stopAudioOnUIEvent() {
        if (state.audioPlaying) {
            stopAudio();
        }
    }

    /**
     * Restart audio from current verse if audio was/is playing.
     * Call this after navigation completes.
     */
    function restartAudioIfPlaying(wasPlaying) {
        if (wasPlaying) {
            startAudio();
        }
    }

    // ============================================
    // Audio URL Helpers & Prefetching
    // ============================================

    /**
     * Fetch and cache the CDN URL for a verse. Subsequent calls return the
     * cached value immediately without a network round-trip.
     */
    async function getAudioUrl(verseId) {
        const key = `verse:${verseId}`;
        if (audioUrlCache.has(key)) return audioUrlCache.get(key);
        const response = await fetch(`/api/audio/${verseId}`);
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const data = await response.json();
        audioUrlCache.set(key, data.url);
        return data.url;
    }

    /**
     * Fetch and cache the CDN URL for a chapter announcement.
     */
    async function getChapterAudioUrl(book, chapter) {
        const key = `chapter:${book}:${chapter}`;
        if (audioUrlCache.has(key)) return audioUrlCache.get(key);
        const encodedBook = encodeURIComponent(book);
        const response = await fetch(`/api/audio/chapter/${encodedBook}/${chapter}`);
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const data = await response.json();
        audioUrlCache.set(key, data.url);
        return data.url;
    }

    /**
     * Silently warm the URL cache for the next N verses.
     * Fire-and-forget: errors are ignored.
     */
    function warmUrlCache(fromVerseId, count = 5) {
        if (!state.audioEnabled) return;
        for (let i = 1; i <= count; i++) {
            const verseId = fromVerseId + i;
            if (verseId > state.totalVerses) break;
            getAudioUrl(verseId).catch(() => {});
        }
    }

    /**
     * Pre-buffer the immediate next verse into the hidden buffer audio element.
     * Once the browser has downloaded it, playing the same URL on the main
     * element will be served from the browser's media cache with no network wait.
     */
    async function preBufferNextVerse(verseId) {
        if (!state.audioEnabled || !elements.ttsAudioBuffer) return;
        const nextId = verseId + 1;
        if (nextId > state.totalVerses) return;
        try {
            const url = await getAudioUrl(nextId);
            if (elements.ttsAudioBuffer.src !== url) {
                elements.ttsAudioBuffer.src = url;
                elements.ttsAudioBuffer.load();
            }
        } catch (e) {
            // Non-critical — ignore silently
        }
    }

    // ============================================
    // Keyboard Navigation
    // ============================================

    function handleKeyDown(e) {
        // Ignore if typing in input fields
        if (document.activeElement === elements.searchInput) {
            if (e.key === 'Escape') {
                elements.searchInput.blur();
                closeSearch();
            } else if (e.key === 'Enter') {
                handleSearch();
            } else if ((e.key === 'ArrowDown' || e.key === 'j') && state.searchOpen) {
                e.preventDefault();
                const first = elements.searchResultsList.querySelector('.search-result-item');
                if (first) first.focus();
            }
            return;
        }

        if (document.activeElement === elements.newTagInput) {
            if (e.key === 'Escape') {
                elements.newTagInput.blur();
                closeTagPicker();
            } else if (e.key === 'Enter') {
                e.preventDefault();
                handleCreateTag();
            }
            return;
        }

        if (document.activeElement === elements.librarySearch) {
            if (e.key === 'Escape') {
                elements.librarySearch.blur();
                closeLibrary();
            }
            return;
        }

        if (document.activeElement === elements.noteTextarea) {
            if (e.key === 'Escape') {
                elements.noteTextarea.blur();
                closeNoteEditor();
            }
            return;
        }

        if (document.activeElement === elements.chapterNoteTextarea) {
            if (e.key === 'Escape') {
                elements.chapterNoteTextarea.blur();
                closeChapterNoteEditor();
            }
            return;
        }

        if (document.activeElement === elements.cbLabel) {
            if (e.key === 'Escape') {
                elements.cbLabel.blur();
            }
            return;
        }

        // Close overlays with Escape (in order of z-index)
        if (e.key === 'Escape') {
            if (state.passageInsertOpen) {
                if (state.passageInsertMode === 'expand') {
                    showPassageInsertBrowse();
                    renderPassageInsertBrowse();
                    elements.passageInsertSearch.focus();
                } else {
                    closePassageInsertPicker();
                }
            } else if (state.chapterNoteEditorOpen) {
                closeChapterNoteEditor();
            } else if (state.noteEditorOpen) {
                closeNoteEditor();
            } else if (state.tagPickerOpen) {
                closeTagPicker();
            } else if (state.collectionBuilderOpen) {
                closeCollectionBuilder();
            } else if (state.passagePickerOpen) {
                closePassagePicker();
            } else if (state.memorizationOpen) {
                closeMemorization();
            } else if (state.collectionsOpen) {
                closeCollections();
            } else if (state.libraryOpen) {
                closeLibrary();
            } else if (state.mobileMenuOpen) {
                closeMobileMenu();
            } else if (state.searchOpen) {
                closeSearch();
            } else if (state.helpOpen) {
                closeHelp();
            } else if (state.collection) {
                exitCollectionMode();
            }
            return;
        }

        // Don't process other keys if overlays are open
        if (state.searchOpen || state.helpOpen || state.libraryOpen ||
            state.tagPickerOpen || state.noteEditorOpen || state.mobileMenuOpen ||
            state.memorizationOpen || state.passagePickerOpen ||
            state.chapterNoteEditorOpen || state.collectionsOpen ||
            state.collectionBuilderOpen || state.passageInsertOpen) return;

        // Don't intercept browser shortcuts (Cmd/Ctrl + key)
        if (e.metaKey || e.ctrlKey) return;

        switch (e.key) {
            case 'j':
            case 'ArrowDown':
                e.preventDefault();
                nextVerse();
                break;
            case 'k':
            case 'ArrowUp':
                e.preventDefault();
                prevVerse();
                break;
            case 'l':
            case 'ArrowRight':
                e.preventDefault();
                nextPage();
                break;
            case 'h':
            case 'ArrowLeft':
                e.preventDefault();
                prevPage();
                break;
            case '.':
                e.preventDefault();
                if (state.collection) nextPassage();
                else nextChapter();
                break;
            case ',':
                e.preventDefault();
                if (state.collection) prevPassage();
                else prevChapter();
                break;
            case '>':
                e.preventDefault();
                if (state.collection) nextPassage();
                else nextBook();
                break;
            case '<':
                e.preventDefault();
                if (state.collection) prevPassage();
                else prevBook();
                break;
            case '/':
                e.preventDefault();
                if (window.innerWidth <= 600) {
                    document.body.classList.add('mobile-search-open');
                    if (elements.mobileSearchCancel) elements.mobileSearchCancel.hidden = false;
                }
                elements.searchInput.focus();
                break;
            case '?':
                e.preventDefault();
                toggleHelp();
                break;
            case 'b':
                e.preventDefault();
                toggleSaveVerse(state.currentVerseId);
                break;
            case 't':
                e.preventDefault();
                openTagPicker(state.currentVerseId);
                break;
            case 'n':
                e.preventDefault();
                openNoteEditor(state.currentVerseId);
                break;
            case 'y':
                e.preventDefault();
                copyVerseToClipboard(state.currentVerseId);
                break;
            case 'c':
                e.preventDefault();
                if (!state.collection) openChapterNoteEditor();
                break;
            case 'B':
                e.preventDefault();
                if (!state.collection) openChapterNoteEditor(getCurrentBookRef());
                break;
            case 'm':
                e.preventDefault();
                openPassagePicker(state.currentVerseId);
                break;
            case 'M':
                e.preventDefault();
                if (state.currentUser) openMemorization();
                else showToast('Sign in to use memorization');
                break;
            case 'C':
                e.preventDefault();
                openCollections();
                break;
            case 'p':
                e.preventDefault();
                if (state.audioEnabled && !state.collection) toggleAudio();
                break;
            case 's':
                e.preventDefault();
                if (state.audioEnabled && !state.collection) cycleAudioSpeed();
                break;
        }
    }

    // ============================================
    // Event Listeners
    // ============================================

    function setupEventListeners() {
        // Keyboard navigation
        document.addEventListener('keydown', handleKeyDown);
        
        // Search
        // Element-level listener fires before the document-level handleKeyDown;
        // stopPropagation when the autocomplete consumes a key.
        elements.searchInput.addEventListener('keydown', (e) => {
            if (searchAc.open) {
                if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
                    e.preventDefault();
                    e.stopPropagation();
                    moveSearchAcActive(e.key === 'ArrowDown' ? 1 : -1);
                    return;
                }
                if (e.key === 'Enter' && searchAc.activeIndex >= 0) {
                    e.preventDefault();
                    e.stopPropagation();
                    const m = searchAc.matches[searchAc.activeIndex];
                    if (m.type === 'passage') selectPassageSuggestion(m.id);
                    else selectCollectionSuggestion(m.id);
                    return;
                }
                if (e.key === 'Escape') {
                    // First Escape only dismisses the suggestions
                    e.stopPropagation();
                    hideSearchAutocomplete();
                    return;
                }
            }
            if (e.key === 'Enter') {
                e.preventDefault();
                hideSearchAutocomplete();
                handleSearch();
            }
        });
        elements.searchInput.addEventListener('input', updateSearchAutocomplete);
        elements.searchInput.addEventListener('blur', () => {
            // Delay so a mousedown on a suggestion lands first
            setTimeout(hideSearchAutocomplete, 150);
        });
        elements.searchAutocomplete.addEventListener('mousedown', (e) => {
            const item = e.target.closest('.search-ac-item');
            if (item) {
                e.preventDefault();
                if (item.dataset.passageId) {
                    selectPassageSuggestion(item.dataset.passageId);
                } else {
                    selectCollectionSuggestion(parseInt(item.dataset.collectionId, 10));
                }
            }
        });
        elements.searchInput.addEventListener('focus', () => {
            state.audioWasPlayingBeforeModal = state.audioPlaying;
            stopAudioOnUIEvent();
            elements.searchInput.select();
        });
        elements.searchClose.addEventListener('click', closeSearch);
        elements.searchOverlay.addEventListener('click', (e) => {
            if (e.target === elements.searchOverlay) {
                closeSearch();
            }
        });
        if (elements.searchResultTabs) {
            elements.searchResultTabs.addEventListener('click', (e) => {
                const tab = e.target.closest('.search-result-tab');
                if (!tab || tab.hidden) return;
                setSearchResultTab(tab.dataset.tab);
            });
        }

        // Keyboard navigation within search results
        elements.searchResultsList.addEventListener('keydown', (e) => {
            const items = Array.from(elements.searchResultsList.querySelectorAll('.search-result-item'));
            const idx = items.indexOf(document.activeElement);
            if (idx === -1) return;

            if (e.key === 'ArrowDown' || e.key === 'j') {
                e.preventDefault();
                if (idx < items.length - 1) items[idx + 1].focus();
            } else if (e.key === 'ArrowUp' || e.key === 'k') {
                e.preventDefault();
                if (idx > 0) items[idx - 1].focus();
                else elements.searchInput.focus();
            } else if (e.key === 'Enter') {
                e.preventDefault();
                items[idx].click();
            }
        });

        // Help
        elements.helpToggle.addEventListener('click', toggleHelp);
        elements.helpClose.addEventListener('click', closeHelp);
        elements.helpOverlay.addEventListener('click', (e) => {
            if (e.target === elements.helpOverlay) {
                closeHelp();
            }
        });

        // Passage picker modal
        elements.passagePickerClose.addEventListener('click', closePassagePicker);
        elements.passagePickerCancel.addEventListener('click', closePassagePicker);
        elements.passagePickerOverlay.addEventListener('click', (e) => {
            if (e.target === elements.passagePickerOverlay) closePassagePicker();
        });
        // Add / Update — existingEntry captured on open; stored on overlay as dataset
        elements.passagePickerAdd.addEventListener('click', () => {
            const existingId = elements.passagePickerOverlay.dataset.editEntryId || null;
            const existingEntry = existingId
                ? state.memorizedEntries.find(e => e.id === existingId) || null
                : null;
            submitPassagePicker(existingEntry);
        });
        elements.passagePickerRemove.addEventListener('click', () => {
            const entryId = elements.passagePickerOverlay.dataset.editEntryId;
            if (entryId) removePassageFromPicker(entryId);
        });

        // Memorization queue
        elements.memorizationToggle.addEventListener('click', () => {
            if (!state.currentUser) {
                showToast('Sign in to use memorization');
                return;
            }
            openMemorization();
        });
        elements.memorizationClose.addEventListener('click', closeMemorization);
        elements.memorizationOverlay.addEventListener('click', (e) => {
            if (e.target === elements.memorizationOverlay) closeMemorization();
        });
        elements.memorizationTrainBtn.addEventListener('click', () => {
            const due = state.memorizationDueEntries;
            if (!due || due.length === 0) return;
            sessionStorage.setItem('kjv_training_session', JSON.stringify({ entries: due, index: 0 }));
            window.location.href = '/train?from=reading';
        });

        // Passage collections hub
        elements.collectionsToggle.addEventListener('click', openCollections);
        elements.collectionsClose.addEventListener('click', closeCollections);
        elements.collectionsOverlay.addEventListener('click', (e) => {
            if (e.target === elements.collectionsOverlay) closeCollections();
        });
        elements.collectionsNew.addEventListener('click', () => openCollectionBuilder());

        // Collection builder
        elements.cbClose.addEventListener('click', closeCollectionBuilder);
        elements.cbCancel.addEventListener('click', closeCollectionBuilder);
        elements.cbOverlay.addEventListener('click', (e) => {
            if (e.target === elements.cbOverlay) closeCollectionBuilder();
        });
        elements.cbBookSelect.addEventListener('change', () => {
            builder.bookId = parseInt(elements.cbBookSelect.value, 10);
            builder.chapter = 1;
            renderBuilderChapter();
        });
        elements.cbChapterSelect.addEventListener('change', () => {
            builder.chapter = parseInt(elements.cbChapterSelect.value, 10);
            renderBuilderChapter();
        });
        elements.cbPrevCh.addEventListener('click', async () => {
            if (builder.chapter > 1) {
                builder.chapter--;
            } else if (builder.bookId > 1) {
                builder.bookId--;
                elements.cbBookSelect.value = builder.bookId;
                const chapters = await getBuilderChapters(builder.bookId);
                builder.chapter = chapters.length;
            } else return;
            renderBuilderChapter();
        });
        elements.cbNextCh.addEventListener('click', async () => {
            const chapters = await getBuilderChapters(builder.bookId);
            if (builder.chapter < chapters.length) {
                builder.chapter++;
            } else if (builder.bookId < state.books[state.books.length - 1].id) {
                builder.bookId++;
                elements.cbBookSelect.value = builder.bookId;
                builder.chapter = 1;
            } else return;
            renderBuilderChapter();
        });
        elements.cbAddChecked.addEventListener('click', addCheckedToQueue);
        elements.cbLabel.addEventListener('input', updateBuilderSaveState);
        elements.cbSave.addEventListener('click', saveCollectionFromBuilder);
        elements.cbQueueList.addEventListener('click', (e) => {
            const up = e.target.closest('.cb-seg-up');
            const down = e.target.closest('.cb-seg-down');
            const remove = e.target.closest('.cb-seg-remove');
            if (up) spliceQueueSegment(parseInt(up.dataset.seg, 10), 'up');
            else if (down) spliceQueueSegment(parseInt(down.dataset.seg, 10), 'down');
            else if (remove) spliceQueueSegment(parseInt(remove.dataset.seg, 10), 'remove');
        });

        // Library (saved verses) — on mobile the hamburger opens the quick-actions menu instead
        elements.libraryToggle.addEventListener('click', () => {
            if (window.innerWidth <= 600) {
                openMobileMenu();
            } else {
                openLibrary();
            }
        });
        elements.libraryClose.addEventListener('click', closeLibrary);
        elements.libraryOverlay.addEventListener('click', (e) => {
            if (e.target === elements.libraryOverlay) {
                closeLibrary();
            }
        });
        elements.librarySearch.addEventListener('input', handleLibrarySearchInput);
        elements.librarySort.addEventListener('change', handleLibrarySortChange);
        elements.libraryFiltersToggle.addEventListener('click', toggleLibraryFilters);
        elements.libraryTabVerses.addEventListener('click', () => setLibraryView('verses'));
        elements.libraryTabChapterNotes.addEventListener('click', () => setLibraryView('chapter-notes'));

        // Mobile quick-actions menu
        if (elements.mobileMenuOverlay) {
            // Backdrop tap closes the sheet
            elements.mobileMenuOverlay.addEventListener('click', (e) => {
                if (e.target === elements.mobileMenuOverlay) closeMobileMenu();
            });
            document.getElementById('mobile-menu-search').addEventListener('click', () => {
                closeMobileMenu();
                document.body.classList.add('mobile-search-open');
                if (elements.mobileSearchCancel) elements.mobileSearchCancel.hidden = false;
                elements.searchInput.focus();
                elements.searchInput.select();
            });
            document.getElementById('mobile-menu-library').addEventListener('click', () => {
                closeMobileMenu();
                openLibrary();
            });
            document.getElementById('mobile-menu-memorization').addEventListener('click', () => {
                closeMobileMenu();
                if (!state.currentUser) {
                    showToast('Sign in to use memorization');
                    return;
                }
                openMemorization();
            });
            document.getElementById('mobile-menu-collections').addEventListener('click', () => {
                closeMobileMenu();
                openCollections();
            });
            document.getElementById('mobile-menu-bookmark').addEventListener('click', () => {
                closeMobileMenu();
                toggleSaveVerse(state.currentVerseId);
            });
            document.getElementById('mobile-menu-tags').addEventListener('click', () => {
                closeMobileMenu();
                openTagPicker(state.currentVerseId);
            });
            document.getElementById('mobile-menu-note').addEventListener('click', () => {
                closeMobileMenu();
                openNoteEditor(state.currentVerseId);
            });
            document.getElementById('mobile-menu-chapter-note').addEventListener('click', () => {
                closeMobileMenu();
                openChapterNoteEditor();
            });
            document.getElementById('mobile-menu-book-note').addEventListener('click', () => {
                closeMobileMenu();
                openChapterNoteEditor(getCurrentBookRef());
            });
            document.getElementById('mobile-font-decrease').addEventListener('click', decreaseFontSize);
            document.getElementById('mobile-font-increase').addEventListener('click', increaseFontSize);
        }

        // Mobile search cancel button
        if (elements.mobileSearchCancel) {
            elements.mobileSearchCancel.addEventListener('click', () => {
                elements.searchInput.value = '';
                closeSearch();
                elements.searchInput.blur();
            });
        }

        // Tag picker
        elements.tagPickerClose.addEventListener('click', closeTagPicker);
        elements.tagPickerOverlay.addEventListener('click', (e) => {
            if (e.target === elements.tagPickerOverlay) {
                closeTagPicker();
            }
        });
        elements.createTagBtn.addEventListener('click', handleCreateTag);

        // Note editor
        elements.noteEditorClose.addEventListener('click', closeNoteEditor);
        elements.noteDoneBtn.addEventListener('click', closeNoteEditor);
        elements.noteEditBtn.addEventListener('click', () => setNoteMode('edit'));
        elements.noteCancelBtn.addEventListener('click', cancelNoteEdit);
        elements.noteSaveBtn.addEventListener('click', saveNote);
        elements.noteEditorOverlay.addEventListener('click', (e) => {
            if (e.target === elements.noteEditorOverlay) {
                closeNoteEditor();
            }
        });
        elements.noteTextarea.addEventListener('input', updateNoteCharCount);
        if (elements.noteInsertPassageBtn) {
            elements.noteInsertPassageBtn.addEventListener('click', () =>
                openPassageInsertPicker(elements.noteTextarea));
        }
        elements.noteView.addEventListener('click', (e) => {
            const rangeLink = e.target.closest('.note-range-link');
            if (rangeLink) {
                e.preventDefault();
                handleNoteRangeLinkClick(rangeLink);
                return;
            }
            const passageLink = e.target.closest('.note-passage-link');
            if (passageLink) {
                e.preventDefault();
                handleNotePassageLinkClick(passageLink);
                return;
            }
            const collectionLink = e.target.closest('.note-collection-link');
            if (collectionLink) {
                e.preventDefault();
                stageNoteReturnFromOpenEditors();
                closeNoteEditor();
                closeLibrary();
                enterCollectionMode(parseInt(collectionLink.dataset.collectionId, 10));
                return;
            }
            const link = e.target.closest('.note-verse-link');
            if (link) {
                e.preventDefault();
                handleNoteVerseLinkClick(link);
            }
        });

        // Chapter note editor
        elements.chapterNoteClose.addEventListener('click', closeChapterNoteEditor);
        elements.chapterNoteDoneBtn.addEventListener('click', closeChapterNoteEditor);
        elements.chapterNoteEditBtn.addEventListener('click', () => setChapterNoteMode('edit'));
        elements.chapterNoteCancelBtn.addEventListener('click', cancelChapterNoteEdit);
        elements.chapterNoteSaveBtn.addEventListener('click', saveChapterNote);
        elements.chapterNoteOverlay.addEventListener('click', (e) => {
            if (e.target === elements.chapterNoteOverlay) {
                closeChapterNoteEditor();
            }
        });
        elements.chapterNoteTextarea.addEventListener('input', updateChapterNoteCharCount);
        if (elements.chapterNoteInsertPassageBtn) {
            elements.chapterNoteInsertPassageBtn.addEventListener('click', () =>
                openPassageInsertPicker(elements.chapterNoteTextarea));
        }
        elements.chapterNoteView.addEventListener('click', (e) => {
            const rangeLink = e.target.closest('.note-range-link');
            if (rangeLink) {
                e.preventDefault();
                handleNoteRangeLinkClick(rangeLink);
                return;
            }
            const passageLink = e.target.closest('.note-passage-link');
            if (passageLink) {
                e.preventDefault();
                handleNotePassageLinkClick(passageLink);
                return;
            }
            const collectionLink = e.target.closest('.note-collection-link');
            if (collectionLink) {
                e.preventDefault();
                stageNoteReturnFromOpenEditors();
                closeChapterNoteEditor();
                closeLibrary();
                enterCollectionMode(parseInt(collectionLink.dataset.collectionId, 10));
                return;
            }
            const link = e.target.closest('.note-verse-link');
            if (link) {
                e.preventDefault();
                handleNoteVerseLinkClick(link);
            }
        });

        // Scripture insert picker
        if (elements.passageInsertClose) {
            elements.passageInsertClose.addEventListener('click', closePassageInsertPicker);
            elements.passageInsertOverlay.addEventListener('click', (e) => {
                if (e.target === elements.passageInsertOverlay) closePassageInsertPicker();
            });
            elements.passageInsertSearch.addEventListener('input', onPassageInsertSearchInput);
            if (elements.passageInsertTabs) {
                elements.passageInsertTabs.addEventListener('click', (e) => {
                    const tab = e.target.closest('.passage-insert-tab');
                    if (!tab) return;
                    setPassageInsertTab(tab.dataset.tab);
                });
            }
            if (elements.passageInsertExpandBack) {
                elements.passageInsertExpandBack.addEventListener('click', () => {
                    showPassageInsertBrowse();
                    renderPassageInsertBrowse();
                    elements.passageInsertSearch.focus();
                });
            }
            if (elements.passageInsertConfirm) {
                elements.passageInsertConfirm.addEventListener('click', confirmPassageInsertExpand);
            }
        }

        // Book-note pencil / scoped Back in the page title
        elements.chapterTitle.addEventListener('click', (e) => {
            if (e.target.closest('.scoped-back-btn') || e.target.closest('.collection-exit-btn')) {
                exitCollectionMode();
                return;
            }
            if (e.target.closest('.book-note-btn')) {
                openChapterNoteEditor(getCurrentBookRef());
            }
        });

        // Browser back/forward restores collection, passage, or range mode from the URL
        window.addEventListener('popstate', async () => {
            const collMatch = window.location.pathname.match(/^\/read\/collection\/(\d+)$/);
            const passMatch = window.location.pathname.match(/^\/read\/passage\/([0-9a-f-]{36})$/i);
            const rangeParams = new URLSearchParams(window.location.search);
            const isRangePath = window.location.pathname === '/read/range' && rangeParams.get('v');
            if (collMatch) {
                const id = parseInt(collMatch[1], 10);
                if (!state.collection || state.collection.kind !== 'collection' || state.collection.id !== id) {
                    await enterCollectionMode(id, { push: false });
                }
            } else if (passMatch) {
                const id = passMatch[1];
                if (!state.collection || state.collection.kind !== 'passage' || state.collection.id !== id) {
                    await enterPassageMode(id, { push: false });
                }
            } else if (isRangePath) {
                const v = rangeParams.get('v');
                if (!state.collection || state.collection.kind !== 'range' || state.collection.rangeV !== v) {
                    await enterRangeMode(v, { push: false });
                }
            } else if (state.collection) {
                await exitCollectionMode({ push: false });
            }
        });

        // Font size
        elements.fontIncrease.addEventListener('click', increaseFontSize);
        elements.fontDecrease.addEventListener('click', decreaseFontSize);

        // Audio controls
        elements.audioToggle.addEventListener('click', toggleAudio);
        elements.audioSpeedBadge.addEventListener('click', cycleAudioSpeed);
        elements.ttsAudio.addEventListener('ended', handleAudioEnded);
        elements.ttsAudio.addEventListener('error', handleAudioError);

        // Click on verse to select it
        elements.readingArea.addEventListener('click', (e) => {
            const noteBtn = e.target.closest('.chapter-note-btn');
            if (noteBtn) {
                openChapterNoteEditor({
                    bookId: parseInt(noteBtn.dataset.bookId),
                    chapter: parseInt(noteBtn.dataset.chapter),
                    label: noteBtn.dataset.label
                });
                return;
            }
            const copyBtn = e.target.closest('.verse-copy-btn');
            if (copyBtn) {
                e.stopPropagation();
                copyVerseToClipboard(parseInt(copyBtn.dataset.verseId));
                return;
            }
            const verseEl = e.target.closest('.verse');
            if (verseEl) {
                const verseId = parseInt(verseEl.dataset.verseId);
                if (state.collection) {
                    // Prefer data-ci — the same verse id can appear more than once
                    const ci = verseEl.dataset.ci != null
                        ? parseInt(verseEl.dataset.ci, 10)
                        : NaN;
                    const v = Number.isInteger(ci)
                        ? state.pageVerses.find(x => x._ci === ci)
                        : state.pageVerses.find(x => x.id === verseId);
                    if (v && v._ci !== state.collection.currentIndex) {
                        state.collection.currentIndex = v._ci;
                        state.currentVerseId = v.id;
                        renderPage();
                    }
                    return;
                }
                if (verseId && verseId !== state.currentVerseId) {
                    const wasPlaying = state.audioPlaying;
                    if (wasPlaying) stopAudio();
                    state.currentVerseId = verseId;
                    renderPage();
                    saveState();
                    if (wasPlaying) restartAudioIfPlaying(wasPlaying);
                }
            }
        });
        
        window.addEventListener('resize', scheduleRelayout);
        if (typeof ResizeObserver !== 'undefined') {
            new ResizeObserver(scheduleRelayout).observe(elements.readingArea);
        }

        // ── Mobile: swipe left/right on reading area to turn pages ──
        let touchStartX = 0;
        let touchStartY = 0;
        let swipeConsumed = false;

        elements.readingArea.addEventListener('touchstart', (e) => {
            touchStartX = e.touches[0].clientX;
            touchStartY = e.touches[0].clientY;
            swipeConsumed = false;
        }, { passive: true });

        elements.readingArea.addEventListener('touchend', (e) => {
            if (swipeConsumed) return;
            const deltaX = e.changedTouches[0].clientX - touchStartX;
            const deltaY = e.changedTouches[0].clientY - touchStartY;
            const absX = Math.abs(deltaX);
            const absY = Math.abs(deltaY);
            // Trigger page turn only for a clear horizontal swipe (≥50 px, 1.5× more horizontal than vertical)
            if (absX >= 50 && absX > absY * 1.5) {
                swipeConsumed = true;
                if (deltaX < 0) {
                    nextPage();   // swipe left  → next page
                } else {
                    prevPage();   // swipe right → previous page
                }
            }
        }, { passive: true });

        // ── Mobile: prev/next page buttons in the indicators bar ──
        if (elements.mobilePrev) {
            elements.mobilePrev.addEventListener('click', prevPage);
        }
        if (elements.mobileNext) {
            elements.mobileNext.addEventListener('click', nextPage);
        }
    }

    // ============================================
    // Loading State
    // ============================================

    function showLoading() {
        elements.loadingOverlay.hidden = false;
    }

    function hideLoading() {
        elements.loadingOverlay.hidden = true;
    }

    // ============================================
    // Auth
    // ============================================

    async function checkAuthState() {
        try {
            const res = await fetch('/api/auth/me', { credentials: 'include' });
            if (res.ok) {
                state.currentUser = await res.json();
                // Snapshot localStorage data before loadLibraryFromApi() overwrites state
                const localVerses = { ...state.savedVerses };
                const localTags = { ...state.tags };
                await loadLibraryFromApi();
                await loadMemorizationFromApi();
                await loadChapterNotesFromApi();
                await loadBookNotesFromApi();
                await loadCollectionsFromApi();
                await loadPassagesFromApi();
                if (!state.currentUser.localStorageMigrated) {
                    // One-time migration: sync whatever localStorage data existed at login time
                    await migrateLocalStorageToDb(localVerses, localTags);
                    // Mark migration complete on the server so it never runs again for this account
                    await fetch('/api/auth/me/migration-complete', {
                        method: 'POST', credentials: 'include'
                    }).catch(() => { /* non-fatal */ });
                    state.currentUser.localStorageMigrated = true;
                }
                updateAuthHeader();
                // Saved/memorized verses change layout (.saved pads the text
                // 12px narrower), so the page measured before this data
                // arrived may no longer fit — re-measure, don't just re-render
                await remeasureCurrentPage();
            } else {
                state.currentUser = null;
                updateAuthHeader();
            }
        } catch (_) {
            state.currentUser = null;
            updateAuthHeader();
        }
    }

    function updateAuthHeader() {
        const user = state.currentUser;
        if (user) {
            elements.authHeader.hidden = false;
            elements.authSigninLink.hidden = true;
            elements.authDisplayName.textContent = user.displayName || user.email;
            elements.authLogoutBtn.onclick = async () => {
                try {
                    await fetch('/api/auth/logout', { method: 'POST', credentials: 'include' });
                } catch (_) { /* ignore */ }
                state.currentUser = null;
                updateAuthHeader();
                // Revert to localStorage data for anonymous state
                loadSavedVerses();
                loadTags();
                state.memorizedPassages = {};
                state.memorizedEntries = [];
                state.chapterNotes = {};
                state.bookNotes = {};
                state.collections = [];
                state.passages = [];
                // Account-owned collection/passage sessions can't stay after logout.
                // Public /read/range?v=… sessions remain — /api/ranges is anonymous.
                if (state.collection && state.collection.kind !== 'range') {
                    exitCollectionMode();
                } else {
                    await remeasureCurrentPage();
                }
            };
        } else {
            elements.authHeader.hidden = true;
            elements.authSigninLink.hidden = false;
        }
    }

    // ============================================
    // Initialization
    // ============================================

    /**
     * Resolve once the reading area has non-zero dimensions, or after a
     * bounded wait. Uses setTimeout polling (not requestAnimationFrame,
     * which can be throttled to a halt in hidden/background tabs).
     */
    function waitForReadingAreaLayout(timeoutMs = 2000) {
        return new Promise((resolve) => {
            const deadline = performance.now() + timeoutMs;
            (function check() {
                const area = elements.readingArea;
                if ((area.clientWidth > 0 && area.clientHeight > 0) || performance.now() >= deadline) {
                    resolve();
                } else {
                    setTimeout(check, 50);
                }
            })();
        });
    }

    async function init() {
        showLoading();

        try {
            // Load saved state
            loadState();
            loadFontSize();
            loadSavedVerses();
            loadTags();
            loadAudioSpeed();

            // Check auth state (fire-and-forget — doesn't block page load)
            checkAuthState();

            // Check TTS status
            await checkTtsStatus();

            // Initialize dropdowns
            await initDropdowns();

            // Setup event listeners
            setupEventListeners();

            // Wait for web fonts before measuring the viewport — font metrics affect
            // line wrapping and therefore how many verses fit on a page.
            await document.fonts.ready;

            // Some environments (embedded/preview browsers, background tabs)
            // run scripts before the page has real dimensions. Measuring a
            // 0×0 reading area would fit zero verses, so wait for layout.
            // Bounded: if the size never settles, proceed — the reading
            // area's ResizeObserver reloads the page once it gets a size.
            await waitForReadingAreaLayout();

            // Load initial page — collection, passage, or portable range deep links
            const collectionMatch = window.location.pathname.match(/^\/read\/collection\/(\d+)$/);
            const passageMatch = window.location.pathname.match(/^\/read\/passage\/([0-9a-f-]{36})$/i);
            const rangeV = window.location.pathname === '/read/range'
                ? new URLSearchParams(window.location.search).get('v')
                : null;
            let enteredScoped = false;
            if (collectionMatch) {
                enteredScoped = await enterCollectionMode(
                    parseInt(collectionMatch[1], 10), { push: false });
            } else if (passageMatch) {
                enteredScoped = await enterPassageMode(passageMatch[1], { push: false });
            } else if (rangeV) {
                enteredScoped = await enterRangeMode(rangeV, { push: false });
            }
            if (!enteredScoped) {
                await goToVerse(state.currentVerseId);
            }
            state.initialPageLoaded = true;

            // A resize that landed while the first page was still loading
            // was deliberately ignored by scheduleRelayout (see the
            // initialPageLoaded guard); re-run the check now so a viewport
            // that gained its real size mid-init still gets a full page.
            scheduleRelayout();

            // Warm the audio URL cache for upcoming verses if TTS enabled
            if (state.audioEnabled) {
                warmUrlCache(state.currentVerseId);
            }

        } catch (error) {
            console.error('Initialization failed:', error);
            elements.readingArea.innerHTML = '<p class="error">Failed to load Bible data. Please refresh the page.</p>';
        } finally {
            hideLoading();
        }
    }

    // Start the app
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', init);
    } else {
        init();
    }

})();
