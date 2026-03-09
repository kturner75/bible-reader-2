package com.readthekjv.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NaturalKeyParserTest {

    // ─── parse() ──────────────────────────────────────────────────────────────

    @Test
    void parseSingleVerse() {
        List<NaturalKeyParser.Segment> segs = NaturalKeyParser.parse("26137");
        assertEquals(1, segs.size());
        assertEquals(26137, segs.get(0).from());
        assertEquals(26137, segs.get(0).to());
    }

    @Test
    void parseConsecutiveRange() {
        List<NaturalKeyParser.Segment> segs = NaturalKeyParser.parse("1:3");
        assertEquals(1, segs.size());
        assertEquals(1, segs.get(0).from());
        assertEquals(3, segs.get(0).to());
    }

    @Test
    void parseRangePlusSingleVerse() {
        List<NaturalKeyParser.Segment> segs = NaturalKeyParser.parse("1:3,5");
        assertEquals(2, segs.size());
        assertEquals(new NaturalKeyParser.Segment(1, 3), segs.get(0));
        assertEquals(new NaturalKeyParser.Segment(5, 5), segs.get(1));
    }

    @Test
    void parseTwoRanges() {
        List<NaturalKeyParser.Segment> segs = NaturalKeyParser.parse("1:3,5:7");
        assertEquals(2, segs.size());
        assertEquals(new NaturalKeyParser.Segment(1, 3), segs.get(0));
        assertEquals(new NaturalKeyParser.Segment(5, 7), segs.get(1));
    }

    @Test
    void parseSingleVerseAtBoundary() {
        // First verse of the Bible
        List<NaturalKeyParser.Segment> first = NaturalKeyParser.parse("1");
        assertEquals(1, first.get(0).from());

        // Last verse of the Bible
        List<NaturalKeyParser.Segment> last = NaturalKeyParser.parse("31102");
        assertEquals(31102, last.get(0).from());
    }

    @Test
    void parseThrowsOnBlank() {
        assertThrows(IllegalArgumentException.class, () -> NaturalKeyParser.parse(""));
        assertThrows(IllegalArgumentException.class, () -> NaturalKeyParser.parse("   "));
    }

    @Test
    void parseThrowsOnNull() {
        assertThrows(IllegalArgumentException.class, () -> NaturalKeyParser.parse(null));
    }

    @Test
    void parseThrowsOnNonNumeric() {
        assertThrows(Exception.class, () -> NaturalKeyParser.parse("abc"));
        assertThrows(Exception.class, () -> NaturalKeyParser.parse("1:abc"));
    }

    // ─── outerFrom() / outerTo() ──────────────────────────────────────────────

    @Test
    void outerBoundsForSingleSegment() {
        var segs = NaturalKeyParser.parse("5:10");
        assertEquals(5,  NaturalKeyParser.outerFrom(segs));
        assertEquals(10, NaturalKeyParser.outerTo(segs));
    }

    @Test
    void outerBoundsForMultipleSegments() {
        var segs = NaturalKeyParser.parse("5:10,20:30");
        assertEquals(5,  NaturalKeyParser.outerFrom(segs));
        assertEquals(30, NaturalKeyParser.outerTo(segs));
    }

    @Test
    void outerBoundsForNonConsecutive() {
        // Segments out of order should still return true min/max
        var segs = List.of(
            new NaturalKeyParser.Segment(100, 110),
            new NaturalKeyParser.Segment(50,  60)
        );
        assertEquals(50,  NaturalKeyParser.outerFrom(segs));
        assertEquals(110, NaturalKeyParser.outerTo(segs));
    }

    // ─── isValid() ────────────────────────────────────────────────────────────

    @Test
    void isValidForWellFormedKeys() {
        assertTrue(NaturalKeyParser.isValid("1"));
        assertTrue(NaturalKeyParser.isValid("31102"));
        assertTrue(NaturalKeyParser.isValid("1:31102"));
        assertTrue(NaturalKeyParser.isValid("1:3,5:7"));
        assertTrue(NaturalKeyParser.isValid("26137"));          // John 3:16
        assertTrue(NaturalKeyParser.isValid("26137:26172"));    // John 3:16 – end of chapter
    }

    @Test
    void isValidSingleVerseRange() {
        // from == to is valid (single verse expressed as range)
        assertTrue(NaturalKeyParser.isValid("5:5"));
    }

    @Test
    void isValidFalseForBlankOrNull() {
        assertFalse(NaturalKeyParser.isValid(null));
        assertFalse(NaturalKeyParser.isValid(""));
        assertFalse(NaturalKeyParser.isValid("   "));
    }

    @Test
    void isValidFalseForNonNumeric() {
        assertFalse(NaturalKeyParser.isValid("abc"));
        assertFalse(NaturalKeyParser.isValid("1:abc"));
    }

    @Test
    void isValidFalseWhenFromExceedsTo() {
        assertFalse(NaturalKeyParser.isValid("10:5"));
    }

    @Test
    void isValidFalseWhenOutOfBibleBounds() {
        assertFalse(NaturalKeyParser.isValid("0"));         // below min
        assertFalse(NaturalKeyParser.isValid("31103"));     // above max
        assertFalse(NaturalKeyParser.isValid("1:99999"));   // to exceeds max
    }
}
