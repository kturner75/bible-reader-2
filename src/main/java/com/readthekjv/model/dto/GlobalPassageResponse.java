package com.readthekjv.model.dto;

import java.util.UUID;

/**
 * DTO for a single global/curated passage as surfaced on the dashboard's
 * "Featured Passages" card. {@code alreadyQueued} is true when the authenticated
 * user already has a memorization entry for this passage's naturalKey.
 */
public record GlobalPassageResponse(
        UUID    id,
        String  naturalKey,
        String  title,
        String  fromVerseRef,
        String  toVerseRef,
        boolean alreadyQueued
) {}
