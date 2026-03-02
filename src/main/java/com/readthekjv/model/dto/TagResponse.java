package com.readthekjv.model.dto;

import com.readthekjv.model.entity.Tag;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TagResponse(UUID id, String name, short colorIndex, OffsetDateTime createdAt) {

    public static TagResponse from(Tag tag) {
        return new TagResponse(tag.getId(), tag.getName(), tag.getColorIndex(), tag.getCreatedAt());
    }
}
