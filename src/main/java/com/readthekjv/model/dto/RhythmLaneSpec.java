package com.readthekjv.model.dto;

import java.util.List;

/**
 * A lane as submitted by the rhythm builder.
 *
 * @param id           existing lane id when editing; null creates a new lane.
 *                     Supplying it is what preserves the lane's cursor through an edit.
 * @param name         lane label, e.g. "Sunday" or "Gospels"
 * @param dayOfWeek    ISO day number 1–7, or null for "any day". A hint only —
 *                     it never restricts when the lane may be read.
 * @param bookIds      ordered book ids (1–66) the lane progresses through
 * @param cursorBookId optional explicit cursor book (used by "set position");
 *                     null leaves the existing cursor untouched
 * @param cursorChapter chapters finished in cursorBookId; ignored when cursorBookId is null
 */
public record RhythmLaneSpec(
        Long id,
        String name,
        Short dayOfWeek,
        List<Integer> bookIds,
        Integer cursorBookId,
        Integer cursorChapter
) {}
