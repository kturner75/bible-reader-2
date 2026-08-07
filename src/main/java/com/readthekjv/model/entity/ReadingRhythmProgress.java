package com.readthekjv.model.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

/**
 * Append-only record of a "read through here" event on a rhythm lane.
 *
 * The lane cursor is only a high-water mark; this log is what the activity
 * heatmap counts. A backward correction is still logged, with delta 0.
 */
@Entity
@Table(name = "reading_rhythm_progress")
public class ReadingRhythmProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lane_id", nullable = false)
    private ReadingRhythmLane lane;

    @Column(name = "book_id", nullable = false)
    private int bookId;

    @Column(name = "through_chapter", nullable = false)
    private int throughChapter;

    @Column(name = "chapters_delta", nullable = false)
    private int chaptersDelta;

    @Column(name = "completed_at", nullable = false, updatable = false)
    private OffsetDateTime completedAt;

    @PrePersist
    void onCreate() {
        if (completedAt == null) completedAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public ReadingRhythmLane getLane() { return lane; }
    public void setLane(ReadingRhythmLane lane) { this.lane = lane; }
    public int getBookId() { return bookId; }
    public void setBookId(int bookId) { this.bookId = bookId; }
    public int getThroughChapter() { return throughChapter; }
    public void setThroughChapter(int throughChapter) { this.throughChapter = throughChapter; }
    public int getChaptersDelta() { return chaptersDelta; }
    public void setChaptersDelta(int chaptersDelta) { this.chaptersDelta = chaptersDelta; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
}
