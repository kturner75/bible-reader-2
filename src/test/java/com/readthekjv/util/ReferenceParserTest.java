package com.readthekjv.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReferenceParserTest {

    @Test
    void testBookOnlyInput() {
        // "1p" should resolve to 1 Peter 1:1
        var result = ReferenceParser.parse("1p");
        assertNotNull(result, "1p should be recognized as a reference");
        assertEquals("1 Peter", result.book());
        assertEquals(1, result.chapter());
        assertNull(result.verse()); // No verse specified
    }

    @Test
    void testBookWithChapterSeparatedBySpace() {
        // "1p 2" should resolve to 1 Peter 2:null (chapter 2)
        var result = ReferenceParser.parse("1p 2");
        assertNotNull(result, "1p 2 should be recognized as a reference");
        assertEquals("1 Peter", result.book());
        assertEquals(2, result.chapter());
        assertNull(result.verse());
    }

    @Test
    void testBookWithChapterAndVerse() {
        // "1p 2:3" should resolve to 1 Peter 2:3
        var result = ReferenceParser.parse("1p 2:3");
        assertNotNull(result, "1p 2:3 should be recognized as a reference");
        assertEquals("1 Peter", result.book());
        assertEquals(2, result.chapter());
        assertEquals(3, result.verse());
    }

    @Test
    void testGenBookOnly() {
        // "gen" should resolve to Genesis 1:null
        var result = ReferenceParser.parse("gen");
        assertNotNull(result, "gen should be recognized as a reference");
        assertEquals("Genesis", result.book());
        assertEquals(1, result.chapter());
        assertNull(result.verse());
    }

    @Test
    void testGenWithSpaceChapter() {
        // "gen 3" should resolve to Genesis 3:null
        var result = ReferenceParser.parse("gen 3");
        assertNotNull(result, "gen 3 should be recognized as a reference");
        assertEquals("Genesis", result.book());
        assertEquals(3, result.chapter());
        assertNull(result.verse());
    }

    @Test
    void testGenWithAttachedChapter() {
        // "gen1" should resolve to Genesis 1:null
        var result = ReferenceParser.parse("gen1");
        assertNotNull(result, "gen1 should be recognized as a reference");
        assertEquals("Genesis", result.book());
        assertEquals(1, result.chapter());
        assertNull(result.verse());
    }

    @Test
    void testFullReference() {
        // "john 3:16" should resolve to John 3:16
        var result = ReferenceParser.parse("john 3:16");
        assertNotNull(result, "john 3:16 should be recognized as a reference");
        assertEquals("John", result.book());
        assertEquals(3, result.chapter());
        assertEquals(16, result.verse());
    }

    @Test
    void testPsalmReference() {
        // "ps 23" should resolve to Psalm 23:null
        var result = ReferenceParser.parse("ps 23");
        assertNotNull(result, "ps 23 should be recognized as a reference");
        assertEquals("Psalm", result.book());
        assertEquals(23, result.chapter());
        assertNull(result.verse());
    }

    @Test
    void testNonReference() {
        // "nonsense" should not be recognized
        var result = ReferenceParser.parse("nonsense");
        assertNull(result, "nonsense should not be recognized as a reference");
    }

    @Test
    void testGenWithAttachedChapterAndVerse() {
        // "gen1:1" should resolve to Genesis 1:1
        var result = ReferenceParser.parse("gen1:1");
        assertNotNull(result, "gen1:1 should be recognized as a reference");
        assertEquals("Genesis", result.book());
        assertEquals(1, result.chapter());
        assertEquals(1, result.verse());
    }

    @Test
    void test2PeterAlias() {
        // "2p" should resolve to 2 Peter 1:null
        var result = ReferenceParser.parse("2p");
        assertNotNull(result, "2p should be recognized as a reference");
        assertEquals("2 Peter", result.book());
        assertEquals(1, result.chapter());
        assertNull(result.verse());
    }

    @Test
    void test1JohnAlias() {
        // "1j" should resolve to 1 John 1:null
        var result = ReferenceParser.parse("1j");
        assertNotNull(result, "1j should be recognized as a reference");
        assertEquals("1 John", result.book());
        assertEquals(1, result.chapter());
        assertNull(result.verse());
    }

    @Test
    void testMultiWordBookWithChapter() {
        // Full book name spanning multiple words (book-note links emit these)
        var result = ReferenceParser.parse("Song of Solomon 2");
        assertNotNull(result, "Song of Solomon 2 should be recognized as a reference");
        assertEquals("Song of Solomon", result.book());
        assertEquals(2, result.chapter());
        assertNull(result.verse());
    }

    @Test
    void testMultiWordBookWithChapterAndVerse() {
        var result = ReferenceParser.parse("song of solomon 2:5");
        assertNotNull(result, "song of solomon 2:5 should be recognized as a reference");
        assertEquals("Song of Solomon", result.book());
        assertEquals(2, result.chapter());
        assertEquals(5, result.verse());
    }

    @Test
    void testMultiWordBookExtraWhitespace() {
        var result = ReferenceParser.parse("song  of   solomon 3");
        assertNotNull(result, "extra internal whitespace should still resolve");
        assertEquals("Song of Solomon", result.book());
        assertEquals(3, result.chapter());
    }

    @Test
    void testUnknownMultiWordBookRejected() {
        assertNull(ReferenceParser.parse("hello world 5"),
            "unknown multi-word book should not parse");
    }

    @Test
    void absoluteLinkSingleVerse() {
        var link = ReferenceParser.parseAbsoluteLink("John 3:16");
        assertNotNull(link);
        assertEquals("John", link.book());
        assertEquals(3, link.chapter());
        assertEquals(List.of(new ScopeRelativeLinkParser.Span(16, 16)), link.verseSpans());
        assertFalse(link.isMultiVerse());
    }

    @Test
    void absoluteLinkInclusiveRange() {
        var link = ReferenceParser.parseAbsoluteLink("John 3:16-18");
        assertNotNull(link);
        assertEquals("John", link.book());
        assertEquals(3, link.chapter());
        assertEquals(List.of(new ScopeRelativeLinkParser.Span(16, 18)), link.verseSpans());
        assertTrue(link.isMultiVerse());
        assertTrue(ReferenceParser.looksLikeAbsoluteMultiVerse("John 3:16-18"));
    }

    @Test
    void absoluteLinkReversedSpan() {
        var link = ReferenceParser.parseAbsoluteLink("jer 13:11-1");
        assertNotNull(link);
        assertEquals("Jeremiah", link.book());
        assertEquals(List.of(new ScopeRelativeLinkParser.Span(1, 11)), link.verseSpans());
    }

    @Test
    void absoluteLinkCommaListAndMixed() {
        assertEquals(
                List.of(
                        new ScopeRelativeLinkParser.Span(16, 16),
                        new ScopeRelativeLinkParser.Span(18, 18)),
                ReferenceParser.parseAbsoluteLink("John 3:16,18").verseSpans());
        assertEquals(
                List.of(
                        new ScopeRelativeLinkParser.Span(1, 11),
                        new ScopeRelativeLinkParser.Span(15, 15)),
                ReferenceParser.parseAbsoluteLink("John 3:1-11,15").verseSpans());
        assertEquals(
                List.of(
                        new ScopeRelativeLinkParser.Span(1, 11),
                        new ScopeRelativeLinkParser.Span(15, 15)),
                ReferenceParser.parseAbsoluteLink("John 3:1-11, 15").verseSpans());
    }

    @Test
    void absoluteLinkAttachedForm() {
        var link = ReferenceParser.parseAbsoluteLink("jn3:16-18");
        assertNotNull(link);
        assertEquals("John", link.book());
        assertEquals(3, link.chapter());
        assertEquals(List.of(new ScopeRelativeLinkParser.Span(16, 18)), link.verseSpans());
    }

    @Test
    void absoluteLinkMultiWordBook() {
        var link = ReferenceParser.parseAbsoluteLink("Song of Solomon 2:1-3");
        assertNotNull(link);
        assertEquals("Song of Solomon", link.book());
        assertEquals(2, link.chapter());
        assertEquals(List.of(new ScopeRelativeLinkParser.Span(1, 3)), link.verseSpans());
    }

    @Test
    void absoluteLinkRejectsCrossChapterAndJunk() {
        assertNull(ReferenceParser.parseAbsoluteLink("John 3:16-4:2"));
        assertNull(ReferenceParser.parseAbsoluteLink("John 3-4"));
        assertNull(ReferenceParser.parseAbsoluteLink("nonsense 3:1-2"));
        assertNull(ReferenceParser.parseAbsoluteLink("John 3"));
        assertFalse(ReferenceParser.looksLikeAbsoluteMultiVerse("John 3:16"));
        assertFalse(ReferenceParser.looksLikeAbsoluteMultiVerse("1-11"));
    }

    @Test
    void parseStillHandlesSingleVerse() {
        // Existing single-ref path must remain unchanged
        var result = ReferenceParser.parse("john 3:16");
        assertNotNull(result);
        assertEquals("John", result.book());
        assertEquals(3, result.chapter());
        assertEquals(16, result.verse());
    }
}
