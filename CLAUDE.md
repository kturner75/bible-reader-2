# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

KJV Bible Reader - A distraction-free, desktop-focused Bible reading web application featuring a two-column layout inspired by physical printed Bibles. Complete rewrite of https://readthekjv.com/.

## Build & Run Commands

```bash
# Build the project
mvn clean package

# Run the application (starts on port 8080)
mvn spring-boot:run

# Run all tests
mvn test

# Run a single test class
mvn test -Dtest=ReferenceParserTest

# Run a specific test method
mvn test -Dtest=ReferenceParserTest#testBookWithChapterAndVerse
```

## Architecture

### Backend (Java 21 + Spring Boot 3)

**Package:** `com.readthekjv`

- **BibleController** (`controller/`) - REST API endpoints for verses, books, chapters, search, navigation
- **BibleService** (`service/`) - In-memory Bible data with O(1) verse lookups by global ID (1-31,102)
- **LuceneIndexService** (`service/`) - In-memory Lucene full-text search, built at startup
- **BibleDataLoader** (`service/`) - @PostConstruct loader that parses `kjv.json` and initializes services
- **ReferenceParser** (`util/`) - Parses Bible references ("john 3:16", "ps 23", "gen1:1") with 170+ book aliases
- **Models** (`model/`) - Java records: Verse, Book, ChapterInfo, SearchResult

**Data Flow:** KJV JSON → BibleDataLoader → BibleService (verse map) + LuceneIndexService (search index)

### Frontend (Vanilla HTML5/CSS3/ES6+)

Static files in `src/main/resources/static/`:

- **index.html** - Semantic structure with fixed header, two-column reading area, search/help modals
- **style.css** - CSS custom properties, EB Garamond typography, multi-column layout
- **app.js** - State management, LocalStorage persistence, keyboard navigation, API layer

### Key API Endpoints

| Endpoint | Purpose |
|----------|---------|
| `GET /api/verses?from=&count=` | Fetch verse range (max 200) |
| `GET /api/books` | All 66 books with metadata |
| `GET /api/books/{id}/chapters` | Chapters for a book |
| `GET /api/search?q=&limit=` | Full-text Lucene search |
| `GET /api/reference?ref=` | Parse Bible reference string |
| `GET /api/navigate/{currentId}` | Navigation helpers |

## Non-Negotiables

These constraints must be preserved in all changes:

- **No scrolling** - Page-turn navigation only, never scroll the reading area
- **Two-column layout** - CSS Multi-column, verses flow down column 1 then column 2
- **Distraction-free** - Scripture text is the sole focus, minimal UI chrome
- **Desktop-first** - Primary target is desktop/laptop; mobile is secondary

## Keyboard Shortcuts

Frontend implements vim-style navigation:
- `j/k` or arrows: next/previous verse
- `h/l` or arrows: previous/next page
- `,/.`: previous/next chapter
- `</>`: previous/next book
- `/`: focus search bar
- `?`: toggle help modal
- `b`: save/unsave current verse
- `t`: open tag picker for current verse
- `n`: add/edit note on current verse
- `Esc`: close overlays

## Saved Verses Feature

Users can save verses with optional tags and notes. All data persists in localStorage.

**Data Model:**
- `kjv_saved_verses`: `{ verseId: { id, savedAt, tagIds[], note } }`
- `kjv_tags`: `{ tagId: { id, name, colorIndex, createdAt } }`

**Limits:** 20 char tag name, 50 total tags, 5 tags per verse, 500 char note

**UI Components:**
- Hamburger menu (☰) opens full-screen Library modal with filtering
- Library modal features:
  - Text search (searches verse text + notes)
  - Multi-select category pills (OR logic - matches any selected category)
  - Multi-select book pills (OR logic - matches any selected book, filtered by selected categories)
  - Multi-select tag pills (OR logic - matches any selected tag)
  - Sort options: Newest First, Oldest First, Bible Order
  - Filter logic: AND between filter types (search AND categories AND books AND tags), OR within each filter type
- Tag picker modal with checkbox list + create new tag
- Note editor modal with textarea
- Visual indicator: 4px color bar in left margin of saved verses with stacked tag color dots

**Book Categories:**
- Pentateuch, Historical, Wisdom & Poetry, Major Prophets, Minor Prophets
- Gospels, Acts, Pauline Epistles, General Epistles, Revelation

**Z-Index Hierarchy:** header=100, search=200, library=250, help=300, tag picker/note editor=350, loading=400

## Data

- **kjv.json** (`src/main/resources/data/`) - Full KJV text, format: `{BookName: {ChapterNum: ["verse text", ...]}}`
- All data loaded into memory at startup - no external database
- Verse IDs are global continuous integers (Genesis 1:1 = 1, Revelation 22:21 = 31102)
