package com.readthekjv.model.dto;

/**
 * One book offered by the finder's scripture filter.
 *
 * Its own type rather than a {@link SermonNoteSummary.ScriptureRef} with {@code chapter = 0}:
 * a filter option is a book, not a reference to one, and a zero chapter is not a chapter.
 */
public record BookOption(int bookId, String label) {}
