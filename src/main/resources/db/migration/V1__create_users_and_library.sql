-- ============================================================
-- V1: Initial schema — users, tags, saved_verses, saved_verse_tags
-- ============================================================

-- --------------------------------------------------------
-- users
-- --------------------------------------------------------
CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(254) UNIQUE NOT NULL,
    password_hash VARCHAR(72)  NULL,        -- null for OAuth-only accounts
    google_sub    VARCHAR(255) UNIQUE NULL, -- null for password-only accounts
    display_name  VARCHAR(100) NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_users_login_method CHECK (
        password_hash IS NOT NULL OR google_sub IS NOT NULL
    )
);

-- Keep updated_at current automatically
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_users_updated_at
BEFORE UPDATE ON users
FOR EACH ROW EXECUTE PROCEDURE set_updated_at();

-- --------------------------------------------------------
-- tags
-- --------------------------------------------------------
CREATE TABLE tags (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name        VARCHAR(20) NOT NULL,
    color_index SMALLINT    NOT NULL CHECK (color_index BETWEEN 0 AND 9),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_tags_user_name UNIQUE (user_id, name)
);

CREATE INDEX idx_tags_user_id ON tags (user_id);

-- --------------------------------------------------------
-- saved_verses
-- --------------------------------------------------------
CREATE TABLE saved_verses (
    id       BIGSERIAL   PRIMARY KEY,
    user_id  BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    verse_id INTEGER     NOT NULL CHECK (verse_id BETWEEN 1 AND 31102),
    note     VARCHAR(500) NULL,
    saved_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_saved_verses_user_verse UNIQUE (user_id, verse_id)
);

CREATE INDEX idx_saved_verses_user_saved_at ON saved_verses (user_id, saved_at DESC);
CREATE INDEX idx_saved_verses_user_verse_id ON saved_verses (user_id, verse_id ASC);

-- --------------------------------------------------------
-- saved_verse_tags  (junction)
-- --------------------------------------------------------
CREATE TABLE saved_verse_tags (
    saved_verse_id BIGINT NOT NULL REFERENCES saved_verses (id) ON DELETE CASCADE,
    tag_id         UUID   NOT NULL REFERENCES tags (id)         ON DELETE CASCADE,
    PRIMARY KEY (saved_verse_id, tag_id)
);

CREATE INDEX idx_svt_tag_id ON saved_verse_tags (tag_id);
