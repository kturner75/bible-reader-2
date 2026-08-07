-- Reading Rhythms: user-defined, recurring, self-paced reading lanes.
--
-- Distinct from reading_plans (V10), which is finite and day-numbered. A rhythm has
-- no end date and no required chapters per session: each lane is an ordered list of
-- books plus a cursor marking the last chapter finished. The reader picks up where
-- they left off, whenever they get to it.
--
-- A lane's optional day_of_week is a *surfacing hint* only — the dashboard leads with
-- today's lane, but any lane may be read and advanced on any day. Nothing here or in
-- the service enforces the weekday.

CREATE TABLE reading_rhythms (
    id         BIGSERIAL    PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title      VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_reading_rhythms_user_title UNIQUE (user_id, title)
);

CREATE INDEX idx_reading_rhythms_user_id ON reading_rhythms (user_id);

CREATE TRIGGER trg_reading_rhythms_updated_at
BEFORE UPDATE ON reading_rhythms
FOR EACH ROW EXECUTE PROCEDURE set_updated_at();

-- Ordered lanes within a rhythm.
--
-- Cursor = (cursor_book_id, cursor_chapter) — "the last chapter I finished".
-- Every book before that one in the lane list is complete; every book after is
-- untouched. Per-book "chapters read" is therefore derived, never stored.
-- cursor_book_id NULL means the lane has not been started.
--
-- The cursor stores a *book id*, not an index into lane_books, so reordering or
-- inserting books leaves progress meaningful. If the cursor book is removed from
-- the lane entirely, the service resets the lane to not-started.
CREATE TABLE reading_rhythm_lanes (
    id             BIGSERIAL   PRIMARY KEY,
    rhythm_id      BIGINT      NOT NULL REFERENCES reading_rhythms (id) ON DELETE CASCADE,
    position       INTEGER     NOT NULL,
    name           VARCHAR(60) NOT NULL,
    -- ISO-8601 day numbering: 1 = Monday … 7 = Sunday. NULL = no scheduled day.
    day_of_week    SMALLINT    NULL CHECK (day_of_week BETWEEN 1 AND 7),
    cursor_book_id INTEGER     NULL CHECK (cursor_book_id BETWEEN 1 AND 66),
    cursor_chapter INTEGER     NOT NULL DEFAULT 0 CHECK (cursor_chapter >= 0),
    CONSTRAINT uq_reading_rhythm_lane_position UNIQUE (rhythm_id, position)
);

CREATE INDEX idx_reading_rhythm_lanes_rhythm_id ON reading_rhythm_lanes (rhythm_id);

-- Ordered book membership of a lane. No surrogate PK: a row has no identity
-- beyond (lane, slot). A book may legitimately repeat within a lane.
CREATE TABLE reading_rhythm_lane_books (
    lane_id  BIGINT  NOT NULL REFERENCES reading_rhythm_lanes (id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    book_id  INTEGER NOT NULL CHECK (book_id BETWEEN 1 AND 66),
    PRIMARY KEY (lane_id, position)
);

-- Append-only log of "I read through here" events. The lane cursor is a high-water
-- mark with no history; this table is what the activity heatmap counts.
CREATE TABLE reading_rhythm_progress (
    id              BIGSERIAL   PRIMARY KEY,
    user_id         BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    lane_id         BIGINT      NOT NULL REFERENCES reading_rhythm_lanes (id) ON DELETE CASCADE,
    book_id         INTEGER     NOT NULL CHECK (book_id BETWEEN 1 AND 66),
    through_chapter INTEGER     NOT NULL CHECK (through_chapter >= 1),
    -- Chapters gained by this event. 0 when the user corrects the cursor backward.
    chapters_delta  INTEGER     NOT NULL DEFAULT 0 CHECK (chapters_delta >= 0),
    completed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_reading_rhythm_progress_user_date
    ON reading_rhythm_progress (user_id, completed_at);
CREATE INDEX idx_reading_rhythm_progress_lane
    ON reading_rhythm_progress (lane_id);
