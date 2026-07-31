# Architecture docs (scripture model)

**For agents and implementers:** durable specs live here. Cursor plan files under `~/.cursor/plans/` are session pointers only — do not treat them as competing sources of truth.

| Document | Status | Purpose |
|----------|--------|---------|
| [passage-and-verse-link-model.md](./passage-and-verse-link-model.md) | **Accepted** | Invariants: verses are substrate; links are `[v=…]`; Passages are optional lenses |
| [portable-verse-links-plan.md](./portable-verse-links-plan.md) | **Implemented (core + scope multi-verse)** | `[v=…]` storage, range reader, save normalize, scope-relative `[1-11]` / lists |
| [first-class-passages.md](./first-class-passages.md) | **Completed** | What shipped for Passage entities, collections, focused `/read/passage/{uuid}` |

## Rule of thumb

1. Need the **model**? → `passage-and-verse-link-model.md`
2. Need **implementation status / checklist**? → `portable-verse-links-plan.md`
3. Need **what already landed** for Passages/collections? → `first-class-passages.md`

**Do not** implement note links as `[passage=<uuid>]` as the canonical form. That was an interim choice on the Passages slice; the accepted model uses portable `[v=…]` verse-id ranges (human label on render).

**Follow-on (backlog):** Insert Scripture search (PR #40) and header `/` Matching Verses | Matching Passages tabs (PR #41) are done. Scope-relative multi-verse note input is done (`ScopeRelativeLinkParser`). Remaining typed-link gap: absolute ranges like `[John 3:16-18]`. Future discovery lanes can join the search tab strip. See `BACKLOG.md`.
