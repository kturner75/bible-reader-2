-- Derived index of the scripture a sermon note references.
--
-- A note body stores portable [v=…] / [e=…] tokens — verse-id ranges — so the scripture
-- a note is *about* is already in the body, encoded and invisible to a text search. This
-- table resolves those tokens to (book_id, chapter) pairs at write time so the finder can
-- show scripture chips on a card and filter by book without parsing every body per query.
--
-- Strictly derived: dropping this table and re-running the startup backfill is lossless.
-- Granularity is book+chapter, not verse — the body remains the authority for exact ranges.
--
-- Deliberately no indexed_at marker. trg_sermon_notes_updated_at sets updated_at = now()
-- on every UPDATE of sermon_notes, so stamping a column there would bump the timestamp the
-- finder sorts and filters by on every note the backfill touched. "Indexed" is "has rows";
-- a note that genuinely references nothing is re-scanned once per boot, which is a regex
-- over a body that finds nothing.
CREATE TABLE sermon_note_refs (
    id      BIGSERIAL PRIMARY KEY,
    note_id UUID      NOT NULL REFERENCES sermon_notes (id) ON DELETE CASCADE,
    book_id INTEGER   NOT NULL,
    chapter INTEGER   NOT NULL,
    CONSTRAINT uq_sermon_note_refs UNIQUE (note_id, book_id, chapter),
    CONSTRAINT ck_sermon_note_refs_book_id CHECK (book_id BETWEEN 1 AND 66),
    CONSTRAINT ck_sermon_note_refs_chapter CHECK (chapter >= 1)
);

-- Card hydration walks note_id; the "Scripture: any book" filter walks book_id.
CREATE INDEX idx_sermon_note_refs_note_id ON sermon_note_refs (note_id);
CREATE INDEX idx_sermon_note_refs_book_id ON sermon_note_refs (book_id);
