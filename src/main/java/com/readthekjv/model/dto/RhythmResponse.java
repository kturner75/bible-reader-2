package com.readthekjv.model.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A rhythm with all of its lanes.
 *
 * @param todayLaneIds ids of the lanes whose dayOfWeek matches today. Purely a
 *                     surfacing hint for the dashboard — may be empty, may hold
 *                     several, and every other lane remains fully usable.
 */
public record RhythmResponse(
        Long id,
        String title,
        List<RhythmLaneResponse> lanes,
        List<Long> todayLaneIds,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
