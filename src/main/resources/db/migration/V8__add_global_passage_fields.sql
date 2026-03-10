-- V8: Add title and sort_order columns to passages table for global/shared passages.
-- Global passages have user_id IS NULL (prepared by V6).
-- A partial unique index prevents duplicate global passages per natural_key.

ALTER TABLE passages
    ADD COLUMN title      VARCHAR(100),
    ADD COLUMN sort_order INTEGER;

-- PostgreSQL treats NULL != NULL in standard UNIQUE constraints, so the existing
-- uq_passages_user_key (user_id, natural_key) does NOT prevent duplicate global rows.
-- This partial index enforces uniqueness only for global rows (user_id IS NULL).
CREATE UNIQUE INDEX uq_global_passage_natural_key
    ON passages (natural_key)
    WHERE user_id IS NULL;
