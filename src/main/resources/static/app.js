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
        tagPickerOpen: false,      // tag picker modal state
        noteEditorOpen: false,     // note editor modal state
        tagPickerVerseId: null,    // which verse the tag picker is for
        noteEditorVerseId: null,   // which verse the note editor is for
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
        // Training
        trainingOpen: false,
        trainingEntryId: null,
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
        searchOverlay: document.getElementById('search-overlay'),
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
        noteTextarea: document.getElementById('note-textarea'),
        noteCharCurrent: document.getElementById('note-char-current'),
        noteSaveBtn: document.getElementById('note-save-btn'),
        noteCancelBtn: document.getElementById('note-cancel-btn'),
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
        memorizationResultsCount: document.getElementById('memorization-results-count'),
        memorizationList: document.getElementById('memorization-list'),
        // Training modal
        trainingOverlay: document.getElementById('training-overlay'),
        trainingClose: document.getElementById('training-close'),
        trainingVerseRef: document.getElementById('training-verse-ref'),
        trainingVerse: document.getElementById('training-verse'),
        trainingCheckBtn: document.getElementById('training-check-btn'),
        trainingRatings: document.getElementById('training-ratings')
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

    async function searchBible(query) {
        const response = await fetch(`/api/search?q=${encodeURIComponent(query)}&limit=50`);
        if (!response.ok) throw new Error('Search failed');
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
     * Calculate how many verses fit in the current viewport starting from a given verse.
     * Uses a binary search approach for efficiency.
     * Accounts for chapter headers that appear before the first verse of each chapter.
     */
    async function calculatePageVerses(startVerseId) {
        const container = elements.readingArea;
        const availableHeight = container.clientHeight;
        
        // Fetch a batch of verses to test
        const batchSize = 100; // Fetch more than we need
        const data = await fetchVerses(startVerseId, batchSize);
        const verses = data.verses;
        
        if (verses.length === 0) {
            return { verses: [], fits: 0 };
        }

        // Create a hidden measuring container that mirrors the reading area
        const measureContainer = document.createElement('div');
        measureContainer.style.cssText = `
            position: absolute;
            visibility: hidden;
            width: ${container.clientWidth}px;
            height: auto;
            column-count: ${getComputedStyle(container).columnCount};
            column-gap: ${getComputedStyle(container).columnGap};
            text-align: justify;
            hyphens: auto;
            -webkit-hyphens: auto;
            font-family: ${getComputedStyle(container).fontFamily};
            font-size: ${getComputedStyle(container).fontSize};
            line-height: ${getComputedStyle(container).lineHeight};
        `;
        document.body.appendChild(measureContainer);

        // Binary search for optimal verse count
        let low = 1;
        let high = verses.length;
        let fittingVerses = [];
        
        try {
            while (low <= high) {
                const mid = Math.floor((low + high) / 2);
                const testVerses = verses.slice(0, mid);
                
                // Render to hidden container for measurement (including chapter headers)
                measureContainer.innerHTML = renderVersesWithHeaders(testVerses);
                
                // For multi-column layouts, we need to check if content overflows
                // The scrollHeight gives us the actual content height when columns are used
                const contentHeight = measureContainer.scrollHeight;
                
                if (contentHeight <= availableHeight) {
                    fittingVerses = testVerses;
                    low = mid + 1;
                } else {
                    high = mid - 1;
                }
            }
        } finally {
            // Clean up measuring container
            document.body.removeChild(measureContainer);
        }
        
        // Ensure at least one verse
        if (fittingVerses.length === 0 && verses.length > 0) {
            fittingVerses = [verses[0]];
        }
        
        return { verses: fittingVerses, total: data.total };
    }

    /**
     * Calculate how many verses fit in the current viewport ENDING at a given verse.
     * Used for backward navigation - finds the maximum verses that fit while ensuring
     * the specified verse is the LAST verse on the page.
     * Accounts for chapter headers that appear before the first verse of each chapter.
     */
    async function calculatePageVersesEndingAt(endVerseId) {
        const container = elements.readingArea;
        const availableHeight = container.clientHeight;
        
        // Fetch verses ending at the target (fetch backwards)
        // We need verses from (endVerseId - N) to endVerseId
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
        
        // Create a hidden measuring container
        const measureContainer = document.createElement('div');
        measureContainer.style.cssText = `
            position: absolute;
            visibility: hidden;
            width: ${container.clientWidth}px;
            height: auto;
            column-count: ${getComputedStyle(container).columnCount};
            column-gap: ${getComputedStyle(container).columnGap};
            text-align: justify;
            hyphens: auto;
            -webkit-hyphens: auto;
            font-family: ${getComputedStyle(container).fontFamily};
            font-size: ${getComputedStyle(container).fontSize};
            line-height: ${getComputedStyle(container).lineHeight};
        `;
        document.body.appendChild(measureContainer);

        // Binary search for maximum verses that fit, working from the END
        // We want to find how many verses from the end of versesEndingAtTarget fit
        let low = 1;
        let high = versesEndingAtTarget.length;
        let fittingVerses = [];
        
        try {
            while (low <= high) {
                const mid = Math.floor((low + high) / 2);
                // Take the last 'mid' verses (ending at target)
                const testVerses = versesEndingAtTarget.slice(-mid);
                
                // Render with chapter headers (including chapter header for first verse if needed)
                measureContainer.innerHTML = renderVersesWithHeaders(testVerses);
                const contentHeight = measureContainer.scrollHeight;
                
                if (contentHeight <= availableHeight) {
                    fittingVerses = testVerses;
                    low = mid + 1;
                } else {
                    high = mid - 1;
                }
            }
        } finally {
            document.body.removeChild(measureContainer);
        }
        
        // Ensure at least one verse (the target)
        if (fittingVerses.length === 0 && versesEndingAtTarget.length > 0) {
            fittingVerses = [versesEndingAtTarget[versesEndingAtTarget.length - 1]];
        }
        
        return { verses: fittingVerses, total: data.total };
    }

    /**
     * Create HTML for a chapter header.
     * Format: "Chapter N" for most books, or "Psalm N" for Psalms
     */
    function createChapterHeaderHTML(verse) {
        // Check for both "Psalm" and "Psalms" to handle different data formats
        const isPsalm = verse.book === 'Psalms' || verse.book === 'Psalm';
        const headerText = isPsalm ? `Psalm ${verse.chapter}` : `Chapter ${verse.chapter}`;
        return `<div class="chapter-header">${headerText}</div>`;
    }

    /**
     * Create HTML for a single verse.
     */
    function isVerseMemorized(verseId) {
        return !!state.memorizedPassages[buildNaturalKey(verseId, verseId)];
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

        return `
            <p class="verse${currentClass}${savedClass}${memorizedClass}" data-verse-id="${verse.id}">
                ${tagDotsHtml}
                <span class="verse-number">${verse.verse}</span>
                <span class="verse-text">${escapeHtml(verse.text)}</span>
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

    /**
     * Render the current page of verses with inline chapter headers.
     */
    function renderPage() {
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
            if (firstVerse.chapter === lastVerse.chapter && firstVerse.book === lastVerse.book) {
                elements.chapterTitle.textContent = `${firstVerse.book} ${firstVerse.chapter}`;
            } else if (firstVerse.book === lastVerse.book) {
                elements.chapterTitle.textContent = `${firstVerse.book} ${firstVerse.chapter}–${lastVerse.chapter}`;
            } else {
                elements.chapterTitle.textContent = `${firstVerse.book} — ${lastVerse.book}`;
            }
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
    async function loadPage(startVerseId) {
        state.isLoading = true;
        
        try {
            const result = await calculatePageVerses(startVerseId);
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
     * Go to a specific verse (loads page containing it).
     */
    async function goToVerse(verseId) {
        verseId = Math.max(1, Math.min(verseId, state.totalVerses));
        state.currentVerseId = verseId;
        await loadPage(verseId);
    }

    /**
     * Move to next verse (within page or turn page).
     * @param {boolean} autoAdvance - true if called from audio auto-advance (skip restart)
     */
    async function nextVerse(autoAdvance = false) {
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
        state.isLoading = true;
        
        try {
            // Use the dedicated function that calculates verses ending at the target
            const result = await calculatePageVersesEndingAt(targetVerseId);
            
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
     * Turn to next page.
     */
    async function nextPage() {
        if (state.pageVerses.length === 0) return;

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

        // First, check if it's a Bible reference
        try {
            const refResult = await parseReference(query);
            if (refResult.valid && refResult.verseId) {
                // It's a valid reference, jump directly
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

        // Perform full-text search
        try {
            const results = await searchBible(query);
            showSearchResults(results);
        } catch (e) {
            console.error('Search failed', e);
        }
    }

    function showSearchResults(results) {
        elements.searchResultsTitle.textContent =
            `${results.count} result${results.count !== 1 ? 's' : ''} for "${results.query}"`;

        if (results.verses.length === 0) {
            elements.searchResultsList.innerHTML = '<p class="no-results">No verses found.</p>';
        } else {
            elements.searchResultsList.innerHTML = results.verses.map(v => `
                <div class="search-result-item" data-verse-id="${v.id}" tabindex="0">
                    <div class="search-result-ref">${v.book} ${v.chapter}:${v.verse}</div>
                    <div class="search-result-text">${v.highlight || escapeHtml(v.text)}</div>
                </div>
            `).join('');

            // Add click handlers
            elements.searchResultsList.querySelectorAll('.search-result-item').forEach(item => {
                item.addEventListener('click', async () => {
                    const wasPlaying = state.audioWasPlayingBeforeModal;
                    const verseId = parseInt(item.dataset.verseId);
                    closeSearch();
                    await goToVerse(verseId);
                    if (wasPlaying) restartAudioIfPlaying(wasPlaying);
                });
            });
        }

        openSearch();
        const firstResult = elements.searchResultsList.querySelector('.search-result-item');
        if (firstResult) firstResult.focus();
    }

    function openSearch() {
        state.audioWasPlayingBeforeModal = state.audioPlaying;
        stopAudioOnUIEvent();
        state.searchOpen = true;
        elements.searchOverlay.hidden = false;
    }

    function closeSearch() {
        state.searchOpen = false;
        elements.searchOverlay.hidden = true;
        document.body.classList.remove('mobile-search-open');
        if (elements.mobileSearchCancel) elements.mobileSearchCancel.hidden = true;
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
            entries.forEach(entry => {
                state.memorizedPassages[entry.passage.naturalKey] = entry.id;
            });
        } catch (err) {
            console.error('Failed to load memorization queue:', err);
        }
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
                renderPage();
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
                renderPage();
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
            renderPage();
        }
    }

    async function toggleMemorizeVerse(verseId) {
        if (!state.currentUser) {
            showToast('Sign in to memorize verses');
            return;
        }
        const key = buildNaturalKey(verseId, verseId);
        const entryId = state.memorizedPassages[key];
        if (entryId) {
            // Optimistic remove
            delete state.memorizedPassages[key];
            renderPage();
            try {
                await libApi(`/api/memorization/queue/${entryId}`, { method: 'DELETE' });
            } catch (err) {
                console.error('Failed to remove from memorization queue:', err);
            }
        } else {
            // Add via API — need server-assigned entry ID
            try {
                const entry = await libApi('/api/memorization/queue', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ fromVerseId: verseId, toVerseId: verseId })
                });
                state.memorizedPassages[entry.passage.naturalKey] = entry.id;
                renderPage();
            } catch (err) {
                console.error('Failed to add to memorization queue:', err);
            }
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

    // ─── Training ─────────────────────────────────────────────────────────────

    /**
     * Returns an array of segment objects: { text, isBlank, expected }
     * Non-blank segments have isBlank=false; blank segments have isBlank=true and
     * expected = the word (leading/trailing punctuation stripped) for comparison.
     *
     * Blank selection is index-based (deterministic) based on masteryLevel:
     *   0 → every 7th word (~14%)
     *   1 → every 4th  (~25%)
     *   2 → every 2nd  (~50%)
     *   3 → 2 of every 3 (~67%)
     *   4 → 6 of every 7 (~86%)
     *   5 → all words  (100%)
     */
    function computeBlankedSegments(text, masteryLevel) {
        // Split into tokens preserving whitespace
        const tokens = text.split(/(\s+)/);
        const wordTokens = tokens.filter(t => t.trim().length > 0);
        const blankSet = new Set();
        const n = wordTokens.length;

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
                // whitespace — preserve as-is
                if (segments.length > 0 && !segments[segments.length - 1].isBlank) {
                    segments[segments.length - 1].text += token;
                } else {
                    segments.push({ text: token, isBlank: false, expected: null });
                }
            } else {
                const blank = shouldBlank(wordIdx);
                const expected = token.replace(/^[^a-zA-Z0-9]+|[^a-zA-Z0-9]+$/g, '');
                segments.push({ text: token, isBlank: blank, expected: blank ? expected : null });
                wordIdx++;
            }
        }
        return segments;
    }

    function openTraining(entry) {
        closeMemorization();
        state.trainingOpen = true;
        state.trainingEntryId = entry.id;
        elements.trainingVerseRef.textContent = entry.fromVerseRef;

        // Render blanked verse
        const segments = computeBlankedSegments(entry.fromVerseText, entry.masteryLevel);
        elements.trainingVerse.innerHTML = segments.map(seg => {
            if (seg.isBlank) {
                const sz = Math.max(3, seg.expected.length + 1);
                return `<input class="blank-input" size="${sz}" data-expected="${escapeHtml(seg.expected)}" autocomplete="off" spellcheck="false">`;
            }
            return `<span>${escapeHtml(seg.text)}</span>`;
        }).join('');

        elements.trainingCheckBtn.hidden = false;
        elements.trainingCheckBtn.disabled = false;
        elements.trainingRatings.hidden = true;
        elements.trainingOverlay.hidden = false;

        // Focus first blank
        const first = elements.trainingVerse.querySelector('.blank-input');
        if (first) first.focus();
    }

    function closeTraining() {
        state.trainingOpen = false;
        state.trainingEntryId = null;
        elements.trainingOverlay.hidden = true;
    }

    function checkAnswers() {
        elements.trainingCheckBtn.disabled = true;
        elements.trainingVerse.querySelectorAll('.blank-input').forEach(input => {
            input.disabled = true;
            const answer = input.value.trim().toLowerCase();
            const expected = input.dataset.expected.toLowerCase();
            if (answer === expected) {
                input.classList.add('blank-correct');
            } else {
                input.classList.add('blank-wrong');
                input.value = input.dataset.expected; // show correct answer
            }
        });
        elements.trainingCheckBtn.hidden = true;
        elements.trainingRatings.hidden = false;
    }

    async function submitRating(quality) {
        elements.trainingRatings.querySelectorAll('.rating-btn').forEach(b => b.disabled = true);
        try {
            await libApi(`/api/memorization/queue/${state.trainingEntryId}/review`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ quality })
            });
        } catch (err) {
            console.error('Failed to submit review:', err);
        }
        closeTraining();
        openMemorization();
    }

    async function renderMemorizationList() {
        let entries;
        try {
            entries = await libApi('/api/memorization/queue');
        } catch (_) {
            entries = [];
        }

        const count = entries.length;
        elements.memorizationResultsCount.textContent =
            count === 0 ? '0 passages' :
            count === 1 ? '1 passage' :
            `${count} passages`;

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
            return `
            <div class="memorization-item" data-entry-id="${escapeHtml(entry.id)}"
                 data-verse-id="${entry.passage.fromVerseId}">
                <div class="memorization-item-body">
                    <div class="memorization-item-ref">${escapeHtml(entry.fromVerseRef)}</div>
                    <div class="memorization-item-text">${escapeHtml(entry.fromVerseText)}</div>
                    <div class="memorization-item-mastery">${dots}</div>
                    <button class="memorization-practice-btn" data-entry-id="${escapeHtml(entry.id)}"
                            aria-label="Practice this verse">Practice</button>
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
                if (entry) openTraining(entry);
            });
        });

        // Remove buttons
        elements.memorizationList.querySelectorAll('.memorization-item-remove').forEach(btn => {
            btn.addEventListener('click', async (e) => {
                e.stopPropagation();
                const entryId = btn.dataset.entryId;
                // Remove from local state map
                Object.keys(state.memorizedPassages).forEach(key => {
                    if (state.memorizedPassages[key] === entryId) {
                        delete state.memorizedPassages[key];
                    }
                });
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
                renderPage();
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

    async function renderLibraryResults() {
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

        const savedVerse = state.savedVerses[verseId];
        elements.noteTextarea.value = savedVerse.note || '';
        updateNoteCharCount();
        elements.noteTextarea.focus();
    }

    function closeNoteEditor() {
        state.noteEditorOpen = false;
        state.noteEditorVerseId = null;
        elements.noteEditorOverlay.hidden = true;
    }

    function updateNoteCharCount() {
        elements.noteCharCurrent.textContent = elements.noteTextarea.value.length;
    }

    function saveNote() {
        const note = elements.noteTextarea.value.trim();
        setVerseNote(state.noteEditorVerseId, note);
        closeNoteEditor();
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

        // Close overlays with Escape (in order of z-index)
        if (e.key === 'Escape') {
            if (state.noteEditorOpen) {
                closeNoteEditor();
            } else if (state.tagPickerOpen) {
                closeTagPicker();
            } else if (state.trainingOpen) {
                closeTraining();
            } else if (state.memorizationOpen) {
                closeMemorization();
            } else if (state.libraryOpen) {
                closeLibrary();
            } else if (state.mobileMenuOpen) {
                closeMobileMenu();
            } else if (state.searchOpen) {
                closeSearch();
            } else if (state.helpOpen) {
                closeHelp();
            }
            return;
        }

        // Don't process other keys if overlays are open
        if (state.searchOpen || state.helpOpen || state.libraryOpen ||
            state.tagPickerOpen || state.noteEditorOpen || state.mobileMenuOpen ||
            state.memorizationOpen || state.trainingOpen) return;

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
                nextChapter();
                break;
            case ',':
                e.preventDefault();
                prevChapter();
                break;
            case '>':
                e.preventDefault();
                nextBook();
                break;
            case '<':
                e.preventDefault();
                prevBook();
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
            case 'm':
                e.preventDefault();
                toggleMemorizeVerse(state.currentVerseId);
                break;
            case 'M':
                e.preventDefault();
                if (state.currentUser) openMemorization();
                else showToast('Sign in to use memorization');
                break;
            case 'p':
                e.preventDefault();
                if (state.audioEnabled) toggleAudio();
                break;
            case 's':
                e.preventDefault();
                if (state.audioEnabled) cycleAudioSpeed();
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
        elements.searchInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') {
                e.preventDefault();
                handleSearch();
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

        // Training modal
        elements.trainingClose.addEventListener('click', closeTraining);
        elements.trainingOverlay.addEventListener('click', (e) => {
            if (e.target === elements.trainingOverlay) closeTraining();
        });
        elements.trainingCheckBtn.addEventListener('click', checkAnswers);
        document.querySelector('.training-rating-btns').addEventListener('click', (e) => {
            const btn = e.target.closest('.rating-btn');
            if (btn) submitRating(parseInt(btn.dataset.quality));
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
        elements.noteCancelBtn.addEventListener('click', closeNoteEditor);
        elements.noteSaveBtn.addEventListener('click', saveNote);
        elements.noteEditorOverlay.addEventListener('click', (e) => {
            if (e.target === elements.noteEditorOverlay) {
                closeNoteEditor();
            }
        });
        elements.noteTextarea.addEventListener('input', updateNoteCharCount);

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
            const verseEl = e.target.closest('.verse');
            if (verseEl) {
                const verseId = parseInt(verseEl.dataset.verseId);
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
        
        // Window resize - reload page to recalculate
        let resizeTimeout;
        window.addEventListener('resize', () => {
            clearTimeout(resizeTimeout);
            resizeTimeout = setTimeout(() => {
                loadPage(state.pageStartVerseId);
            }, 200);
        });

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
                renderPage(); // re-render with merged DB + migrated data
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
                renderPage();
            };
        } else {
            elements.authHeader.hidden = true;
            elements.authSigninLink.hidden = false;
        }
    }

    // ============================================
    // Initialization
    // ============================================

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

            // Load initial page
            await goToVerse(state.currentVerseId);

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
