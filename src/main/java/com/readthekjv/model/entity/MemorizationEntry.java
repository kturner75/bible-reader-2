package com.readthekjv.model.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "memorization_entries")
public class MemorizationEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "passage_id", nullable = false)
    private Passage passage;

    @Column(name = "mastery_level", nullable = false)
    private short masteryLevel = 0;

    @Column(name = "ease_factor", nullable = false, precision = 4, scale = 2)
    private BigDecimal easeFactor = new BigDecimal("2.50");

    @Column(name = "interval_days", nullable = false)
    private int intervalDays = 1;

    @Column(name = "next_review_at")
    private LocalDate nextReviewAt;

    @Column(name = "added_at", nullable = false, updatable = false)
    private OffsetDateTime addedAt;

    @PrePersist
    void onCreate() {
        addedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public Passage getPassage() { return passage; }
    public void setPassage(Passage passage) { this.passage = passage; }
    public short getMasteryLevel() { return masteryLevel; }
    public void setMasteryLevel(short masteryLevel) { this.masteryLevel = masteryLevel; }
    public BigDecimal getEaseFactor() { return easeFactor; }
    public void setEaseFactor(BigDecimal easeFactor) { this.easeFactor = easeFactor; }
    public int getIntervalDays() { return intervalDays; }
    public void setIntervalDays(int intervalDays) { this.intervalDays = intervalDays; }
    public LocalDate getNextReviewAt() { return nextReviewAt; }
    public void setNextReviewAt(LocalDate nextReviewAt) { this.nextReviewAt = nextReviewAt; }
    public OffsetDateTime getAddedAt() { return addedAt; }
}
