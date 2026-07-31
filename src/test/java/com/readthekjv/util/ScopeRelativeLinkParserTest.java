package com.readthekjv.util;

import com.readthekjv.model.ChapterInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScopeRelativeLinkParserTest {

    @Test
    void chapterSingleVerse() {
        // firstVerseId=1000 → verse 12 is 1011
        assertEquals(
                List.of(new VerseRangeParser.Range(1011, 1011)),
                ScopeRelativeLinkParser.resolveChapterRelative("12", 1000, 27));
    }

    @Test
    void chapterInclusiveRange() {
        assertEquals(
                List.of(new VerseRangeParser.Range(1000, 1010)),
                ScopeRelativeLinkParser.resolveChapterRelative("1-11", 1000, 27));
        // reversed bounds
        assertEquals(
                List.of(new VerseRangeParser.Range(1000, 1010)),
                ScopeRelativeLinkParser.resolveChapterRelative("11-1", 1000, 27));
    }

    @Test
    void chapterDiscreteAndMixed() {
        assertEquals(
                List.of(
                        new VerseRangeParser.Range(1000, 1000),
                        new VerseRangeParser.Range(1004, 1004),
                        new VerseRangeParser.Range(1006, 1006)),
                ScopeRelativeLinkParser.resolveChapterRelative("1,5,7", 1000, 27));
        assertEquals(
                List.of(
                        new VerseRangeParser.Range(1000, 1010),
                        new VerseRangeParser.Range(1014, 1014)),
                ScopeRelativeLinkParser.resolveChapterRelative("1-11,15", 1000, 27));
        // spaces allowed
        assertEquals(
                List.of(
                        new VerseRangeParser.Range(1000, 1010),
                        new VerseRangeParser.Range(1014, 1014)),
                ScopeRelativeLinkParser.resolveChapterRelative("1-11, 15", 1000, 27));
    }

    @Test
    void chapterMergesAdjacent() {
        // vv 1-3 → 1000-1002; vv 4-5 → 1003-1004; merge → 1000-1004
        assertEquals(
                List.of(new VerseRangeParser.Range(1000, 1004)),
                ScopeRelativeLinkParser.resolveChapterRelative("1-3,4-5", 1000, 27));
    }

    @Test
    void chapterRejectsOutOfBoundsAndJunk() {
        assertNull(ScopeRelativeLinkParser.resolveChapterRelative("1-99", 1000, 27));
        assertNull(ScopeRelativeLinkParser.resolveChapterRelative("0", 1000, 27));
        assertNull(ScopeRelativeLinkParser.resolveChapterRelative("1-11x", 1000, 27));
        assertNull(ScopeRelativeLinkParser.resolveChapterRelative("John 3:16", 1000, 27));
        assertNull(ScopeRelativeLinkParser.resolveChapterRelative("3:16", 1000, 27));
        assertNull(ScopeRelativeLinkParser.resolveChapterRelative("1-11", 1000, 0));
    }

    @Test
    void bookWholeChapters() {
        List<ChapterInfo> chapters = List.of(
                new ChapterInfo(1, 100, 5),
                new ChapterInfo(2, 105, 4),
                new ChapterInfo(3, 109, 10));
        assertEquals(
                List.of(new VerseRangeParser.Range(105, 108)),
                ScopeRelativeLinkParser.resolveBookRelative("2", chapters));
        assertEquals(
                List.of(new VerseRangeParser.Range(100, 108)),
                ScopeRelativeLinkParser.resolveBookRelative("1-2", chapters));
    }

    @Test
    void bookChapterVerseAndRange() {
        List<ChapterInfo> chapters = List.of(
                new ChapterInfo(3, 109, 16),
                new ChapterInfo(13, 500, 27));
        assertEquals(
                List.of(new VerseRangeParser.Range(124, 124)),
                ScopeRelativeLinkParser.resolveBookRelative("3:16", chapters));
        assertEquals(
                List.of(new VerseRangeParser.Range(500, 510)),
                ScopeRelativeLinkParser.resolveBookRelative("13:1-11", chapters));
        assertEquals(
                List.of(
                        new VerseRangeParser.Range(500, 510),
                        new VerseRangeParser.Range(514, 514)),
                ScopeRelativeLinkParser.resolveBookRelative("13:1-11,13:15", chapters));
        // bare verse after comma is invalid without chapter — use 13:1-11,15 meaning ch13 v15?
        // Spec: bare numbers are chapters. So "13:1-11,15" = ch13 vv1-11 + whole chapter 15.
    }

    @Test
    void bookMixedChapterAndVerseSegments() {
        List<ChapterInfo> chapters = List.of(
                new ChapterInfo(1, 100, 5),
                new ChapterInfo(2, 105, 4),
                new ChapterInfo(3, 109, 16));
        // chapters 1-2 plus 3:16
        assertEquals(
                List.of(
                        new VerseRangeParser.Range(100, 108),
                        new VerseRangeParser.Range(124, 124)),
                ScopeRelativeLinkParser.resolveBookRelative("1-2,3:16", chapters));
    }

    @Test
    void bookRejectsMissingChapterOrVerseOob() {
        List<ChapterInfo> chapters = List.of(new ChapterInfo(1, 100, 5));
        assertNull(ScopeRelativeLinkParser.resolveBookRelative("2", chapters));
        assertNull(ScopeRelativeLinkParser.resolveBookRelative("1:9", chapters));
        assertNull(ScopeRelativeLinkParser.resolveBookRelative("John 3:16", chapters));
    }

    @Test
    void serializeMatchesPortableForm() {
        List<VerseRangeParser.Range> ranges =
                ScopeRelativeLinkParser.resolveChapterRelative("1-11,15", 1000, 27);
        assertEquals("[v=1000-1010,1014]", VerseRangeParser.serializeVToken(ranges));
    }
}
