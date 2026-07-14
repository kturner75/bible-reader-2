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
- **Book notes / outlines** — high-level notes or structured outline scoped to a whole book. The chapter-notes markdown renderer + verse deep-linking (`[Gen 1:1]`) is built and reusable; this becomes a `book_notes` table (V14) + parallel endpoints.
- **Sermon / lesson notes** — freeform notes attached to a collection of verses across chapters/books; essentially a "note with a verse list".
- **Character studies** — verses tagged to a person (Abraham, David, Paul…); auto-populated from a concordance or user-curated.
- **Location studies** — same concept for places (Jerusalem, Egypt, Bethlehem…).

---

## Reading Plans

- **Pre-built plans** *(done — PR #21)* — Read the Bible in a Year, NT in 90 days, Psalms in a month, etc.
- **Progress tracking + streak** *(done — PR #22)* — mark days complete; dashboard shows today's reading card + streak
- **Custom plans** — user picks start/end date + scope (whole Bible, specific books, etc.)

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

- **Cross-references** — inline links to parallel passages (e.g. Psalm 22 ↔ Matthew 27)
- **Concordance** — click a word to see all verses containing it
- **Red-letter edition** — words of Christ highlighted
- **Interlinear toggle** — show Strong's numbers or original Hebrew/Greek words beneath the KJV text
- **Print / PDF** — print a chapter or passage with clean typography
