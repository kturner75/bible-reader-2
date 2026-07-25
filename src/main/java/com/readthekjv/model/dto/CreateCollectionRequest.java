package com.readthekjv.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CreateCollectionRequest(
    @NotBlank @Size(max = 100) String label,
    // Cap matches the builder's 500-verse budget; V17.1 can migrate sparse
    // verse lists into many one-verse passages, so 100 was too low to re-save.
    @NotNull @Size(min = 1, max = 500) List<UUID> passageIds
) {}
