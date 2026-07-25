package com.readthekjv.model.dto;

import jakarta.validation.constraints.Size;

public record UpdatePassageTitleRequest(
    @Size(max = 100) String title
) {}
