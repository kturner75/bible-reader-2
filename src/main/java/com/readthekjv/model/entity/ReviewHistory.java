package com.readthekjv.model.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "review_history")
public class ReviewHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entry_id", nullable = false)
    private MemorizationEntry entry;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private short quality;

    @Column(name = "mastery_after", nullable = false)
    private short masteryAfter;

    @Column(name = "reviewed_at", nullable = false, updatable = false)
    private OffsetDateTime reviewedAt;

    @PrePersist
    void onCreate() {
        reviewedAt = OffsetDateTime.now();
    }

    public UUID getId() { return id; }
    public MemorizationEntry getEntry() { return entry; }
    public void setEntry(MemorizationEntry entry) { this.entry = entry; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public short getQuality() { return quality; }
    public void setQuality(short quality) { this.quality = quality; }
    public short getMasteryAfter() { return masteryAfter; }
    public void setMasteryAfter(short masteryAfter) { this.masteryAfter = masteryAfter; }
    public OffsetDateTime getReviewedAt() { return reviewedAt; }
}
