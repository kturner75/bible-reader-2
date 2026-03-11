package com.readthekjv.model.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "user_plan_enrollments")
public class UserPlanEnrollment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private ReadingPlan plan;

    @Column(name = "current_day", nullable = false)
    private int currentDay = 1;

    @Column(name = "enrolled_at", nullable = false, updatable = false)
    private OffsetDateTime enrolledAt;

    @PrePersist
    void onCreate() { enrolledAt = OffsetDateTime.now(); }

    public UUID getId()                  { return id; }
    public User getUser()                { return user; }
    public void setUser(User u)          { this.user = u; }
    public ReadingPlan getPlan()         { return plan; }
    public void setPlan(ReadingPlan p)   { this.plan = p; }
    public int getCurrentDay()           { return currentDay; }
    public void setCurrentDay(int d)     { this.currentDay = d; }
    public OffsetDateTime getEnrolledAt(){ return enrolledAt; }
}
