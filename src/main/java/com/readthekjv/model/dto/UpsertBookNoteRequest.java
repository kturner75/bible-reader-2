package com.readthekjv.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpsertBookNoteRequest(
    @NotBlank @Size(max = 10000) String note
) {}
