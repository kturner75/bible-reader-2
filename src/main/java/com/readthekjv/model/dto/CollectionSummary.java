package com.readthekjv.model.dto;

import com.readthekjv.model.entity.PassageCollection;

import java.time.OffsetDateTime;

public record CollectionSummary(
    long id,
    String label,
    int verseCount,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public static CollectionSummary from(PassageCollection c) {
        return new CollectionSummary(
            c.getId(), c.getLabel(), c.getVerseIds().size(),
            c.getCreatedAt(), c.getUpdatedAt()
        );
    }
}
