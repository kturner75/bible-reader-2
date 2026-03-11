package com.readthekjv.model.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A reading plan with the authenticated user's enrollment state attached.
 *
 * {@code currentDay} and {@code enrolledAt} are null when not enrolled.
 * {@code todayDay} is null when not enrolled, or when {@code currentDay > totalDays}
 * (plan is complete).
 */
public record ReadingPlanResponse(
        UUID                   id,
        String                 slug,
        String                 title,
        int                    totalDays,
        boolean                enrolled,
        Integer                currentDay,      // null if not enrolled
        OffsetDateTime         enrolledAt,      // null if not enrolled
        ReadingPlanDayResponse todayDay         // null if not enrolled or finished
) {}
