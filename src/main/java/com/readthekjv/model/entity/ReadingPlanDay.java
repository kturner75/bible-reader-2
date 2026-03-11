package com.readthekjv.model.entity;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "reading_plan_days")
public class ReadingPlanDay {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private ReadingPlan plan;

    @Column(name = "day_number", nullable = false)
    private int dayNumber;

    @Column(nullable = false, length = 200)
    private String label;

    @Column(name = "from_verse_id", nullable = false)
    private int fromVerseId;

    @Column(name = "to_verse_id", nullable = false)
    private int toVerseId;

    public UUID getId()               { return id; }
    public ReadingPlan getPlan()      { return plan; }
    public void setPlan(ReadingPlan p){ this.plan = p; }
    public int getDayNumber()         { return dayNumber; }
    public void setDayNumber(int d)   { this.dayNumber = d; }
    public String getLabel()          { return label; }
    public void setLabel(String l)    { this.label = l; }
    public int getFromVerseId()       { return fromVerseId; }
    public void setFromVerseId(int v) { this.fromVerseId = v; }
    public int getToVerseId()         { return toVerseId; }
    public void setToVerseId(int v)   { this.toVerseId = v; }
}
