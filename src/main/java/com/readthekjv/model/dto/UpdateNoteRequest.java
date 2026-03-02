package com.readthekjv.model.dto;

import jakarta.validation.constraints.Size;

public record UpdateNoteRequest(
    @Size(max = 500) String note
) {}
