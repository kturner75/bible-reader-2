package com.readthekjv.model.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Persists the AI-selected verse of the day.
 * One row per calendar day (UTC). Generated at midnight by VerseOfDayService.
 */
@Entity
@Table(name = "verse_of_day")
public class VerseOfDay {

    /** Calendar date (UTC) — primary key; set explicitly, no generation strategy. */
    @Id
    @Column(nullable = false)
    private LocalDate date;

    /** Global verse ID (1–31,102). */
    @Column(name = "verse_id", nullable = false)
    private int verseId;

    /** 2–3 sentence AI-written blurb connecting the verse to the day's theme. */
    @Column(name = "ai_blurb")
    private String aiBlurb;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
    }

    // ── Constructors ──────────────────────────────────────────────────────────

    protected VerseOfDay() {}

    public VerseOfDay(LocalDate date, int verseId, String aiBlurb) {
        this.date    = date;
        this.verseId = verseId;
        this.aiBlurb = aiBlurb;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public LocalDate getDate()         { return date; }
    public int       getVerseId()      { return verseId; }
    public String    getAiBlurb()      { return aiBlurb; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
