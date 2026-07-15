-- Sermon/lesson notes: free-standing, full-CRUD notes (title + body).
-- Unlike chapter/book notes there is no unique scope key — a user can have
-- any number of sermon notes, so this is plain CRUD, not upsert-by-scope.
CREATE TABLE sermon_notes (
    id         UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    BIGINT         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title      VARCHAR(200)   NOT NULL,
    note       VARCHAR(20000) NOT NULL,
    created_at TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_sermon_notes_user_id ON sermon_notes (user_id);

CREATE TRIGGER trg_sermon_notes_updated_at
BEFORE UPDATE ON sermon_notes
FOR EACH ROW EXECUTE PROCEDURE set_updated_at();
