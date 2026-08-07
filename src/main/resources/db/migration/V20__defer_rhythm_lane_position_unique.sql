-- Reordering lanes swaps their positions (0→1, 1→0). Hibernate issues one UPDATE
-- per entity, so the first statement transiently duplicates a position that the
-- second is about to vacate. With the immediate constraint from V19 that first
-- UPDATE fails outright:
--
--   ERROR: duplicate key value violates unique constraint
--          "uq_reading_rhythm_lane_position"
--   Detail: Key (rhythm_id, "position")=(7, 1) already exists.
--
-- Deferring the check to commit lets the intermediate state exist inside the
-- transaction while still guaranteeing uniqueness once it lands. Preferred over
-- dropping the constraint (loses the guarantee) or renumbering through a spare
-- offset (extra writes, and still wrong under a concurrent reorder).
--
-- A constraint's deferrability cannot be altered in place, so drop and re-add.

ALTER TABLE reading_rhythm_lanes
    DROP CONSTRAINT uq_reading_rhythm_lane_position;

ALTER TABLE reading_rhythm_lanes
    ADD CONSTRAINT uq_reading_rhythm_lane_position
    UNIQUE (rhythm_id, "position")
    DEFERRABLE INITIALLY DEFERRED;

-- V19's table comment claimed a book may repeat within a lane, copied from the
-- passage-collection model where repeats are meaningful. They are not here: the
-- cursor stores a book id, so a second occurrence is indistinguishable from the
-- first and progress cannot advance past it. The service now rejects duplicates.
COMMENT ON TABLE reading_rhythm_lane_books IS
    'Ordered book membership of a lane. A book appears at most once per lane — the '
    'lane cursor is keyed on book id, so repeats would be ambiguous.';
