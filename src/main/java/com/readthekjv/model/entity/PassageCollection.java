package com.readthekjv.model.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "passage_collections")
public class PassageCollection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 100)
    private String label;

    // Verse ids in user-defined order — a collection may sequence verses out of
    // canonical Bible order and may repeat a verse, so order is data here.
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "passage_collection_verses",
                     joinColumns = @JoinColumn(name = "collection_id"))
    @OrderColumn(name = "position")
    @Column(name = "verse_id", nullable = false)
    private List<Integer> verseIds = new ArrayList<>();

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

    /**
     * Dirty the parent row explicitly. Replacing verseIds only touches the
     * @ElementCollection table, so a content-only edit (same label) would
     * otherwise skip @PreUpdate and the DB updated_at trigger, leaving a
     * stale updatedAt in responses and in updatedAt-ordered listings.
     */
    public void touch() {
        updatedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public List<Integer> getVerseIds() { return verseIds; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
