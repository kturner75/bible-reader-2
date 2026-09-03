# Notes finder — search and scripture indexing

**Status:** Accepted
**Scope:** `/notes` (sermon / lesson notes). Verse, chapter and book notes in the reader dock are out of scope.

Supersedes nothing. Implements the "Searchable notes grid" backlog item.

---

## Context

`/notes` browsed sermon notes as a single-column list in a 320px gutter (`notes-sidebar` /
`sermon-notes-list`), rendered from `GET /api/sermon-notes`, which returns every note the user owns
with a **fixed first-160-character snippet** and nothing about scripture. That is fine at a dozen
notes and useless at two hundred: there is no search, no filter, and the only ordering is
`updatedAt DESC`.

The workspace also carries content the list cannot see. A note body stores portable
`[v=…]` / `[e=…]` tokens — verse-id ranges — so the scripture a note is *about* is already in the
body, encoded, and invisible to any text search a reader would think to type. Searching for
`psalm` finds notes that say "psalm" and misses the note whose entire subject is `[e=14237-14242]`.

Three decisions had to be made together, because each constrains the next.

---

## Decision 1 — the finder is a view, not a filter on the gutter

`/notes` opens as a **full-width finder**: search field, filter row, and a card grid. Choosing a
note swaps to the existing sidebar + editor layout with a back link; the editor pane, `[e=…]`
embeds and Print are untouched.

**Rejected — search inside the widened sidebar.** Cheapest change and keeps the editor permanently
on screen, but 380px has no room for match context or scripture chips, which is most of what makes
a finder worth building. It produces a better list, not a finder.

**Rejected — a finder overlay over the untouched workspace**, mirroring the reader's Library modal.
Genuinely attractive: zero disruption, a familiar pattern, cheap to back out of. Rejected because a
transient grid keeps browsing a detour — the catalogue never becomes the thing you land on. It
composes with this decision rather than competing with it, so a quick-jump hotkey opening the same
finder over the editor remains available later.

**Consequence:** `/notes` gains a mode. The reader who arrives to write rather than to find pays one
click. Accepted, because a note you have opened is a note you have stopped browsing for.

---

## Decision 2 — search runs on the server

`GET /api/sermon-notes` takes `q`, `updatedWithin`, `bookId` and `sort`; filtering happens in
Postgres.

**Rejected — filter the existing payload in the browser.** It is frontend-only work and it cannot
meet the requirement: the list endpoint ships a 160-character snippet, not the body, so a
client-side match would silently fail on any word past character 160. The fix — widening the DTO to
carry every full body — means shipping the entire corpus on page load to search it, which is the
scaling problem the item exists to solve, moved to the network.

**Consequence:** typing is a round trip. Debounced at 200ms, and the corpus is per-user and small,
so this is not the query that will hurt.

---

## Decision 3 — scripture references are extracted at write time

A note's `[v=…]` / `[e=…]` tokens are resolved to `(book_id, chapter)` pairs and stored in
`sermon_note_refs` on every create and update. This backs both the scripture chips on each card and
the "Scripture: any book" filter.

**Rejected — resolve at read time.** No schema change, nothing to keep in sync, and correct by
construction since the body is the only source of truth. Rejected because it puts a parse of every
matching note body inside every query, and it cannot be indexed: filtering by book would mean
parsing the whole corpus per keystroke — reintroducing decision 2's problem on the server side.

**Consequence — the derived rows can drift.** They are a cache of something the body already says,
so anything that writes a body without going through `SermonNoteService` leaves them stale. Two
guards:

- Re-indexing is delete-then-insert inside the same transaction as the note save. There is no
  partial-update path to get wrong.
- A startup backfill indexes, in batches, every note with no rows in `sermon_note_refs` — so notes
  written before this slice are picked up without a hand-run script.

**No `indexed_at` marker, deliberately.** The obvious design — a `refs_indexed_at` column on
`sermon_notes`, null until indexed — is wrong here: `trg_sermon_notes_updated_at` sets
`NEW.updated_at = now()` on *every* update, so stamping that column would bump `updated_at` on
every note the backfill touched, corrupting the field the finder sorts and filters by. A separate
marker table would avoid that at the cost of a second table for bookkeeping nobody reads. Instead
"indexed" is simply "has rows", which re-scans ref-less notes once per boot — a regex over a body
that finds nothing, batched, at a scale where it does not register.

**Consequence — granularity is book and chapter, not verse.** A chip reads `Psalm 23`, not
`Psalm 23:1-6`. Chapter is the level a reader browses at, it keeps one row per chapter rather than
per verse, and the note body remains the authority for the exact range.

---

## What "search" covers

| Field | Matched | Notes |
|---|---|---|
| Title | yes | |
| Body text | yes | the stored body, including token text |
| Scripture refs | yes | via the extracted `(book_id, chapter)` rows and their book names |
| Verse *text* of a referenced passage | **no** | a note citing John 3:16 does not match `begotten` |

That last row is deliberate. Matching the KJV text behind a reference means joining the finder to
the Lucene index, which is a different feature ("find notes about verses that say X") and would
make `q` mean two things at once.

---

## Snippet

The card snippet becomes **match context** — the text surrounding the first hit — rather than the
first 160 characters, and falls back to the leading text when there is no `q`. The grid card clamps
it at two lines (~110–130 characters at the desktop column width), so the old fixed 160 overflows
the space it is given.

---

## Invariants preserved

- Note bodies still store portable verse ids. Nothing here writes a Passage row or a UUID link.
- `sermon_note_refs` is derived. Dropping the table and re-running the backfill is lossless.
- The reader's no-scroll / two-column constraints are untouched — `/notes` is not the reader.
