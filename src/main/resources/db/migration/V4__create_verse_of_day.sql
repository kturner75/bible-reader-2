-- Verse of the Day: one AI-selected verse per calendar day
CREATE TABLE IF NOT EXISTS verse_of_day (
    date       DATE        PRIMARY KEY,
    verse_id   INTEGER     NOT NULL CHECK (verse_id BETWEEN 1 AND 31102),
    ai_blurb   TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
