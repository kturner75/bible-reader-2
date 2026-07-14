package com.readthekjv.model.dto;

import com.readthekjv.model.entity.BookNote;

import java.time.OffsetDateTime;

public record BookNoteResponse(
    int bookId,
    String bookName,
    int firstVerseId,
    String note,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public static BookNoteResponse from(BookNote n, String bookName, int firstVerseId) {
        return new BookNoteResponse(
            n.getBookId(), bookName, firstVerseId,
            n.getNote(), n.getCreatedAt(), n.getUpdatedAt()
        );
    }
}
