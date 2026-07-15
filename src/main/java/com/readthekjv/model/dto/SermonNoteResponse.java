package com.readthekjv.model.dto;

import com.readthekjv.model.entity.SermonNote;

import java.time.OffsetDateTime;

public record SermonNoteResponse(
    String id,
    String title,
    String note,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public static SermonNoteResponse from(SermonNote n) {
        return new SermonNoteResponse(
            n.getId().toString(), n.getTitle(), n.getNote(),
            n.getCreatedAt(), n.getUpdatedAt()
        );
    }
}
