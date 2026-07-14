package com.readthekjv.model.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "chapter_notes")
public class ChapterNote {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "book_id", nullable = false)
    private int bookId;

    @Column(nullable = false)
    private int chapter;

    @Column(nullable = false, length = 5000)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public int getBookId() { return bookId; }
    public void setBookId(int bookId) { this.bookId = bookId; }
    public int getChapter() { return chapter; }
    public void setChapter(int chapter) { this.chapter = chapter; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
