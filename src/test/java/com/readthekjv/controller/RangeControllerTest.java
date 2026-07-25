package com.readthekjv.controller;

import com.readthekjv.exception.BadRequestException;
import com.readthekjv.model.Verse;
import com.readthekjv.model.dto.PassageContextResponse;
import com.readthekjv.service.BibleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RangeControllerTest {

    private RangeController controller;

    @BeforeEach
    void setUp() {
        BibleService bible = new BibleService();
        List<Verse> verses = new ArrayList<>();
        // Cap-limit fixture: one artificial "chapter" of 600 verses
        for (int i = 1; i <= 600; i++) {
            verses.add(new Verse(i, "Genesis", 1, 1, i, "text"));
        }
        // Multi-chapter fixture for surrounding context (ids 601+)
        int id = 601;
        for (int chapter = 1; chapter <= 3; chapter++) {
            for (int verse = 1; verse <= 3; verse++) {
                verses.add(new Verse(id++, "Exodus", 2, chapter, verse, "text " + chapter + ":" + verse));
            }
        }
        bible.loadVerses(verses);
        controller = new RangeController(bible);
    }

    @Test
    void acceptsRangeWithinLimit() {
        var res = controller.get("1-10");
        assertEquals(10, res.verses().size());
    }

    @Test
    void rejectsRangeAboveVerseCap() {
        assertThrows(BadRequestException.class, () -> controller.get("1-501"));
    }

    @Test
    void surroundingContextIncludesPrevCurrentNext() {
        // Exodus 2:2 is the middle chapter (ids 601-603 ch1, 604-606 ch2, 607-609 ch3)
        PassageContextResponse ctx = controller.getSurroundingContext(605);
        assertNotNull(ctx.prevChapter());
        assertNotNull(ctx.currentChapter());
        assertNotNull(ctx.nextChapter());
        assertEquals(1, ctx.prevChapter().chapter());
        assertEquals(2, ctx.currentChapter().chapter());
        assertEquals(3, ctx.nextChapter().chapter());
        assertEquals(3, ctx.currentChapter().verses().size());
    }

    @Test
    void surroundingContextOmitsPrevAtBookStart() {
        PassageContextResponse ctx = controller.getSurroundingContext(601);
        assertNull(ctx.prevChapter());
        assertEquals(1, ctx.currentChapter().chapter());
        assertEquals(2, ctx.nextChapter().chapter());
    }

    @Test
    void surroundingContextNotFoundForMissingVerse() {
        assertThrows(ResponseStatusException.class, () -> controller.getSurroundingContext(99999));
    }
}
