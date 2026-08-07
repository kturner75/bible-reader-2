# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

KJV Bible Reader - A distraction-free, desktop-focused Bible reading web application featuring a two-column layout inspired by physical printed Bibles. Complete rewrite of https://readthekjv.com/.

## Build & Run Commands

```bash
# Local development (port 8081, dev profile)
./dev.sh

# Build the project
mvn clean package

# Run without dev profile (port 8080 — production default)
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
| `GET /api/plans` | Pre-built reading plans (finite, day-numbered) |
| `GET /api/rhythms` | User-defined reading rhythms (recurring lanes) |

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
- `y`: copy current verse's text and reference to clipboard
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

## Reading Plans vs. Reading Rhythms

Two distinct concepts — keep the language straight:

| | **Reading Plan** | **Reading Rhythm** |
|---|---|---|
| Shape | finite, goal-oriented | recurring, self-paced |
| Content | numbered days of fixed verse ranges | lanes, each an ordered book list |
| Progress | `currentDay` of `totalDays` | per-lane chapter cursor |
| Origin | seeded, shared across users | user-defined, per account |
| Schema | V10/V11 `reading_plans` | V19 `reading_rhythms` |
| API | `/api/plans` | `/api/rhythms` |
| Leaving | `DELETE /{id}/enroll` · `POST /{id}/restart` | `DELETE /api/rhythms/{id}` |

**Leaving a plan never deletes history.** `unenroll` removes only the enrollment row;
`reading_plan_completions` survives, because those rows record days actually read and feed the
activity heatmap and streak. Re-enrolling therefore starts at day 1. `restart` is the same idea
for a finished plan — resets `current_day` without dropping the enrollment. Caveat: `completeDay`
is guarded by a unique key on `(user, plan, day)`, so a second pass through a plan re-treads
recorded days and those repeats add nothing to the heatmap.

**Rhythm cursor:** a lane's position is `(cursorBookId, cursorChapter)` — the last chapter
finished. Books earlier in the lane are complete, later ones untouched, so per-book "chapters
read" is derived and never stored. `cursorBookId` is a *book id*, not a list index, so reordering
the lane preserves progress; removing the cursor book resets the lane to not-started.

**`dayOfWeek` is a surfacing hint, never a gate.** ISO 1–7, nullable. It only decides which lane
the dashboard leads with today. `ReadingRhythmService` never consults the current date when
validating a mutation — any lane can be read and marked on any day, and the "All lanes" list
carries the same actions as today's card. Do not add weekday checks to progress endpoints.

**"Today's Reading" card** spans both concepts. It lists every outstanding item — enrolled,
unfinished plan days plus today's unmarked rhythm lanes — ordered plans-first (a plan has a
deadline; a rhythm never does). One item names the passage and opens it; two or more show a count
with the first two named and a "See all →" jump, mirroring how "Due for Review" handles a queue.
A rhythm lane leaves the card once `markedToday` is true (any `reading_rhythm_progress` row dated
today) — it stays fully readable, it simply stops being outstanding. Note the asymmetry: a plan
day does *not* settle this way; completing it advances `currentDay`, so the plan's next day
immediately becomes today's reading. That is pre-existing plan behaviour and deliberately
supports catching up by completing several days in a sitting.

**Reader integration:** `/read?vid=…&lane=N` shows a chip in the reading footer with a
**Stopped here** button that marks the currently displayed chapter. It lives in `.reading-footer`
— never in `.reading-area` — so it costs no reading height and does not affect pagination
measurement. The footer is hidden at ≤600px, so `#mobile-menu-rhythm` carries the same action in
the mobile bottom sheet.

## Dashboard Layout

Section order is by **volatility** — what changes daily sits high, retrospective content sits low:
Memorization Queue → Reading Rhythms → Reading Plans → Sermon Notes → Activity.

Three `<details>` disclosures keep secondary content one click away rather than letting it become
the page's bulk. Nothing is hidden behind navigation; the page is ~2000px instead of ~4000px.

- **All lanes** (`#rhythm-all-disclosure`) — collapsed by default; opens automatically only when
  no lane is scheduled today, so the Rhythms section is never empty-looking.
- **Add a featured passage** (`#featured-disclosure`) — lives *inside* the Memorization Queue,
  because it is a catalogue serving that intent, not a status of its own. Opens by default only
  when the queue is empty, and removes itself once every passage has been queued.
- **Activity** (`#activity-details`) — the heatmap computes cell positions arithmetically, not
  from measured widths, so it renders correctly while collapsed.

If adding a section, ask whether it reports *state* (belongs here) or offers a *catalogue*
(belongs inside the section whose intent it serves).

## Data

- **kjv.json** (`src/main/resources/data/`) - Full KJV text, format: `{BookName: {ChapterNum: ["verse text", ...]}}`
- All data loaded into memory at startup - no external database
- Verse IDs are global continuous integers (Genesis 1:1 = 1, Revelation 22:21 = 31102)
