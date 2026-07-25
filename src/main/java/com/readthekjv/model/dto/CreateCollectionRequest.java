package com.readthekjv.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CreateCollectionRequest(
    @NotBlank @Size(max = 100) String label,
    @NotNull @Size(min = 1, max = 100) List<UUID> passageIds
) {}
