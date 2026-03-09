-- ============================================================
-- V6: Passage infrastructure — widen natural_key, allow global passages
-- ============================================================

-- Widen natural_key to support multi-segment passages ("V1:V2,V3:V4,...")
ALTER TABLE passages ALTER COLUMN natural_key TYPE VARCHAR(500);

-- Allow user_id to be null for future global (shared) passages
ALTER TABLE passages ALTER COLUMN user_id DROP NOT NULL;
