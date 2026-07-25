# KJV Bible Reader — Backlog

Feature ideas for future slices. Not prioritized — just captured for reference.

---

## Memorization

- **Streak tracking** *(done)* — daily review streak, shown on dashboard
- **Review history** *(done)* — per-passage history of quality ratings over time; color-coded dots (green/orange/red) on dashboard queue rows
- **Test mode** *(done)* — hides verse numbers and reference; pure recall from memory; toggle button on training card, preference persisted in localStorage
- **Global/shared passages** *(done)* — 9 curated passages (Psalm 23, Lord's Prayer, Beatitudes, etc.) surfaced as a "Featured Passages" card on the dashboard with one-click Add
- **AI verse suggestions** *(Phase 3 Part 2)* — use OpenAI to suggest verses to memorize based on the user's existing saved/memorized passages
- **Voice recitation mode** *(done — Phase 3 Part 1)* — OpenAI Whisper server-side STT; word-level diff with accuracy score; quality suggestion pre-highlighted on SM-2 rating buttons

---

## Notes & Annotations

- **Chapter notes** *(done — PR #28)* — study notes/sermon outline scoped to a chapter. `chapter_notes` table keyed on `(user_id, book_id, chapter)`, account-only. Includes markdown-lite rendering (headings, bold, italic, lists) and verse links: `[12]` (chapter-relative) and `[John 3:16]` (any reference) jump the reader.
- **Book notes / outlines** *(done — PR #29)* — whole-book outline/study notes. `book_notes` table (V14), shared note modal, pencil on the page title, Shift+B. Book-relative verse links: `[12]` = chapter, `[3:16]` = chapter:verse. Library tab merged into a single "Notes" tab.
- **Passage Collections** *(done — PR #30)* — ordered cross-book verse lists, builder modal, scoped reader at `/read/collection/{id}`, `[pid=N]` note links, search autocomplete. V15 migration. `passage_collections` (BIGSERIAL) + `passage_collection_verses` (explicit position). Hotkey `C`.
- **Sermon / lesson notes** *(done — PR #31)* — full-CRUD notes (title + body) on `/dashboard`. `sermon_notes` table (V16). Markdown-lite renderer with `[pid=N]` collection links and `[Reference]` verse links. Dashboard builder: list, create, view, edit, delete.
- **Portable `[v=…]` verse links** *(done)* — note bodies store `[v=verse-id-ranges]`; render as human refs (or Passage title); focused `/read/range?v=…` reader without requiring a Passage row. See `docs/architecture/passage-and-verse-link-model.md`.
- **Insert Scripture: Bible search + range expand** *(done — PR #40)* — Insert Scripture has Matching Verses (Lucene + reference parse) and My Passages / Featured. Selecting a verse opens chapter-aware surrounding checkboxes (prev/current/next), then inserts portable `[v=…]`. Optional “Save as passage” remains a follow-on.
- **Tabbed header search (verses | passages)** *(done — PR #41)* — header `/` search overlay shares the discovery-tab model: Matching Verses + Matching Passages (title/reference/overlap with hit ranges). Omit Matching Passages when the catalog is empty so the strip stays an expansion joint for future lanes (collections, plans, …).
- **First-class Notes Editor (not a modal)** — verse / chapter / book / sermon notes currently share compact modal editors. As notes become the hub for portable `[v=…]` links, outlines, and later study/sermon work, the editor needs a dedicated, distraction-aware surface (full route or docked panel), not a transient overlay.
  - **Why:** room for longer writing, Insert Scripture / search, preview of rendered markdown + scripture labels, backlinks to open ranges, and eventually multi-note / outline structure without fighting z-index and focus traps.
  - **Preserve rkj constraints:** reading area stays no-scroll / two-column when the editor is closed or side-by-side; desktop-first; scripture remains the focus when reading.
  - **Candidate shapes:** (A) `/notes/...` full-page editor with optional “read linked range” pane; (B) resizable dock beside the reader; (C) library-driven note workspace that opens the editor as the primary chrome. Prefer one primary pattern across verse, chapter, book, and sermon notes.
  - **Depends on / pairs with:** portable `[v=…]` links, Insert Scripture search, focused range reader; feeds character/location studies and focused study plans later.
- **Character studies** — verses tagged to a person (Abraham, David, Paul…); auto-populated from a concordance or user-curated.
- **Location studies** — same concept for places (Jerusalem, Egypt, Bethlehem…).

---

## Plans & Reading Rhythms

- **Pre-built plans** *(done — PR #21)* — Read the Bible in a Year, NT in 90 days, Psalms in a month, etc.
- **Progress tracking + streak** *(done — PR #22)* — mark days complete; dashboard shows today's reading card + streak
- **Custom reading plans** — finite assignments toward a goal; user picks a start/end date and scope (whole Bible, specific books, etc.). These answer, “What must I read today to finish by a target date?”
- **Personal reading rhythms** — recurring, self-paced lanes that preserve continuity while blending different parts of Scripture throughout the week. No deadline or required chapters per session. Each lane contains an arbitrary ordered list of books, keeps independent chapter-level progress, opens the next unread chapter, and lets the reader mark where the session ended. Show chapters remaining instead of “Day X of Y”; consider archive/restart behavior when a lane or rhythm is complete.
  - Initial weekly rhythm: Sunday (Matthew, Mark, Luke, John, Acts, Romans); Monday (Joshua, Judges, Ruth, Ezra, Nehemiah, Esther, Ezekiel, Daniel); Tuesday (1–2 Samuel, 1–2 Kings, 1–2 Chronicles); Wednesday (1 Corinthians–Revelation); Thursday (Isaiah, Jeremiah, Lamentations, Hosea–Malachi); Friday (Job, Psalms, Proverbs, Ecclesiastes, Song of Solomon); Saturday (Genesis–Deuteronomy).
  - Dashboard language could be “Continue with Luke 5”; completion could be “Mark through Luke 7.” Preserve the distraction-free, page-turn reader rather than imposing a fixed daily assignment boundary.
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
- **Donation button** — Stripe or Buy Me a Coffee integration on landing/dashboard
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
