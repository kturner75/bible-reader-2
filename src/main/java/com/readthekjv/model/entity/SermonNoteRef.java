package com.readthekjv.model.entity;

import jakarta.persistence.*;

import java.util.UUID;

/**
 * One (book, chapter) the owning sermon note references, resolved from the
 * portable {@code [v=…]} / {@code [e=…]} tokens in its body at write time.
 *
 * Derived data — see V22 and {@code docs/architecture/notes-finder-search.md}.
 * Never written by hand: {@code SermonNoteService} reindexes delete-then-insert
 * inside the same transaction as the note save.
 */
@Entity
@Table(name = "sermon_note_refs")
public class SermonNoteRef {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "note_id", nullable = false)
    private SermonNote note;

    @Column(name = "book_id", nullable = false)
    private int bookId;

    @Column(nullable = false)
    private int chapter;

    protected SermonNoteRef() {}

    public SermonNoteRef(SermonNote note, int bookId, int chapter) {
        this.note = note;
        this.bookId = bookId;
        this.chapter = chapter;
    }

    public Long getId() { return id; }
    public SermonNote getNote() { return note; }
    public int getBookId() { return bookId; }
    public int getChapter() { return chapter; }
}
