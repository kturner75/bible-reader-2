package com.readthekjv.model.dto;

import java.util.List;
import java.util.UUID;

public record PassageReadResponse(
    UUID id,
    String title,
    String reference,
    String naturalKey,
    List<CollectionReadResponse.CollectionVerse> verses
) {}
