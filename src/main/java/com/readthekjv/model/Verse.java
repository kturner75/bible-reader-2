package com.readthekjv.model;

/**
 * Immutable record representing a single Bible verse.
 * 
 * @param id Global verse ID (1-31102, Genesis 1:1 = 1)
 * @param book Book name (e.g., "Genesis")
 * @param bookId Book ID (1-66)
 * @param chapter Chapter number
 * @param verse Verse number within the chapter
 * @param text The verse text content
 */
public record Verse(
    int id,
    String book,
    int bookId,
    int chapter,
    int verse,
    String text
) {
    /**
     * Returns a formatted reference string (e.g., "Genesis 1:1")
     */
    public String reference() {
        return book + " " + chapter + ":" + verse;
    }
}
