package com.readthekjv.model;

/**
 * Immutable record representing chapter info for dropdowns.
 * 
 * @param chapter Chapter number
 * @param firstVerseId Global ID of the first verse in this chapter
 * @param verseCount Number of verses in this chapter
 */
public record ChapterInfo(
    int chapter,
    int firstVerseId,
    int verseCount
) {}
