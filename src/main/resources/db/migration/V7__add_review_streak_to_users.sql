-- ============================================================
-- V7: Review streak tracking on the users table
-- ============================================================

ALTER TABLE users ADD COLUMN current_streak  INT NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN longest_streak  INT NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN last_review_date DATE;
