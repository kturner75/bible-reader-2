package com.readthekjv.model.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "reading_plan_completions")
public class ReadingPlanCompletion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private ReadingPlan plan;

    @Column(name = "day_number", nullable = false)
    private int dayNumber;

    @Column(name = "completed_at", nullable = false, updatable = false)
    private OffsetDateTime completedAt;

    @PrePersist
    void onCreate() { completedAt = OffsetDateTime.now(); }

    public UUID getId()                   { return id; }
    public Long getUserId()               { return userId; }
    public void setUserId(Long u)         { this.userId = u; }
    public ReadingPlan getPlan()          { return plan; }
    public void setPlan(ReadingPlan p)    { this.plan = p; }
    public int getDayNumber()             { return dayNumber; }
    public void setDayNumber(int d)       { this.dayNumber = d; }
    public OffsetDateTime getCompletedAt(){ return completedAt; }
}
