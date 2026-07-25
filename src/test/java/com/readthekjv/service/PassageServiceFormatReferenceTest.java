package com.readthekjv.service;

import com.readthekjv.model.dto.CollectionReadResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PassageServiceFormatReferenceTest {

    private static CollectionReadResponse.CollectionVerse v(
            int id, String book, int bookId, int chapter, int verse) {
        return new CollectionReadResponse.CollectionVerse(
                id, book, bookId, chapter, verse, "",
                book + " " + chapter + ":" + verse);
    }

    @Test
    void singleVerse() {
        assertEquals("Genesis 1:1",
                PassageService.formatReference(List.of(v(1, "Genesis", 1, 1, 1))));
    }

    @Test
    void contiguousSameChapter() {
        assertEquals("Genesis 1:1–3",
                PassageService.formatReference(List.of(
                        v(1, "Genesis", 1, 1, 1),
                        v(2, "Genesis", 1, 1, 2),
                        v(3, "Genesis", 1, 1, 3))));
    }

    @Test
    void discontiguousSameChapterFormatsSegmentsSeparately() {
        assertEquals("Genesis 1:1; Genesis 1:3",
                PassageService.formatReference(List.of(
                        v(1, "Genesis", 1, 1, 1),
                        v(3, "Genesis", 1, 1, 3))));
    }

    @Test
    void contiguousAcrossChapters() {
        assertEquals("Genesis 1:31–2:2",
                PassageService.formatReference(List.of(
                        v(31, "Genesis", 1, 1, 31),
                        v(32, "Genesis", 1, 2, 1),
                        v(33, "Genesis", 1, 2, 2))));
    }
}
