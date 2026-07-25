package com.readthekjv.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VerseRangeParserTest {

    @Test
    void parseSingleVerse() {
        assertEquals(List.of(new VerseRangeParser.Range(26136, 26136)),
                VerseRangeParser.parseVToken("[v=26136]"));
    }

    @Test
    void parseContiguousAndMultiSegment() {
        assertEquals(List.of(
                        new VerseRangeParser.Range(26136, 26138),
                        new VerseRangeParser.Range(26140, 26140)),
                VerseRangeParser.parseVToken("[v=26136-26138,26140]"));
    }

    @Test
    void swapsReversedBoundsAndMergesAdjacent() {
        assertEquals(List.of(new VerseRangeParser.Range(1, 5)),
                VerseRangeParser.parseVToken("[v=3-1,4-5]"));
    }

    @Test
    void mergesOverlapsWhenSorting() {
        assertEquals(List.of(new VerseRangeParser.Range(10, 20)),
                VerseRangeParser.normalizeRanges(List.of(
                        new VerseRangeParser.Range(15, 20),
                        new VerseRangeParser.Range(10, 16))));
    }

    @Test
    void rejectsOutOfBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> VerseRangeParser.parseVToken("[v=0]"));
        assertThrows(IllegalArgumentException.class,
                () -> VerseRangeParser.parseVToken("[v=31103]"));
    }

    @Test
    void serializeRoundTrip() {
        String token = "[v=1-3,5,10-12]";
        List<VerseRangeParser.Range> ranges = VerseRangeParser.parseVToken(token);
        assertEquals(token, VerseRangeParser.serializeVToken(ranges));
    }

    @Test
    void naturalKeySymmetry() {
        String key = "1:3,5,10:12";
        List<VerseRangeParser.Range> ranges = VerseRangeParser.rangesFromNaturalKey(key);
        assertEquals(key, VerseRangeParser.naturalKeyFromRanges(ranges));
        assertEquals("[v=1-3,5,10-12]", VerseRangeParser.serializeVToken(ranges));
    }

    @Test
    void equalRangesIgnoresInputOrder() {
        assertTrue(VerseRangeParser.equalRanges(
                List.of(new VerseRangeParser.Range(5, 5), new VerseRangeParser.Range(1, 3)),
                List.of(new VerseRangeParser.Range(1, 3), new VerseRangeParser.Range(5, 5))));
    }

    @Test
    void expandVerseIds() {
        assertEquals(List.of(1, 2, 3, 5),
                VerseRangeParser.expandVerseIds(VerseRangeParser.parseVToken("[v=1-3,5]")));
    }

    @Test
    void acceptsBareBodyAndVPrefix() {
        assertEquals(VerseRangeParser.parseVToken("[v=2]"),
                VerseRangeParser.parseVToken("v=2"));
        assertEquals(VerseRangeParser.parseVToken("[v=2]"),
                VerseRangeParser.parseRangeBody("2"));
    }
}
