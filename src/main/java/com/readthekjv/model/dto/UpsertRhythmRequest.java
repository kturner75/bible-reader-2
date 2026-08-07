package com.readthekjv.model.dto;

import java.util.List;

/** Body for POST /api/rhythms and PUT /api/rhythms/{id}. */
public record UpsertRhythmRequest(
        String title,
        List<RhythmLaneSpec> lanes
) {}
