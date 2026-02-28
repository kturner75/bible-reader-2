package com.readthekjv.model.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "saved_verses")
public class SavedVerse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "verse_id", nullable = false)
    private int verseId;

    @Column(length = 500)
    private String note;

    @Column(name = "saved_at", nullable = false, updatable = false)
    private OffsetDateTime savedAt;

    @OneToMany(mappedBy = "savedVerse", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<SavedVerseTag> savedVerseTags = new ArrayList<>();

    @PrePersist
    void onCreate() {
        savedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public int getVerseId() { return verseId; }
    public void setVerseId(int verseId) { this.verseId = verseId; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public OffsetDateTime getSavedAt() { return savedAt; }
    public List<SavedVerseTag> getSavedVerseTags() { return savedVerseTags; }
}
