package com.readthekjv.service;

import com.readthekjv.exception.BadRequestException;
import com.readthekjv.util.VerseRangeParser;

/**
 * Write-side {@code [e=…]} cap for every note persist path.
 * Maps {@link VerseRangeParser#requireNoteEmbedCap} to {@link BadRequestException}.
 */
final class NoteEmbedCap {

    private NoteEmbedCap() {}

    static void require(String noteBody) {
        try {
            VerseRangeParser.requireNoteEmbedCap(noteBody);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException(ex.getMessage());
        }
    }
}
