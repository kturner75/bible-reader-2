package com.readthekjv.model.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A user-defined recurring reading rhythm: a named set of ordered lanes, each
 * progressing through its own book list at the reader's own pace.
 *
 * Unlike {@link ReadingPlan}, a rhythm has no total_days and no deadline.
 */
@Entity
@Table(name = "reading_rhythms")
public class ReadingRhythm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 100)
    private String title;

    // Lanes are owned by the rhythm — orphanRemoval so an edit that drops a lane
    // deletes its row (and, by FK cascade, its books and progress log).
    // @OrderBy, not @OrderColumn: ReadingRhythmLane.position is written by the
    // service so the INSERT satisfies the column's NOT NULL constraint.
    @OneToMany(mappedBy = "rhythm", cascade = CascadeType.ALL, orphanRemoval = true,
               fetch = FetchType.LAZY)
    @OrderBy("position ASC")
    private List<ReadingRhythmLane> lanes = new ArrayList<>();

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
     * Dirty the parent row explicitly. Same reason as PassageCollection.touch():
     * editing only the lane collection would otherwise skip @PreUpdate.
     */
    public void touch() {
        updatedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public List<ReadingRhythmLane> getLanes() { return lanes; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
