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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "from_verse_id", nullable = false)
    private int fromVerseId;

    @Column(name = "to_verse_id", nullable = false)
    private int toVerseId;

    @Column(name = "natural_key", nullable = false, length = 100)
    private String naturalKey;

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
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
