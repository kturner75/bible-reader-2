package com.readthekjv.model.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "passages")
public class Passage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    // nullable = true: global passages (user_id IS NULL) have no owner
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "user_id", nullable = true)
    private User user;

    @Column(name = "from_verse_id", nullable = false)
    private int fromVerseId;

    @Column(name = "to_verse_id", nullable = false)
    private int toVerseId;

    // V6 widened the DB column to VARCHAR(500); match here
    @Column(name = "natural_key", nullable = false, length = 500)
    private String naturalKey;

    // Non-null only for global passages (user IS NULL)
    @Column(name = "title", length = 100)
    private String title;

    // Display order for global passages on the dashboard
    @Column(name = "sort_order")
    private Integer sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public int getFromVerseId() { return fromVerseId; }
    public void setFromVerseId(int fromVerseId) { this.fromVerseId = fromVerseId; }
    public int getToVerseId() { return toVerseId; }
    public void setToVerseId(int toVerseId) { this.toVerseId = toVerseId; }
    public String getNaturalKey() { return naturalKey; }
    public void setNaturalKey(String naturalKey) { this.naturalKey = naturalKey; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
