-- Passage Collections: user-owned ordered lists of arbitrary verses
-- (may span books, may be out of canonical order, may repeat a verse).
-- BIGSERIAL id (not UUID): [pid=123] note links need a compact typeable id.

CREATE TABLE passage_collections (
    id         BIGSERIAL    PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    label      VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_passage_collections_user_label UNIQUE (user_id, label)
);

CREATE INDEX idx_passage_collections_user_id ON passage_collections (user_id);

CREATE TRIGGER trg_passage_collections_updated_at
BEFORE UPDATE ON passage_collections
FOR EACH ROW EXECUTE PROCEDURE set_updated_at();

-- Ordered membership. No surrogate PK: rows have no identity beyond
-- (collection, slot). verse_id deliberately NOT unique per collection.
CREATE TABLE passage_collection_verses (
    collection_id BIGINT  NOT NULL REFERENCES passage_collections (id) ON DELETE CASCADE,
    position      INTEGER NOT NULL,
    verse_id      INTEGER NOT NULL CHECK (verse_id BETWEEN 1 AND 31102),
    PRIMARY KEY (collection_id, position)
);
