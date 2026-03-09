package com.readthekjv.model.dto;

import java.util.List;

/**
 * Response for GET /api/memorization/context/{verseId}.
 *
 * Returns verses for the previous, current, and next chapters in the same book,
 * used by the passage picker modal to let the user select a passage range.
 * prevChapter / nextChapter are null when at book boundaries.
 */
public record PassageContextResponse(
        ChapterContext prevChapter,
        ChapterContext currentChapter,
        ChapterContext nextChapter
) {
    public record ChapterContext(
            int bookId,
            String bookName,
            int chapter,
            List<VerseSnippet> verses
    ) {}
}
