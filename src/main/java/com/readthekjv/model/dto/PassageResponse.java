package com.readthekjv.model.dto;

import com.readthekjv.model.entity.Passage;

import java.util.UUID;

public record PassageResponse(UUID id, int fromVerseId, int toVerseId, String naturalKey, String title, String reference) {

    public static PassageResponse from(Passage p, String reference) {
        String title = p.getTitle();
        if (title != null && title.isBlank()) title = null;
        else if (title != null) title = title.trim();
        return new PassageResponse(
            p.getId(), p.getFromVerseId(), p.getToVerseId(), p.getNaturalKey(), title, reference
        );
    }

    /** Backward-compatible helper when reference is not needed. */
    public static PassageResponse from(Passage p) {
        return from(p, null);
    }
}
