# First-class Passages (completed)

**Status:** COMPLETED (2026-07-25)  
**Original Cursor plan:** `~/.cursor/plans/first-class_passages_4b8c52a8.plan.md` (archived pointer)  
**Superseded for note links by:** [passage-and-verse-link-model.md](./passage-and-verse-link-model.md) + [portable-verse-links-plan.md](./portable-verse-links-plan.md)

## What this slice delivered

Elevate **Passage** as a shared product object (optional title + `naturalKey`), reshape **collections** to ordered passage membership, and add a focused passage reader plus insert UX.

| Area | Delivered |
|------|-----------|
| Schema | User passage titles allowed; `passage_collection_members`; migrate flat `passage_collection_verses` → passages (V17 / V17.1) |
| APIs | `/api/passages` catalog + hydrate; collections CRUD uses `passageIds` |
| Reader | `/read/passage/{uuid}`; collection reader uses real passage membership |
| Builder | Adding verses create/reuses Passage rows; optional title on queue items |
| Notes (interim) | Insert passage picker; token `[passage=<uuid>]` — **not canonical going forward** |

## Important correction

The original plan made Passage (or collection) the note link target. The accepted architecture reverses that:

> **Links address immutable verse ids; Passages are optional named lenses.**

Do **not** extend `[passage=uuid]` as the document standard. Implement portable `[v=…]` per [portable-verse-links-plan.md](./portable-verse-links-plan.md). Legacy `[pid=N]` (collection) and any existing `[passage=uuid]` remain compat-only.

## Still valid from this work

- Passage entity + find-or-create by owner + range/`naturalKey`
- Collections as ordered `passage_id` membership
- Focused reader chrome patterns (page-turn, no-scroll)
- Picker UX (users never type opaque ids)
- Memorization continuing to FK `passages`

## One-line summary

**Passages and collections shipped; note link portability is the next slice.**
