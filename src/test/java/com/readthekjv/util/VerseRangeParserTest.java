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

    @Test
    void parseEmbedTokenSameGrammarAsV() {
        assertEquals(List.of(new VerseRangeParser.Range(14625, 14625)),
                VerseRangeParser.parseVToken("[e=14625]"));
        assertEquals(List.of(
                        new VerseRangeParser.Range(14625, 14627),
                        new VerseRangeParser.Range(14630, 14630)),
                VerseRangeParser.parseVToken("[e=14625-14627,14630]"));
        assertEquals(VerseRangeParser.parseVToken("[v=14625-14627]"),
                VerseRangeParser.parseVToken("[e=14625-14627]"));
        assertEquals(VerseRangeParser.parseVToken("[e=14625]"),
                VerseRangeParser.parseVToken("e=14625"));
    }

    @Test
    void serializeETokenRoundTrip() {
        String token = "[e=1-3,5,10-12]";
        List<VerseRangeParser.Range> ranges = VerseRangeParser.parseVToken(token);
        assertEquals(token, VerseRangeParser.serializeEToken(ranges));
        assertEquals("[v=1-3,5,10-12]", VerseRangeParser.serializeVToken(ranges));
    }

    @Test
    void isEmbedTokenDetectsPrefixOnly() {
        assertTrue(VerseRangeParser.isEmbedToken("[e=14625]"));
        assertTrue(VerseRangeParser.isEmbedToken("e=14625-14627"));
        assertFalse(VerseRangeParser.isEmbedToken("[v=14625]"));
        assertFalse(VerseRangeParser.isEmbedToken("14625"));
        assertFalse(VerseRangeParser.isEmbedToken(null));
    }

    @Test
    void parseDoesNotTruncateEmbedOverTwelve() {
        // Parse keeps the full range so a pasted token is not silently shortened.
        // Write-side refuse is requireNoteEmbedCap (and the JS twin).
        List<VerseRangeParser.Range> thirteen = VerseRangeParser.parseVToken("[e=1-13]");
        assertEquals(List.of(new VerseRangeParser.Range(1, 13)), thirteen);
        assertEquals(13, VerseRangeParser.expandVerseIds(thirteen).size());
        assertEquals("[e=1-13]", VerseRangeParser.serializeEToken(thirteen));
    }

    @Test
    void requireNoteEmbedCapRefusesPastedEmbedOverTwelve() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> VerseRangeParser.requireNoteEmbedCap("Notes\n[e=1-13]\nmore"));
        assertTrue(ex.getMessage().contains("12"));
        assertTrue(ex.getMessage().contains("13"));

        assertDoesNotThrow(() -> VerseRangeParser.requireNoteEmbedCap("[e=1-12]"));
        assertDoesNotThrow(() -> VerseRangeParser.requireNoteEmbedCap("[v=1-13]"));
        assertDoesNotThrow(() -> VerseRangeParser.requireNoteEmbedCap(null));
        assertDoesNotThrow(() -> VerseRangeParser.requireNoteEmbedCap(""));
        assertDoesNotThrow(() -> VerseRangeParser.requireNoteEmbedCap("[e=not-a-range]"));
    }

    @Test
    void trailingJunkOnABoundIsNotARealRange() {
        assertThrows(IllegalArgumentException.class,
                () -> VerseRangeParser.parseVToken("[e=1-13junk]"));
        assertThrows(IllegalArgumentException.class,
                () -> VerseRangeParser.parseVToken("[e=13junk]"));
        assertThrows(IllegalArgumentException.class,
                () -> VerseRangeParser.parseRangeBody("1-13junk"));
        assertThrows(IllegalArgumentException.class,
                () -> VerseRangeParser.parseRangeBody("13junk"));

        // Fail-closed with JS: junk is skipped, not treated as a 13-verse embed.
        assertDoesNotThrow(() -> VerseRangeParser.requireNoteEmbedCap("[e=1-13junk]"));
        assertDoesNotThrow(() -> VerseRangeParser.requireNoteEmbedCap("[e=13junk]"));
    }
}
