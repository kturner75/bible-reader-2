package com.readthekjv.model.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "saved_verse_tags")
public class SavedVerseTag {

    @EmbeddedId
    private SavedVerseTagId id;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("savedVerseId")
    @JoinColumn(name = "saved_verse_id")
    private SavedVerse savedVerse;

    @ManyToOne(fetch = FetchType.LAZY)
    @MapsId("tagId")
    @JoinColumn(name = "tag_id")
    private Tag tag;

    public SavedVerseTag() {}

    public SavedVerseTag(SavedVerse savedVerse, Tag tag) {
        this.savedVerse = savedVerse;
        this.tag = tag;
        this.id = new SavedVerseTagId(savedVerse.getId(), tag.getId());
    }

    public SavedVerseTagId getId() { return id; }
    public SavedVerse getSavedVerse() { return savedVerse; }
    public Tag getTag() { return tag; }
}
