package com.readthekjv.service;

import com.readthekjv.model.Verse;
import com.readthekjv.util.ReferenceParser;
import com.readthekjv.util.VerseRangeParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BibleServiceAbsoluteLinkTest {

    private BibleService bibleService;

    @BeforeEach
    void setUp() {
        bibleService = new BibleService();
        // John ch3 with 20 verses starting at id 100
        java.util.ArrayList<Verse> verses = new java.util.ArrayList<>();
        for (int v = 1; v <= 20; v++) {
            verses.add(new Verse(99 + v, "John", 43, 3, v, "verse " + v));
        }
        bibleService.loadVerses(verses);
    }

    @Test
    void resolveInclusiveRange() {
        var link = ReferenceParser.parseAbsoluteLink("John 3:16-18");
        assertNotNull(link);
        assertEquals(
                List.of(new VerseRangeParser.Range(115, 117)),
                bibleService.resolveAbsoluteLink(link).orElseThrow());
    }

    @Test
    void resolveCommaList() {
        var link = ReferenceParser.parseAbsoluteLink("John 3:1-11,15");
        assertNotNull(link);
        assertEquals(
                List.of(
                        new VerseRangeParser.Range(100, 110),
                        new VerseRangeParser.Range(114, 114)),
                bibleService.resolveAbsoluteLink(link).orElseThrow());
    }

    @Test
    void rejectOutOfBounds() {
        var link = ReferenceParser.parseAbsoluteLink("John 3:1-99");
        assertNotNull(link);
        assertTrue(bibleService.resolveAbsoluteLink(link).isEmpty());
    }

    @Test
    void rejectUnknownChapter() {
        var link = ReferenceParser.parseAbsoluteLink("John 99:1-2");
        assertNotNull(link);
        assertTrue(bibleService.resolveAbsoluteLink(link).isEmpty());
    }
}
