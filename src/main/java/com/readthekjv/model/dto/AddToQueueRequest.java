package com.readthekjv.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /api/memorization/queue.
 *
 * naturalKey follows the passage natural key format:
 *   "26930"              — single verse
 *   "26930:26944"        — consecutive range
 *   "26930:26944,27100"  — non-consecutive segments
 */
public record AddToQueueRequest(
        @NotNull @Size(min = 1, max = 500) String naturalKey
) {}
