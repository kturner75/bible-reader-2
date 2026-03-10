package com.readthekjv.model.dto;

import java.time.LocalDate;

/**
 * Compact DTO for a single review history entry, embedded in MemorizationEntryResponse.
 * quality: 0=Again, 3=Hard, 4=Good, 5=Easy (SM-2 scale)
 */
public record ReviewHistorySnippet(short quality, short masteryAfter, LocalDate reviewedAt) {}
