package com.readthekjv.model.dto;

import java.util.List;
import java.util.UUID;

public record CollectionReadResponse(
    long id,
    String label,
    List<CollectionPassage> passages
) {
    public record CollectionPassage(
        UUID id,
        String title,
        String reference,
        String naturalKey,
        List<CollectionVerse> verses
    ) {}

    public record CollectionVerse(
        int id,
        String book,
        int bookId,
        int chapter,
        int verse,
        String text,
        String reference
    ) {
        public static CollectionVerse from(com.readthekjv.model.Verse v) {
            return new CollectionVerse(
                v.id(), v.book(), v.bookId(), v.chapter(), v.verse(), v.text(), v.reference()
            );
        }
    }
}
