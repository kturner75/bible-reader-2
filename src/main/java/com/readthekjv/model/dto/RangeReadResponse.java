package com.readthekjv.model.dto;

import java.util.List;

/** Hydrated verses for a portable [v=…] range session (no Passage required). */
public record RangeReadResponse(
    String v,
    String reference,
    String naturalKey,
    List<CollectionReadResponse.CollectionVerse> verses
) {}
