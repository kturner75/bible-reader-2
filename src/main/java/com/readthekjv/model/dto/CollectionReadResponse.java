package com.readthekjv.model.dto;

import com.readthekjv.model.Verse;

import java.util.List;

public record CollectionReadResponse(
    long id,
    String label,
    List<CollectionVerse> verses
) {
    public record CollectionVerse(
        int id,
        String book,
        int bookId,
        int chapter,
        int verse,
        String text,
        String reference
    ) {
        public static CollectionVerse from(Verse v) {
            return new CollectionVerse(
                v.id(), v.book(), v.bookId(), v.chapter(), v.verse(), v.text(), v.reference()
            );
        }
    }
}
