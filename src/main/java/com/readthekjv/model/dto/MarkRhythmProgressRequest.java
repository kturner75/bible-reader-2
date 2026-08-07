package com.readthekjv.model.dto;

/**
 * Body for POST /api/rhythms/lanes/{laneId}/progress — "I read through here".
 *
 * There is deliberately no weekday field: a lane may be advanced on any day.
 */
public record MarkRhythmProgressRequest(
        int bookId,
        int throughChapter
) {}
