package com.readthekjv.model.dto;

import java.util.UUID;

/**
 * A single day's reading assignment within a plan.
 * {@code fromVerseId} is used for the "Open in Reader" link (/read?vid={fromVerseId}).
 */
public record ReadingPlanDayResponse(
        UUID   id,
        int    dayNumber,
        String label,
        int    fromVerseId,
        int    toVerseId
) {}
