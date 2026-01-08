package com.biblereader.model;

import java.util.List;

/**
 * Immutable record representing search results.
 * 
 * @param query The original search query
 * @param count Total number of matching verses
 * @param verses List of matching verses with highlighted text
 */
public record SearchResult(
    String query,
    int count,
    List<VerseMatch> verses
) {
    /**
     * A single verse match with optional highlighted snippet.
     */
    public record VerseMatch(
        int id,
        String book,
        int chapter,
        int verse,
        String text,
        String highlight
    ) {}
}
