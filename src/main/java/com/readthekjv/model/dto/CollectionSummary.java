package com.readthekjv.model.dto;

import com.readthekjv.model.entity.PassageCollection;

import java.time.OffsetDateTime;

public record CollectionSummary(
    long id,
    String label,
    int passageCount,
    int verseCount,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public static CollectionSummary from(PassageCollection c, int verseCount) {
        return new CollectionSummary(
            c.getId(), c.getLabel(), c.getPassageIds().size(), verseCount,
            c.getCreatedAt(), c.getUpdatedAt()
        );
    }
}
