package com.readthekjv.model.dto;

import com.readthekjv.model.entity.ChapterNote;

import java.time.OffsetDateTime;

public record ChapterNoteResponse(
    int bookId,
    String bookName,
    int chapter,
    int firstVerseId,
    int verseCount,
    String note,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public static ChapterNoteResponse from(ChapterNote n, String bookName, int firstVerseId, int verseCount) {
        return new ChapterNoteResponse(
            n.getBookId(), bookName, n.getChapter(), firstVerseId, verseCount,
            n.getNote(), n.getCreatedAt(), n.getUpdatedAt()
        );
    }
}
