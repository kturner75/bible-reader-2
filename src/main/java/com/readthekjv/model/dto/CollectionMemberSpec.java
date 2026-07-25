package com.readthekjv.model.dto;

import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * One ordered collection membership. Provide either {@code passageId} (existing)
 * or {@code naturalKey} (find-or-create in the same transaction as the save).
 */
public record CollectionMemberSpec(
    UUID passageId,
    @Size(max = 500) String naturalKey,
    @Size(max = 100) String title,
    /** When true, apply {@code title} (including null to clear) to an owned passage. */
    Boolean updateTitle
) {}
