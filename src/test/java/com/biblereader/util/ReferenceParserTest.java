package com.biblereader.util;

import org.junit.jupiter.api.Test;
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
}
