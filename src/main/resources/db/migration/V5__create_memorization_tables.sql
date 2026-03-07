-- ============================================================
-- V5: Scripture Memorization — passages catalog + user entries
-- ============================================================

-- --------------------------------------------------------
-- passages  (per-user scripture ranges)
-- --------------------------------------------------------
CREATE TABLE passages (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    from_verse_id INTEGER      NOT NULL CHECK (from_verse_id BETWEEN 1 AND 31102),
    to_verse_id   INTEGER      NOT NULL CHECK (to_verse_id   BETWEEN 1 AND 31102
                                           AND to_verse_id  >= from_verse_id),
    natural_key   VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_passages_user_key UNIQUE (user_id, natural_key)
);

CREATE INDEX idx_passages_user_id ON passages(user_id);

-- --------------------------------------------------------
-- memorization_entries  (SM-2 scheduling per user)
-- --------------------------------------------------------
CREATE TABLE memorization_entries (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        BIGINT       NOT NULL REFERENCES users(id)    ON DELETE CASCADE,
    passage_id     UUID         NOT NULL REFERENCES passages(id) ON DELETE CASCADE,
    mastery_level  SMALLINT     NOT NULL DEFAULT 0
                                CHECK (mastery_level BETWEEN 0 AND 5),
    ease_factor    NUMERIC(4,2) NOT NULL DEFAULT 2.50
                                CHECK (ease_factor >= 1.30),
    interval_days  INTEGER      NOT NULL DEFAULT 1,
    next_review_at DATE,
    added_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_entries_user_passage UNIQUE (user_id, passage_id)
);

CREATE INDEX idx_mem_entries_user_id     ON memorization_entries(user_id);
CREATE INDEX idx_mem_entries_user_review ON memorization_entries(user_id, next_review_at);
