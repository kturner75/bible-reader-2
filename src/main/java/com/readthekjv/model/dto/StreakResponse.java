package com.readthekjv.model.dto;

import java.time.LocalDate;

/**
 * Response payload for GET /api/memorization/streak.
 */
public record StreakResponse(
        int currentStreak,
        int longestStreak,
        LocalDate lastReviewDate
) {}
