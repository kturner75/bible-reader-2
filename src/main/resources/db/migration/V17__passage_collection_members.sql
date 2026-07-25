-- Elevate collections to ordered lists of first-class passages.
-- passage title is already nullable for all rows (V8); user passages may now set it too.
-- Members reference passages(id). verse lists are migrated then dropped by V17 Java companion
-- if present; this SQL creates the new table and is safe if the Java migration also runs.

CREATE TABLE IF NOT EXISTS passage_collection_members (
    collection_id BIGINT NOT NULL REFERENCES passage_collections (id) ON DELETE CASCADE,
    position      INTEGER NOT NULL,
    passage_id    UUID   NOT NULL REFERENCES passages (id) ON DELETE RESTRICT,
    PRIMARY KEY (collection_id, position)
);

CREATE INDEX IF NOT EXISTS idx_passage_collection_members_passage
    ON passage_collection_members (passage_id);

COMMENT ON TABLE passage_collection_members IS
    'Ordered membership of passages in a collection. Same passage may appear in many collections; repeats within one collection are allowed (no unique on passage_id).';
