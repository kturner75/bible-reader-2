CREATE TABLE reading_plans (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    slug       VARCHAR(60) NOT NULL UNIQUE,
    title      VARCHAR(100) NOT NULL,
    total_days INTEGER      NOT NULL CHECK (total_days > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reading_plan_days (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id       UUID        NOT NULL REFERENCES reading_plans(id) ON DELETE CASCADE,
    day_number    INTEGER     NOT NULL CHECK (day_number >= 1),
    label         VARCHAR(200) NOT NULL,
    from_verse_id INTEGER     NOT NULL CHECK (from_verse_id BETWEEN 1 AND 31102),
    to_verse_id   INTEGER     NOT NULL CHECK (to_verse_id   BETWEEN 1 AND 31102
                                          AND to_verse_id  >= from_verse_id),
    CONSTRAINT uq_plan_day UNIQUE (plan_id, day_number)
);
CREATE INDEX idx_plan_days_plan_id ON reading_plan_days(plan_id);

CREATE TABLE user_plan_enrollments (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    plan_id     UUID        NOT NULL REFERENCES reading_plans(id) ON DELETE CASCADE,
    current_day INTEGER     NOT NULL DEFAULT 1 CHECK (current_day >= 1),
    enrolled_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_enrollment UNIQUE (user_id, plan_id)
);
CREATE INDEX idx_enrollments_user_id ON user_plan_enrollments(user_id);
