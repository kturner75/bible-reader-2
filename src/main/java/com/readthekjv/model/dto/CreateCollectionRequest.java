package com.readthekjv.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateCollectionRequest(
    @NotBlank @Size(max = 100) String label,
    @NotNull @Valid @Size(min = 1, max = 500) List<CollectionMemberSpec> members
) {}
