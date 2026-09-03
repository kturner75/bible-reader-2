package com.readthekjv.service;

import com.readthekjv.model.ChapterInfo;
import com.readthekjv.model.Verse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * A two-chapter synthetic book stands in for the canon: ids 1–5 are chapter 1,
 * ids 6–10 are chapter 2. Enough to exercise range-to-chapter resolution without
 * loading kjv.json.
 */
class NoteScriptureIndexerTest {

    private NoteScriptureIndexer indexer;

    @BeforeEach
    void setUp() {
        BibleService bible = mock(BibleService.class);
        when(bible.getVerse(anyInt())).thenAnswer(inv -> {
            int id = inv.getArgument(0);
            if (id < 1 || id > 10) return Optional.empty();
            int chapter = id <= 5 ? 1 : 2;
            int verse = id <= 5 ? id : id - 5;
            return Optional.of(new Verse(id, "Testament", 1, chapter, verse, "text"));
        });
        when(bible.getChapters(1)).thenReturn(List.of(
                new ChapterInfo(1, 1, 5),
                new ChapterInfo(2, 6, 5)));
        indexer = new NoteScriptureIndexer(bible);
    }

    private static NoteScriptureIndexer.BookChapter bc(int book, int chapter) {
        return new NoteScriptureIndexer.BookChapter(book, chapter);
    }

    @Test
    void bodyWithoutTokensCitesNothing() {
        assertEquals(Set.of(), indexer.extract("Three movements: verdict, Spirit, inheritance."));
    }

    @Test
    void nullAndEmptyBodiesAreSafe() {
        assertEquals(Set.of(), indexer.extract(null));
        assertEquals(Set.of(), indexer.extract(""));
    }

    @Test
    void singleVerseTokenResolvesToItsChapter() {
        assertEquals(Set.of(bc(1, 1)), indexer.extract("See [v=3] for the point."));
    }

    @Test
    void rangeSpanningTwoChaptersYieldsBoth() {
        assertEquals(Set.of(bc(1, 1), bc(1, 2)), indexer.extract("[v=4-7]"));
    }

    @Test
    void embedTokensCountTheSameAsLinkTokens() {
        assertEquals(Set.of(bc(1, 2)), indexer.extract("[e=6-8]"));
    }

    @Test
    void overlappingTokensCollapseToDistinctChapters() {
        Set<NoteScriptureIndexer.BookChapter> found =
                indexer.extract("[v=1-5] then [e=3] and again [v=2-4]");
        assertEquals(Set.of(bc(1, 1)), found);
    }

    @Test
    void malformedTokensAreSkippedNotThrown() {
        assertEquals(Set.of(bc(1, 1)), indexer.extract("[v=abc] but [v=3] parses"));
        assertEquals(Set.of(), indexer.extract("[v=] and [e=99-]"));
    }

    @Test
    void outOfCanonIdsAreRejectedByTheParserAndYieldNothing() {
        // 40000 is past MAX_VERSE_ID, so the whole token fails to parse.
        assertEquals(Set.of(), indexer.extract("[v=40000]"));
    }

    @Test
    void chaptersComeBackInBibleOrder() {
        List<NoteScriptureIndexer.BookChapter> ordered = List.copyOf(indexer.extract("[v=8] and [v=2]"));
        assertEquals(List.of(bc(1, 1), bc(1, 2)), ordered);
    }
}
