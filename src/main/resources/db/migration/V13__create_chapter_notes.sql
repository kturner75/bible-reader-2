-- One free-form study note per (user, book, chapter).
CREATE TABLE chapter_notes (
    id         UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    BIGINT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    book_id    INTEGER       NOT NULL,
    chapter    INTEGER       NOT NULL,
    note       VARCHAR(5000) NOT NULL,
    created_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_chapter_notes UNIQUE (user_id, book_id, chapter)
);

CREATE INDEX idx_chapter_notes_user_id ON chapter_notes (user_id);

CREATE TRIGGER trg_chapter_notes_updated_at
BEFORE UPDATE ON chapter_notes
FOR EACH ROW EXECUTE PROCEDURE set_updated_at();
