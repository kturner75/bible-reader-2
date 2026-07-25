package com.readthekjv.model.dto;

import com.readthekjv.model.entity.Passage;

import java.util.UUID;

public record PassageDetailResponse(
    UUID id,
    String naturalKey,
    int fromVerseId,
    int toVerseId,
    String title,
    String reference,
    boolean global
) {
    public static PassageDetailResponse from(Passage p, String reference) {
        String title = p.getTitle();
        if (title != null && title.isBlank()) title = null;
        else if (title != null) title = title.trim();
        return new PassageDetailResponse(
            p.getId(),
            p.getNaturalKey(),
            p.getFromVerseId(),
            p.getToVerseId(),
            title,
            reference,
            p.getUser() == null
        );
    }
}
