package com.readthekjv.model.dto;

/**
 * Lightweight verse representation used in passage-level responses
 * (memorization queue, passage context, training entries).
 */
public record VerseSnippet(int id, int verseNum, String reference, String text) {}
