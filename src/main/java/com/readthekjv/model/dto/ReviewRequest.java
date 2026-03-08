package com.readthekjv.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Body for POST /api/memorization/queue/{entryId}/review.
 * quality 0 = Again, 3 = Hard, 4 = Good, 5 = Easy (SM-2 scale).
 */
public record ReviewRequest(
        @NotNull @Min(0) @Max(5) Integer quality
) {}
