# KJV Bible Reader — Backlog

Feature ideas for future slices. Not prioritized — just captured for reference.

---

## Memorization

- **Streak tracking** *(done)* — daily review streak, shown on dashboard
- **Review history** *(done)* — per-passage history of quality ratings over time; color-coded dots (green/orange/red) on dashboard queue rows
- **Test mode** *(done)* — hides verse numbers and reference; pure recall from memory; toggle button on training card, preference persisted in localStorage
- **Pre-training passage review + Peek** *(done)* — shows the full passage before each exercise; Peek reveals it again without losing answers or session progress
- **Global/shared passages** *(done)* — 9 curated passages (Psalm 23, Lord's Prayer, Beatitudes, etc.) surfaced as a "Featured Passages" card on the dashboard with one-click Add
- **AI verse suggestions** *(Phase 3 Part 2)* — use OpenAI to suggest verses to memorize based on the user's existing saved/memorized passages
- **Voice recitation mode** *(done — Phase 3 Part 1)* — OpenAI Whisper server-side STT; word-level diff with accuracy score; quality suggestion pre-highlighted on SM-2 rating buttons

---

## Notes & Annotations

- **Chapter notes** *(done — PR #28)* — study notes/sermon outline scoped to a chapter. `chapter_notes` table keyed on `(user_id, book_id, chapter)`, account-only. Includes markdown-lite rendering (headings, bold, italic, lists) and verse links: chapter-relative `[12]` / `[1-11]` / `[1,5,7]` and absolute `[John 3:16]` (normalize to portable `[v=…]` on save).
- **Scope-relative multi-verse links** *(done — PR #51)* — chapter/verse notes: `[1-11]`, `[1,5,7]`, `[1-11,15]`; book notes: `[1-11]` (chapters), `[3:1-11]`, mixed `[1-2,3:16]`. Pre-save render is clickable; save rewrites to `[v=…]`. Grammar: `ScopeRelativeLinkParser` + `app.js` helpers. Sermon notes stay absolute-ref only (no chapter scope).
- **Book notes / outlines** *(done — PR #29)* — whole-book outline/study notes. `book_notes` table (V14), shared note modal, pencil on the page title, Shift+B. Book-relative links: `[12]` / `[1-11]` = chapter(s), `[3:16]` / `[3:1-11]` = verse(s). Library tab merged into a single "Notes" tab.
- **Passage Collections** *(done — PR #30)* — ordered cross-book verse lists, builder modal, scoped reader at `/read/collection/{id}`, `[pid=N]` note links, search autocomplete. V15 migration. `passage_collections` (BIGSERIAL) + `passage_collection_verses` (explicit position). Hotkey `C`.
- **Sermon / lesson notes** *(done — PR #31)* — full-CRUD notes (title + body) on `/dashboard`. `sermon_notes` table (V16). Markdown-lite renderer with `[pid=N]` collection links and `[Reference]` verse links. Dashboard builder: list, create, view, edit, delete.
- **Portable `[v=…]` verse links** *(done)* — note bodies store `[v=verse-id-ranges]`; render as human refs (or Passage title); focused `/read/range?v=…` reader without requiring a Passage row. See `docs/architecture/passage-and-verse-link-model.md`.
- **Absolute multi-verse references in notes** *(done)* — typed full refs `[John 3:16-18]` / `[Jeremiah 13:1-11]` (and comma lists) normalize to multi-segment `[v=…]` on save. `ReferenceParser.parseAbsoluteLink` + `/api/reference` ranges response; reader + `/notes` save/click handlers.
- **Insert Scripture: Bible search + range expand** *(done — PR #40)* — Insert Scripture has Matching Verses (Lucene + reference parse) and My Passages / Featured. Selecting a verse opens chapter-aware surrounding checkboxes (prev/current/next), then inserts portable `[v=…]`.
- **Save as passage from Insert expand** *(done — PR #42/#43)* — optional checkbox + title on the expand step; inserts `[v=…]` first, then fire-and-forget `POST /api/passages` (signed-in only). Natural-key length gated; save only after successful insert.
- **Tabbed header search (verses | passages)** *(done — PR #41)* — header `/` search overlay shares the discovery-tab model: Matching Verses + Matching Passages (title/reference/overlap with hit ranges). Omit Matching Passages when the catalog is empty so the strip stays an expansion joint for future lanes (collections, plans, …).
- **First-class Notes Editor — reader dock** *(done — PR #45)* — verse / chapter / book notes open in a resizable dock beside the reader (desktop side-by-side; full-bleed sheet ≤900px). Insert Scripture stays a modal; reading area remeasures on open/resize; dirty-edit guards and reader shortcuts while docked.
- **Notes workspace for sermon / lesson notes** *(done — PR #49)* — dedicated `/notes` page (list + editor pane) for longer sermon/lesson notes. Dashboard keeps a preview shortcut; editing moved off the modal. Builds on portable `[v=…]` + Insert Scripture; reader dock remains separate for verse/chapter/book notes.
  - **Preserve rkj constraints:** when linked from the reader, scripture stays no-scroll / two-column; desktop-first.
  - **Depends on / pairs with:** portable `[v=…]` links, Insert Scripture search, reader notes dock.
- **Embed quoted scripture `[e=…]`** *(done — PR #67)* — render-mode twin of `[v=…]`. Same `VerseRangeParser` grammar; note body stores verse ids only (resolve KJV at render time); quoted block + human ref that still opens `/read/range?v=…`. See `docs/architecture/passage-and-verse-link-model.md` and `docs/architecture/portable-verse-links-plan.md`.
  - **Token / grammar:** `[e=14625]`, `[e=14625-14627]`, `[e=14625-14627,14630]` — same ranges as `[v=…]`.
  - **Storage:** do **not** paste KJV text into the saved body.
  - **Embed mode:** a **note-editor** toggle (default **off**), one flag per open note editor. Default off so existing `[v=…]` behavior is unchanged until the operator turns it on.
  - **Insert Scripture:** the “Embed quoted scripture” checkbox is that **same** flag shown in the modal (reuse the save-as-passage checkbox pattern). Not a second modal, not a second independent sticky. Writes `[e=…]` or `[v=…]` from that same flag; same picker/ranges.
  - **Typed refs:** save-normalize of typed human refs (`[John 3:16]`, ranges, comma lists) writes `[e=…]` when embed mode is on, `[v=…]` when off.
  - **Surfaces:** every note surface that already renders `[v=…]` (sermon/lesson, verse/chapter/book), not sermon-only.
  - **First-cut cap:** refuse an embed over 12 verses (insert and save-normalize) with a clear error. Do not silently truncate.
  - **Invariants:** portable verse ids, no Passage UUID, no new Passage row required.
- **Searchable notes grid** *(done)* — `/notes` now opens as a **finder**: search field, filters, and a card grid; choosing a note swaps to the existing sidebar + editor with an "All notes" back link. The editor pane, `[e=…]` embeds and Print are untouched. See `docs/architecture/notes-finder-search.md`.
  - **Search** runs server-side over title, body, and the *names of books the note cites* — so typing "john" finds a note whose only mention of John is an encoded `[e=26136]`. It deliberately does not match the KJV text behind a reference.
  - **Scripture chips** on each card come from `sermon_note_refs` (V22), a derived `(book_id, chapter)` index rebuilt on every save, with a startup backfill for notes written earlier. Snippets swap tokens for their chapter label rather than showing `[v=…]` raw.
  - **Filters:** rolling updated-window (30 days / past year), book of the Bible (only books the user has actually cited), and sort. Sort persists per device; search text and filters reset on reload — arrangement is a preference, a query is not.
  - **Out of scope, as specced:** reader dock (verse/chapter/book notes).
- **Character studies** — verses tagged to a person (Abraham, David, Paul…); auto-populated from a concordance or user-curated.
- **Location studies** — same concept for places (Jerusalem, Egypt, Bethlehem…).

---

## Plans & Reading Rhythms

- **Pre-built plans** *(done — PR #21)* — Read the Bible in a Year, NT in 90 days, Psalms in a month, etc.
- **Progress tracking + streak** *(done — PR #22)* — mark days complete; dashboard shows today's reading card + streak
- **Custom reading plans** — finite assignments toward a goal; user picks a start/end date and scope (whole Bible, specific books, etc.). These answer, “What must I read today to finish by a target date?”
- **Personal reading rhythms** *(done)* — recurring, self-paced lanes that preserve continuity while blending different parts of Scripture throughout the week. No deadline or required chapters per session. Each lane holds an ordered list of books and its own chapter cursor `(cursorBookId, cursorChapter)` = the last chapter finished; per-book progress is derived, never stored. V19 schema (`reading_rhythms`, `reading_rhythm_lanes`, `reading_rhythm_lane_books`, `reading_rhythm_progress`) + `/api/rhythms`.
  - **Weekday is a suggestion, not a gate.** `lane.dayOfWeek` (ISO 1–7, nullable) only decides which lane leads the dashboard today; nothing server-side consults the date, so any lane may be read and marked on any day. No lane today → "Nothing scheduled today"; several → all render, first is primary; weekday-less lanes never auto-surface.
  - Dashboard section with today's lead card + "All lanes"; builder modal with a **weekly template** matching the user's spreadsheet, category-grouped book picker, and a "set position" control for transcribing an existing plan. Reader shows a footer chip with **Stopped here** when opened via `/read?vid=…&lane=N` (mobile menu equivalent, since the footer is hidden ≤600px).
  - Remaining ideas: streak per rhythm; archive rather than restart a finished lane; a lane-level "read N chapters" nudge.
- **Focused study plans** — a sequence of study sessions centered on a book, biblical person, theme, doctrine, event, or question rather than necessarily reading the whole Bible. A session may contain one or more passages plus optional prompts, notes, linked saved verses, and observations. Reuse Passage Collections and existing notes where practical. Examples: Abraham, faith across Romans/Hebrews/James, the kingdom of God, or Messianic prophecy and its New Testament connections.
- **Product language** — keep these concepts distinct: a *Reading Plan* is finite and goal-oriented; a *Reading Rhythm* is recurring and self-paced; a *Study Plan* is focused and passage-driven.

---

## Dashboard (`/dashboard`)

- *(done — Slice 5)* Memorization due count + Train Now
- *(done — Slice 5)* Continue Reading shortcut
- *(done — PR #22)* Reading plan today card + streak
- *(done — PR #23)* Activity heatmap
- Future: Recent tags / notes quick-access

---

## Library / Saved Verses

- **Export** — download saved verses + notes as PDF, Markdown, or plain text
- **Import** — bulk-add saved verses from a JSON or CSV file
- **Sharing** — generate a shareable link for a tag collection (read-only)

---

## Infrastructure / Accounts

- **PostgreSQL** — already implemented; keep on DO managed cluster
- **Donation / About** *(not started)* — a small **About** page is the home for support copy, not a money widget on the reader. **Never on `/read`** (distraction-free / no extra chrome).
  - **About page:** new public page (e.g. `/about`). State that the site is **self-funded**, and that if someone wants to support the project, here is how. Quiet tone — not a pitch deck.
  - **Donate:** one external link on that page (Buy Me a Coffee, Stripe Payment Link, or similar). Not in-app checkout, not a floating widget.
  - **Entry points:** landing chrome/footer and/or signed-in `/dashboard` nav/footer link to About (not the hero Start Reading CTA). Donate itself lives on About, not duplicated as a loud button everywhere.
  - **Provider URL:** decide at implement time — do not invent an account in the lock.
- **Email notifications** — optional daily reminder to review due passages or read today's plan passage
- **Mobile app** — React Native shell wrapping the web reader + push notifications for due reviews

---

## Prayer

- **Prayer list** — personal prayer requests with optional notes and a status (active, answered, archived). Local-only by default (stored in DB under the user's account) for privacy. Each item can have threaded comments/updates over time.
- **Prayer Circle** *(dependent on Prayer list)* — opt-in sharing of a prayer list (or subset of items) with a named group of users. Members can see each other's requests and add encouragement/comments. Each request retains its status so the circle can celebrate answered prayers together. Privacy model TBD (invite-only vs. open groups).

---

## Reader Enhancements

- **Tabbed search results (verses | passages)** *(done — PR #41)* — header `/` search. Future lanes can join the same strip.
- **Cross-references** — inline links to parallel passages (e.g. Psalm 22 ↔ Matthew 27)
- **Concordance** — click a word to see all verses containing it
- **Red-letter edition** — words of Christ highlighted
- **Interlinear toggle** — show Strong's numbers or original Hebrew/Greek words beneath the KJV text
- **Print / PDF** — print a chapter or passage with clean typography
