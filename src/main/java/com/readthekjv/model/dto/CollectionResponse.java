package com.readthekjv.model.dto;

import com.readthekjv.model.entity.PassageCollection;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record CollectionResponse(
    long id,
    String label,
    List<UUID> passageIds,
    int verseCount,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public static CollectionResponse from(PassageCollection c, int verseCount) {
        return new CollectionResponse(
            c.getId(), c.getLabel(), List.copyOf(c.getPassageIds()),
            verseCount, c.getCreatedAt(), c.getUpdatedAt()
        );
    }
}
