package com.readthekjv.model;

/**
 * Immutable record representing a Bible book.
 * 
 * @param id Book ID (1-66)
 * @param name Full book name (e.g., "Genesis")
 * @param chapters Total number of chapters in this book
 * @param firstVerseId Global ID of the first verse in this book
 * @param lastVerseId Global ID of the last verse in this book
 */
public record Book(
    int id,
    String name,
    int chapters,
    int firstVerseId,
    int lastVerseId
) {}
