# Passage & Verse Link Model

**Status:** Accepted — source of truth for scripture linking  
**Product:** rkj / bible-reader-2 / readthekjv.com  
**Date:** 2026-07-25  
**Index:** [README.md](./README.md)

This document locks the layering so notes, collections, memorization, and future study features share one portable scripture model.

It **supersedes** the note-link portion of the first-class Passages plan (which briefly used `[passage=<uuid>]` as the insert token). Passage entities, collection membership, and focused `/read/passage/{uuid}` from that work remain valid; see [first-class-passages.md](./first-class-passages.md).

**Active implementation of this model:** [portable-verse-links-plan.md](./portable-verse-links-plan.md)

---

## 1. Core insight

```text
Verses are global and immutable.
Passages, collections, and notes are optional user constructs (lenses).
Links address verses. Passages label and organize them.
```

A clickable scripture link must work even when the reader has no Passage, Collection, or Note rows. User constructs enrich the experience; they are never required for link validity.

---

## 2. Layered model

```text
┌─────────────────────────────────────────────────────────────┐
│  User constructs (optional, per-user / curated global)      │
│  Passage · Collection · Note · Memorization · Study plan    │
├─────────────────────────────────────────────────────────────┤
│  Focused range session (runtime UX)                         │
│  “Show verses V; offer related lenses if any exist”         │
├─────────────────────────────────────────────────────────────┤
│  Portable link (document storage)                           │
│  [v=123-125,127]                                            │
├─────────────────────────────────────────────────────────────┤
│  Scripture substrate (global, immutable)                    │
│  Verse IDs 1…31102 · KJV text · book/chapter coordinates  │
└─────────────────────────────────────────────────────────────┘
```

| Layer | What it is | Identity | Portable? |
|-------|------------|----------|-----------|
| **Verse** | Atomic KJV unit | Canonical global id `1…31102` | Yes — forever |
| **Link** | Pointer in note/body text | Verse-id ranges | Yes — across users/DBs/exports |
| **Passage** | Optional named/saved handle over a range | DB UUID; logical key `(owner, normalized range)` | Entity is not the link target |
| **Collection** | Ordered group of passages | DB id + membership rows | Groups; does not replace verse links |
| **Note** | User commentary | DB id; body may contain `[v=…]` | Body stays portable if links use verse ids |

---

## 3. Invariants (non-negotiable)

1. **Verses are the substrate**  
   Global continuous ids (Genesis 1:1 = 1 … Revelation 22:21 = 31102). Immutable text and coordinates.

2. **Links address verses, not row ids**  
   Note bodies, exports, and shared text must not depend on Passage UUIDs or collection BIGSERIALs as the primary target.

3. **Passage is optional enrichment**  
   - Optional title (“The New Birth”)  
   - Find-or-create by owner + normalized range  
   - Used for picker UX, collections, memorization, discovery  
   - **Not required** to open a focused reader for a link

4. **Collections group passages; they do not own scripture identity**  
   Membership is ordered passage ids. Repeats/order may remain data. Notes should not need a collection id to point at scripture.

5. **Users never handle opaque ids**  
   Pickers and search use reference text and titles. UUIDs stay in DB/API/URLs as implementation detail.

6. **Title is display-only**  
   Empty/null title ⇒ UI shows derived Bible reference from the range.

7. **Same range collapses per owner**  
   Find-or-create on normalized verse ranges (equivalent to today’s `naturalKey` intent). Do not mint duplicate passages for the same owner+range.

---

## 4. Portable link syntax

### 4.1 Canonical stored form

```text
[v=<verse-range-list>]
```

Examples:

```text
[v=26136]                 single verse
[v=26136-26138]           contiguous range (inclusive)
[v=26136-26138,26140]     multi-segment
[v=26136-26138,26140-26142]
```

### 4.2 Normalization rules (on write)

- Integer verse ids only  
- Each segment: `start` or `start-end` with `start <= end` (swap if reversed)  
- Reject ids outside `1…31102`  
- Sort segments; merge overlaps/adjacents where safe  
- Canonical serialization: no spaces, comma-separated segments, `-` for ranges  

Normalized form is the **equality key** for “same scripture pointer.”

### 4.3 Display rules (on read)

- Never show raw `[v=…]` as the primary user-visible label when a ref can be derived  
- Label = derived reference(s) from BibleService (e.g. `John 3:16–18`)  
- If a matching Passage exists with a title, prefer **title** in UI chrome; keep verse ids as the link identity  
- Optional future hint (not required for v1): `[v=26136-26138|The New Birth]` — title is display hint only; identity remains the ids  

### 4.4 Explicitly rejected as primary document links

| Form | Role |
|------|------|
| `[passage=<uuid>]` | ❌ Not portable — do not use as canonical note storage |
| `[pid=<collectionId>]` | ⚠️ Legacy collection link only; deprecate for new inserts |
| Human ref only in storage | OK for typing UX; prefer normalizing to `[v=…]` when inserting via picker |

Human references (`[John 3:16]`, scope-relative `[12]`) remain valid **input/UX** forms. Prefer persisting picker inserts as `[v=…]` so stored notes stay unambiguous.

---

## 5. Passage entity (refined role)

Passage remains first-class **product** object:

- `id` (UUID) — DB/API/membership  
- `user_id` null = curated global; non-null = user-owned  
- `from_verse_id` / `to_verse_id` and/or multi-segment representation consistent with `naturalKey`  
- `naturalKey` — human/parser-facing range encoding (keep in sync with verse ids)  
- `title` — optional for user and global  
- `sort_order` — globals on dashboard, etc.

**Logical identity for reuse:**  
`(user_id IS NOT DISTINCT FROM owner, normalized_verse_ranges)`

**Passage is a selector/lens in UX:**

- Save current range as passage (optional title)  
- Add passage to collection  
- Memorize passage  
- Pick existing passage to insert a **verse-id link** into a note (insert `[v=…]`, not UUID)

---

## 6. Focused reader behavior

### 6.1 Entry points

| Entry | Behavior |
|-------|----------|
| `[v=…]` click | Open focused reader on that immutable range |
| `/read/passage/{uuid}` | Resolve passage → same focused reader on its range |
| Collection reader | Ordered sequence of passage ranges (headers = title-or-ref) |

Focused reader **must** accept a pure verse-range session without a Passage row.

### 6.2 Primary chrome

- Title area: Passage title if resolved, else derived reference  
- Page-turn within the range only (rkj no-scroll rules apply)  
- Exit back to prior context  

### 6.3 Secondary discovery (optional panels — user constructs)

After the range is shown, the app **may** discover and link:

- Passages (user + global) whose normalized range equals or overlaps  
- Collections containing those passages  
- Notes that reference this range (`[v=…]` or legacy forms)  
- Memorization state for this range  

These are navigable affordances, not prerequisites.

Suggested framing in UI:

- “Saved as …”  
- “In collections …”  
- “Notes …”  
- “Memorize / continue review …”  
- “Save as passage…” if none exists  

### 6.4 Non-goals for focused reader v1

- Editing KJV text  
- Requiring login to open a `[v=…]` link (login only for user-construct discovery/save)  
- Blocking render until Passage resolution completes  

---

## 7. Notes integration

### 7.1 Insert UX

- “Insert scripture” / “Insert passage” picker  
- Search by reference or passage title (user + globals)  
- On select: insert **`[v=normalized…]`** at cursor  
- If user picked a titled passage, UI may show title in the picker; stored token remains verse ids  

### 7.2 Render UX

- Parse `[v=…]` → ranges → derived label (+ title if matching passage found for current user)  
- Click → focused reader for those verse ids  
- Legacy `[pid=N]`: keep opening collection until migrated; no new inserts  
- Keep `[John 3:16]` and scope-relative links for ad-hoc jumps  

### 7.3 Migration posture

1. New inserts: `[v=…]` only for scripture pointers  
2. Existing `[pid=collectionId]`: still resolve to collection reader  
3. Optional later: rewrite eligible legacy tokens where a collection member is a single contiguous passage → `[v=…]`  
4. Do **not** migrate notes toward `[passage=uuid]`  

---

## 8. API / URL guidance

| Concern | Preference |
|---------|------------|
| Note body | `[v=…]` |
| Collection membership | `passage_id` UUID FK |
| Passage CRUD | UUID in paths OK |
| Focused read by entity | `/read/passage/{uuid}` OK |
| Focused read by pure link | support range session, e.g. `/read/range?v=26136-26138,26140` or internal state from click handler |
| Hydrate verses | by verse-id ranges (Passage resolves to ranges then hydrates) |

UUIDs in URLs are acceptable as **session handles**. They are not the portable document standard.

---

## 9. Normalization & equality

Implement one shared server-side helper (name illustrative):

```text
parseVToken(raw) -> List<Range>
normalizeRanges(ranges) -> List<Range>   // sort, merge, validate
serializeVToken(ranges) -> "[v=...]"
rangesFromPassage(passage) -> List<Range>
naturalKeyFromRanges(ranges) -> String   // existing parser symmetry
rangesFromNaturalKey(naturalKey) -> List<Range>
```

**Equality:** two pointers are the same iff `normalizeRanges` outputs are equal.  
Passage find-or-create must use this equality, not brittle string match on unnormalized keys alone.

---

## 10. Downstream features (design for, don’t build all now)

| Feature | Uses verses | Uses Passage |
|---------|-------------|--------------|
| Note links | **Required** (`[v=…]`) | Optional title/discovery |
| Focused reader | **Required** | Optional |
| Collections | Via passage members’ ranges | **Required** membership |
| Memorization | Range under review | Existing FK OK |
| Study plans | Session = ranges + prompts | Passages as saved steps |
| Character/location studies | Verse sets | Promote sets to passages when named |

---

## 11. Implementation checklist for the current branch

Use this as acceptance criteria against `feature/note-editor-passage-selection` (and follow-ups):

- [ ] Note insert persists `[v=…]`, not passage UUID, not new `[pid=…]`  
- [ ] Click `[v=…]` opens focused reader with only those verses  
- [ ] Focused reader works with zero Passage rows  
- [ ] If a Passage matches range, show title and discovery affordances  
- [ ] Collection builder still creates/reuses Passage entities for membership  
- [ ] Passage find-or-create keyed by normalized ranges / naturalKey  
- [ ] Legacy `[pid=N]` still works (collection), documented as legacy  
- [ ] No user-facing UUID required in picker or note editing  
- [ ] Tests: normalize/merge, render label, click target, find-or-create reuse  

---

## 12. One-line summary

**Links address immutable verse ids; Passages are optional named lenses over those verses; collections and notes compose lenses and commentary — they never replace the substrate.**

---

## 13. Pointers

- Architecture index: [README.md](./README.md)
- Completed Passages slice: [first-class-passages.md](./first-class-passages.md)
- Active implementation plan: [portable-verse-links-plan.md](./portable-verse-links-plan.md)
- Agent constraints: `AGENTS.md` (no-scroll, two-column, desktop-first)
- Backlog consumers: `BACKLOG.md` (character/location studies, plans)
