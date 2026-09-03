# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

KJV Bible Reader - A distraction-free, desktop-focused Bible reading web application featuring a two-column layout inspired by physical printed Bibles. Complete rewrite of https://readthekjv.com/.

## Build & Run Commands

Requires a local PostgreSQL database (Flyway runs on startup; JPA `ddl-auto=validate`). Cloud Agents use `.cursor/start.sh` to provision one; locally create a DB matching `KJV_DB_*` in `application.properties` (or `./dev.sh` if present).

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
- **Models** (`model/`) - Java records: Verse, Book, ChapterInfo, SearchResult; JPA entities under `model/entity/` for account data
- **PostgreSQL + Flyway** - User accounts, library sync, memorization, reading plans/rhythms, notes, and related tables live in Postgres (`src/main/resources/db/migration/`). JPA `ddl-auto=validate` — migrations must apply before the app will start.

**Data Flow:**
- Scripture text: KJV JSON → BibleDataLoader → BibleService (verse map) + LuceneIndexService (search index)
- Account / content state: PostgreSQL (Flyway-managed schema) ↔ Spring Data JPA repositories / services

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

**Two gotchas worth not re-learning:**
- `uq_reading_rhythm_lane_position` is **DEFERRABLE INITIALLY DEFERRED** (V20). Reordering lanes
  swaps positions, and Hibernate's per-entity UPDATEs transiently duplicate one; an immediate
  constraint fails the first statement.
- A book appears **at most once per lane**. The cursor is keyed on book id, so a repeat is
  ambiguous — `nextReading` would advance into the second occurrence while `chaptersRead`
  resolved back to the first. Rejected in `validateBookIds`.

## Calendar days belong to the reader, not the server

Anything the app calls "today" — a due passage, a scheduled review, a heatmap square, the lane the
dashboard leads with — means today *where the reader is*. The server's zone is an accident of
deployment and is wrong for most users on a UTC host.

- **Client:** `date-utils.js` (`window.KjvDate`) is the single definition of the browser's calendar
  day — `localIsoDate` / `todayIso` / `isEntryDue`. Loaded by `index.html` and `dashboard.html`.
  Never derive a calendar date with `toISOString()`; it converts to UTC first and is off by one on
  one side of the meridian or the other.
- **Server:** clients send `X-Time-Zone` (IANA); `RequestZone.resolve` turns it into a `ZoneId`,
  falling back to the server's when absent or unparseable — a bad header degrades the boundary,
  it never fails the request. Used by rhythms (`markedToday`, `todayLaneIds`), the activity
  heatmap, and memorization review scheduling.
- **Both sides must agree.** `nextReviewAt` is written by the server and compared by the client;
  if they use different days an "Again" review comes back due immediately, or waits an extra day.
  The same boundary drives the review streak, so it moves with the scheduling.
- A new shared static asset needs adding to `SecurityConfig`'s `permitAll` — `/read` is public, so
  a 302 on `date-utils.js` would leave `window.KjvDate` undefined and break the reader for
  signed-out visitors.

**Reader integration:** `/read?vid=…&lane=N` shows a chip in the reading footer with a
**Stopped here** button that marks the currently displayed chapter. It lives in `.reading-footer`
— never in `.reading-area` — so it costs no reading height and does not affect pagination
measurement. The footer is hidden at ≤600px, so `#mobile-menu-rhythm` carries the same action in
the mobile bottom sheet.

## Remembering what the reader chose

**The reader should never have to redo the same clicks to get back to the view they already
asked for.** Every user-controllable view option persists — collapsed sections, chosen tabs, sort
orders, font size, playback speed, panel widths, mode toggles. A control that resets on reload is
a bug, not a default.

Where it persists depends on what it is:

| | **View state** | **Content** |
|---|---|---|
| Examples | disclosures, tabs, sort order, font size, audio speed, note-dock width, test/recite mode | notes, bookmarks, tags, collections, rhythms, review schedules, reading position |
| Home | localStorage, per device | database, follows the reader everywhere |
| If lost | mildly annoying | lost work |

That last row is the test to apply when adding something new: *if this vanished when they opened
the app on another machine, would they be annoyed or would they have lost work?* Annoyed →
localStorage. Lost work → a table. localStorage may cache content (the anonymous-user library
does), but it is never the only copy of something the reader wrote.

`view-prefs.js` (`window.KjvViewPrefs`) is the single mechanism: `get(key, fallback)`,
`set(key, value)` (JSON-encoded, wrapped so Safari private mode degrades to defaults rather than
throwing), and `bindDisclosure(el, fallbackOpen)` for `<details>`. Loaded by `index.html` and
`dashboard.html`; like `date-utils.js` it needs `permitAll` in `SecurityConfig`, since `/read` is
public and a 302 would leave `window.KjvViewPrefs` undefined. A few older preferences
(`kjv_font_size`, `kjv_audio_speed`, train's mode keys) still call `localStorage` directly — same
principle, older plumbing; move them across when you next touch them.

Two rules for `bindDisclosure`:
- **A derived default is a first-visit default, not standing policy.** "Open All lanes when
  nothing is scheduled today" applies until the reader expresses a preference; after that theirs
  wins. Pass the rule as `fallbackOpen` and never assign `.open` directly on a bound element —
  renderers that re-derive `open` would stomp the stored choice, so call it on every render.
- **Programmatic changes are not recorded**, or a default would masquerade as a decision the
  reader made. `toggle` fires asynchronously, hence the `pendingProgrammatic` flag rather than a
  synchronous reset.

**Preference, not query.** The line runs between how a view is *arranged* and what it is
*restricted to*. The Library remembers its tab, sort order and whether More Filters is expanded;
it still clears search text and filter pills on close, because a forgotten filter makes a full
library look empty. Persist arrangement; reset queries.

**A cursor is not a commitment.** `kjv_current_verse` — where the reader happens to be right now —
stays in localStorage, deliberately. It changes on every verse and every page turn, so it is a
scroll position, not a decision worth syncing; the dashboard's Continue Reading card reads it
from there. The reading state a reader *does* expect on their phone is the deliberate kind: a
rhythm lane's cursor and a plan's `currentDay`, which are already server-side and touch
localStorage nowhere. When something new looks like "reading position", ask which of the two it
is — incidental (local) or committed (database).

## The `/notes` finder

`/notes` opens as a **finder** — search field, filters, card grid — and choosing a note swaps to
the sidebar + editor workspace with an "All notes" back link. The editor pane, `[e=…]` embeds and
Print are untouched by this; the finder is a way *in*, not a new editor.

**Search is server-side, and it reaches scripture the reader never typed.** `GET /api/sermon-notes`
takes `q`, `bookId`, `updatedWithin` and `sort`. `q` matches title, body, *and the names of books
the note cites* — so "john" finds a note whose only mention of John is an encoded `[e=26136]`. It
deliberately does **not** match the KJV text behind a reference: a note citing John 3:16 does not
match "begotten". That is a different feature, and folding it in makes one parameter mean two things.

**`sermon_note_refs` (V22) is derived, never authored.** Portable `[v=…]` / `[e=…]` tokens are
resolved to `(book_id, chapter)` on every save — delete-then-insert in the note's own transaction —
and a startup backfill claims anything with no rows. Dropping the table and rebooting is lossless.
Granularity is book+chapter: a chip reads `Psalm 23`, and the body stays the authority for the
exact range.

Three traps worth not re-learning:

- **No `indexed_at` column on `sermon_notes`.** `trg_sermon_notes_updated_at` sets
  `updated_at = now()` on *every* UPDATE, so stamping an indexing marker there would bump the
  timestamp the finder sorts and filters by on every note the backfill touched. "Indexed" is
  "has rows"; ref-less notes are re-scanned once per boot, which is a regex that finds nothing.
- **The finder query compares values, never `NULL`.** Postgres cannot infer the type of a bare
  parameter in `? IS NULL`, so `(:since IS NULL OR …)` fails at runtime with "could not determine
  data type of parameter". Optional filters pass match-everything values instead — `"%"`, `-1`,
  the epoch — and `IN` gets a sentinel list, since JPQL has no `IN ()`.
- **Card geometry is load-bearing.** The snippet is clamped to exactly two lines and the card
  height is built from that sum; changing one without the other clips the snippet mid-line. The
  server's snippet budget (140 chars) is sized to the same two lines.

Sort persists via `KjvViewPrefs`; search text and filters do not. Returning from a note keeps the
active search — that is navigating within a finder session, not landing on a filtered view — but a
reload always starts clean. See "Remembering what the reader chose" and
`docs/architecture/notes-finder-search.md`.

## Dashboard Layout

Section order is by **volatility** — what changes daily sits high, retrospective content sits low:
Memorization Queue → Reading Rhythms → Reading Plans → Sermon Notes → Activity.

Three `<details>` disclosures keep secondary content one click away rather than letting it become
the page's bulk. Nothing is hidden behind navigation; the page is ~2000px instead of ~4000px.

- **All lanes** (`#rhythm-all-disclosure`) — collapsed by default; opens automatically only when
  no lane is scheduled today, so the Rhythms section is never empty-looking.
- **Scheduled for later** (`#queue-later-disclosure`) — the Memorization Queue lists only what is
  due; everything else lives here, always collapsed by default. A long list of passages that are
  explicitly *not* actionable today is the exact thing this disclosure exists to keep off the
  page, and "All caught up — nothing due today" already carries the state, so it does not
  auto-open the way All lanes does. Holds *only* the not-yet-due entries, never a copy of a due
  one: a record rendered twice with two live sets of controls is what produced the rhythm lanes'
  stale-copy and double-submit bugs.
- **Add a featured passage** (`#featured-disclosure`) — lives *inside* the Memorization Queue,
  because it is a catalogue serving that intent, not a status of its own. Opens by default only
  when the queue is empty, and removes itself once every passage has been queued.
- **Activity** (`#activity-details`) — the heatmap computes cell positions arithmetically, not
  from measured widths, so it renders correctly while collapsed.

If adding a section, ask whether it reports *state* (belongs here) or offers a *catalogue*
(belongs inside the section whose intent it serves).

**The open/closed rules above are first-visit defaults only** — each disclosure is bound through
`KjvViewPrefs.bindDisclosure`, so once the reader toggles a section their choice wins on every
later visit. A new disclosure needs an `id` and must go through `bindDisclosure`, never a bare
`.open =`. See "Remembering what the reader chose".

## Data

Two stores — keep them distinct:

| Store | What it holds | Lifetime |
|---|---|---|
| **In-memory** (`kjv.json` → `BibleService` + Lucene) | Full KJV scripture text and search index | Loaded at startup; never written at runtime |
| **PostgreSQL** (Flyway migrations V1–V21+) | Users, auth (OAuth2 / remember-me), synced library, tags, notes, memorization, reading plans & rhythms, sermon notes, verse-of-the-day, etc. | Durable; required for the app to start (`ddl-auto=validate`) |

- **kjv.json** (`src/main/resources/data/`) - Full KJV text, format: `{BookName: {ChapterNum: ["verse text", ...]}}`
- Verse IDs are global continuous integers (Genesis 1:1 = 1, Revelation 22:21 = 31102)
- **Local DB defaults** (override via env): `KJV_DB_HOST` / `KJV_DB_PORT` / `KJV_DB_NAME` / `KJV_DB_USERNAME` / `KJV_DB_PASSWORD` — see `application.properties`. Cloud Agent / `.cursor/start.sh` provisions a local `readthekjv` database for development.
