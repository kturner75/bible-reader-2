-- Records each calendar day a user marks a plan day complete.
-- Used to compute reading-plan streaks.
-- The unique constraint prevents double-counting if completeDay() is called twice.
CREATE TABLE reading_plan_completions (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    plan_id      UUID        NOT NULL REFERENCES reading_plans(id) ON DELETE CASCADE,
    day_number   INTEGER     NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_plan_completion UNIQUE (user_id, plan_id, day_number)
);
CREATE INDEX idx_plan_completions_user_plan ON reading_plan_completions(user_id, plan_id);
