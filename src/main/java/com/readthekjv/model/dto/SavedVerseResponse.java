package com.readthekjv.model.dto;

import com.readthekjv.model.entity.SavedVerse;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record SavedVerseResponse(int verseId, String note, OffsetDateTime savedAt, List<UUID> tagIds) {

    public static SavedVerseResponse from(SavedVerse sv) {
        List<UUID> ids = sv.getSavedVerseTags().stream()
                .map(svt -> svt.getTag().getId())
                .toList();
        return new SavedVerseResponse(sv.getVerseId(), sv.getNote(), sv.getSavedAt(), ids);
    }
}
