package com.readthekjv.model;

import java.util.List;

/**
 * Lightweight search result: verse ids only (no text/highlights).
 * Used for Matching Passages overlap against large hit sets.
 */
public record SearchIdsResult(
    String query,
    int count,
    List<Integer> ids
) {}
