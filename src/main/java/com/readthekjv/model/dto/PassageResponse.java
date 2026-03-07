package com.readthekjv.model.dto;

import com.readthekjv.model.entity.Passage;

import java.util.UUID;

public record PassageResponse(UUID id, int fromVerseId, int toVerseId, String naturalKey) {

    public static PassageResponse from(Passage p) {
        return new PassageResponse(p.getId(), p.getFromVerseId(), p.getToVerseId(), p.getNaturalKey());
    }
}
