package com.readthekjv.model.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record AddToQueueRequest(
        @NotNull @Min(1) @Max(31102) Integer fromVerseId,
        @NotNull @Min(1) @Max(31102) Integer toVerseId
) {}
