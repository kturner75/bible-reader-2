package com.readthekjv.controller;

import com.readthekjv.exception.BadRequestException;
import com.readthekjv.model.Verse;
import com.readthekjv.service.BibleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RangeControllerTest {

    private RangeController controller;

    @BeforeEach
    void setUp() {
        BibleService bible = new BibleService();
        List<Verse> verses = new ArrayList<>();
        for (int i = 1; i <= 600; i++) {
            verses.add(new Verse(i, "Genesis", 1, 1, i, "text"));
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
}
