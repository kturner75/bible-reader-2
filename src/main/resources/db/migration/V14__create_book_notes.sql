-- One free-form study note / outline per (user, book).
CREATE TABLE book_notes (
    id         UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    BIGINT         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    book_id    INTEGER        NOT NULL,
    note       VARCHAR(10000) NOT NULL,
    created_at TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_book_notes UNIQUE (user_id, book_id)
);

CREATE INDEX idx_book_notes_user_id ON book_notes (user_id);

CREATE TRIGGER trg_book_notes_updated_at
BEFORE UPDATE ON book_notes
FOR EACH ROW EXECUTE PROCEDURE set_updated_at();
