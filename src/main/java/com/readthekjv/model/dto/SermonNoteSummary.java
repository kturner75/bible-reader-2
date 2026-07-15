package com.readthekjv.model.dto;

import com.readthekjv.model.entity.SermonNote;

import java.time.OffsetDateTime;

public record SermonNoteSummary(
    String id,
    String title,
    String snippet,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    private static final int SNIPPET_LENGTH = 160;

    public static SermonNoteSummary from(SermonNote n) {
        return new SermonNoteSummary(
            n.getId().toString(), n.getTitle(), snippetOf(n.getNote()),
            n.getCreatedAt(), n.getUpdatedAt()
        );
    }

    private static String snippetOf(String note) {
        String flat = note.replaceAll("\\s+", " ").trim();
        return flat.length() <= SNIPPET_LENGTH ? flat : flat.substring(0, SNIPPET_LENGTH).trim() + "…";
    }
}
