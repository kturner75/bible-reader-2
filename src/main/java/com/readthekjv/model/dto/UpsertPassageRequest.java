package com.readthekjv.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpsertPassageRequest(
    @NotBlank @Size(max = 500) String naturalKey,
    @Size(max = 100) String title
) {}
