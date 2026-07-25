package com.readthekjv.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CreateCollectionRequest(
    @NotBlank @Size(max = 100) String label,
    // List-length ceiling; PassageCollectionService also enforces a 500-verse
    // expanded total (repeats count) so hydration cannot balloon.
    @NotNull @Size(min = 1, max = 500) List<UUID> passageIds,
    /** Title edits committed in the same transaction as the collection save. */
    @Valid @Size(max = 500) List<PassageTitleUpdate> passageTitles
) {
    public CreateCollectionRequest {
        if (passageTitles == null) {
            passageTitles = List.of();
        }
    }
}
