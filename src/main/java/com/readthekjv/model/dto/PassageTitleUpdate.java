package com.readthekjv.model.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Optional title change applied atomically with a collection create/update. */
public record PassageTitleUpdate(
    @NotNull UUID id,
    @Size(max = 100) String title
) {}
