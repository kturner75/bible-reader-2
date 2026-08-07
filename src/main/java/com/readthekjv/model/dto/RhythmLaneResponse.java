package com.readthekjv.model.dto;

import java.util.List;

/**
 * A lane with its derived reading position.
 *
 * @param nextReading   the chapter to read next, or null when the lane is complete
 * @param chaptersRead  chapters finished across the whole lane
 * @param chaptersTotal chapters in the lane's full book list
 * @param complete      true when the cursor has reached the last chapter of the last book
 * @param markedToday   true when this lane received a progress mark today. Lets the
 *                      dashboard's "Today's Reading" card settle once you have read,
 *                      without making any lane mandatory — a rhythm is never "owed".
 * @param books         per-book breakdown, mirroring the "chapters read" column of a
 *                      hand-kept reading sheet
 */
public record RhythmLaneResponse(
        Long id,
        String name,
        Short dayOfWeek,
        Integer cursorBookId,
        int cursorChapter,
        RhythmNextReading nextReading,
        int chaptersRead,
        int chaptersTotal,
        boolean complete,
        boolean markedToday,
        List<RhythmLaneBook> books
) {
    /** Where to resume, ready to link straight into the reader. */
    public record RhythmNextReading(
            int bookId,
            String bookName,
            int chapter,
            int firstVerseId
    ) {}

    /** One book of the lane with its progress. */
    public record RhythmLaneBook(
            int bookId,
            String bookName,
            int chaptersTotal,
            int chaptersRead
    ) {}
}
