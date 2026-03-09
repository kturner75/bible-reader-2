package com.readthekjv.model.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record MemorizationEntryResponse(
        UUID id,
        PassageResponse passage,
        String fromVerseRef,
        String toVerseRef,           // end reference (same as fromVerseRef for single verses)
        List<VerseSnippet> verses,   // all verses in passage (replaces fromVerseText)
        int masteryLevel,
        LocalDate nextReviewAt,
        OffsetDateTime addedAt
) {}
