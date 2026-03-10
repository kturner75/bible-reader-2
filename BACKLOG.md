# KJV Bible Reader — Backlog

Feature ideas for future slices. Not prioritized — just captured for reference.

---

## Memorization

- **Streak tracking** — daily review streak, shown on dashboard
- **Review history** — per-passage history of quality ratings over time
- **Test mode** — hides verse numbers and reference; pure recall from memory
- **Global/shared passages** — pre-built passages (Psalm 23, Lord's Prayer, etc.) available to all users without adding them manually
- **Voice recitation mode** *(low priority)* — microphone input via Web Speech API (or a server-side STT service like OpenAI Whisper); user speaks the verse aloud, app transcribes it and checks accuracy against the expected text (stripping punctuation on both sides before comparing). Particularly useful on mobile. Accuracy scoring would follow the same quality-rating model as fill-in-the-blank. Fallback gracefully when browser lacks speech support.

---

## Notes & Annotations

- **Chapter notes** — same UX as verse notes but scoped to a chapter (e.g. study notes, sermon outline for a chapter). Stored in a `chapter_notes` table keyed on `(user_id, book_id, chapter)`.
- **Book notes / outlines** — high-level notes or structured outline scoped to a whole book, with easy verse deep-linking (e.g. `[Gen 1:1]` auto-links to the reader).
- **Sermon / lesson notes** — freeform notes attached to a collection of verses across chapters/books; essentially a "note with a verse list".
- **Character studies** — verses tagged to a person (Abraham, David, Paul…); auto-populated from a concordance or user-curated.
- **Location studies** — same concept for places (Jerusalem, Egypt, Bethlehem…).

---

## Reading Plans

- **Pre-built plans** — e.g. Read the Bible in a Year (OT + NT interleaved), NT in 90 days, Psalms in a month
- **Custom plans** — user picks start/end date + scope (whole Bible, specific books, etc.)
- **Progress tracking** — mark days complete; dashboard shows today's reading + streak
- **Plans dashboard card** — "Today: Genesis 1–3 + Matthew 1" with a direct link

---

## Dashboard (`/dashboard`)

- *(Slice 5)* Memorization due count + Train Now
- *(Slice 5)* Continue Reading shortcut
- Future: Reading plan today card
- Future: Recent tags / notes quick-access
- Future: Streak calendar heatmap

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

## Reader Enhancements

- **Cross-references** — inline links to parallel passages (e.g. Psalm 22 ↔ Matthew 27)
- **Concordance** — click a word to see all verses containing it
- **Red-letter edition** — words of Christ highlighted
- **Interlinear toggle** — show Strong's numbers or original Hebrew/Greek words beneath the KJV text
- **Print / PDF** — print a chapter or passage with clean typography
