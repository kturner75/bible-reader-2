package com.readthekjv.model.dto;

/**
 * Response from POST /api/memorization/queue/{entryId}/recite.
 * The frontend performs the word-level diff between transcript and expectedText.
 */
public record ReciteResponse(
        String transcript,
        String expectedText
) {}
