package com.readthekjv.model.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "reading_plans")
public class ReadingPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(nullable = false, unique = true, length = 60)
    private String slug;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(name = "total_days", nullable = false)
    private int totalDays;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() { createdAt = OffsetDateTime.now(); }

    public UUID getId()             { return id; }
    public String getSlug()         { return slug; }
    public void setSlug(String slug){ this.slug = slug; }
    public String getTitle()        { return title; }
    public void setTitle(String t)  { this.title = t; }
    public int getTotalDays()       { return totalDays; }
    public void setTotalDays(int d) { this.totalDays = d; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
