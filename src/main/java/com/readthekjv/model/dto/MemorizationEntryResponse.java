package com.readthekjv.model.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record MemorizationEntryResponse(
        UUID id,
        PassageResponse passage,
        String fromVerseRef,
        String fromVerseText,
        int masteryLevel,
        LocalDate nextReviewAt,
        OffsetDateTime addedAt
) {}
