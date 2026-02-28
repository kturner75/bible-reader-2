package com.readthekjv.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class SavedVerseTagId implements Serializable {

    @Column(name = "saved_verse_id")
    private Long savedVerseId;

    @Column(name = "tag_id", columnDefinition = "uuid")
    private UUID tagId;

    public SavedVerseTagId() {}

    public SavedVerseTagId(Long savedVerseId, UUID tagId) {
        this.savedVerseId = savedVerseId;
        this.tagId = tagId;
    }

    public Long getSavedVerseId() { return savedVerseId; }
    public UUID getTagId() { return tagId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SavedVerseTagId that)) return false;
        return Objects.equals(savedVerseId, that.savedVerseId) && Objects.equals(tagId, that.tagId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(savedVerseId, tagId);
    }
}
