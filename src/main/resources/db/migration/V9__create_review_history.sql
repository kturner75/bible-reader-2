CREATE TABLE review_history (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    entry_id      UUID        NOT NULL REFERENCES memorization_entries(id) ON DELETE CASCADE,
    user_id       BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    quality       SMALLINT    NOT NULL,   -- 0=Again 3=Hard 4=Good 5=Easy
    mastery_after SMALLINT    NOT NULL,   -- mastery_level after this review
    reviewed_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_review_history_entry_id ON review_history(entry_id);
CREATE INDEX idx_review_history_user_id  ON review_history(user_id);
