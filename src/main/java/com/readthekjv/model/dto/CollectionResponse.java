package com.readthekjv.model.dto;

import com.readthekjv.model.entity.PassageCollection;

import java.time.OffsetDateTime;
import java.util.List;

public record CollectionResponse(
    long id,
    String label,
    List<Integer> verseIds,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public static CollectionResponse from(PassageCollection c) {
        return new CollectionResponse(
            c.getId(), c.getLabel(), List.copyOf(c.getVerseIds()),
            c.getCreatedAt(), c.getUpdatedAt()
        );
    }
}
