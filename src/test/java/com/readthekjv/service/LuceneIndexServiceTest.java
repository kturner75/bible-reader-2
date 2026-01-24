package com.readthekjv.service;

import com.readthekjv.model.SearchResult;
import com.readthekjv.model.Verse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LuceneIndexServiceTest {

    private LuceneIndexService service;

    @BeforeEach
    void setUp() throws IOException {
        service = new LuceneIndexService();

        // Create test verses (id, book, bookId, chapter, verse, text)
        List<Verse> testVerses = List.of(
            new Verse(1, "Ecclesiastes", 21, 1, 2, "Vanity of vanities, saith the Preacher, vanity of vanities; all is vanity."),
            new Verse(2, "Ecclesiastes", 21, 1, 14, "I have seen all the works that are done under the sun; and, behold, all is vanity and vexation of spirit."),
            new Verse(3, "Psalm", 19, 39, 5, "Behold, thou hast made my days as an handbreadth; and mine age is as nothing before thee: verily every man at his best state is altogether vanity."),
            new Verse(4, "John", 43, 3, 16, "For God so loved the world, that he gave his only begotten Son, that whosoever believeth in him should not perish, but have everlasting life."),
            new Verse(5, "Romans", 45, 8, 28, "And we know that all things work together for good to them that love God, to them who are the called according to his purpose.")
        );

        service.buildIndex(testVerses);
    }

    @Test
    void testSearchWithPipeOrSyntax() {
        // Search using pipe syntax: should find verses with "vanity" OR "vanities"
        SearchResult result = service.search("vanity | vanities", 50);

        assertNotNull(result);
        assertTrue(result.count() >= 3, "Should find at least 3 verses with vanity or vanities");
    }

    @Test
    void testSearchWithOrKeyword() {
        // Search using OR keyword: should find same results
        SearchResult result = service.search("vanity OR vanities", 50);

        assertNotNull(result);
        assertTrue(result.count() >= 3, "Should find at least 3 verses with vanity or vanities");
    }

    @Test
    void testSearchWithPipeNoSpaces() {
        // Search using pipe without spaces should also work
        SearchResult result = service.search("vanity|vanities", 50);

        assertNotNull(result);
        assertTrue(result.count() >= 3, "Should find at least 3 verses with vanity or vanities");
    }

    @Test
    void testSearchOrWithDifferentTerms() {
        // Search for "love | vexation" - should find verses with either word
        SearchResult result = service.search("love | vexation", 50);

        assertNotNull(result);
        // Should find Romans 8:28 (love) and Ecclesiastes 1:14 (vexation)
        // Note: "loved" in John 3:16 may not match "love" depending on analyzer
        assertTrue(result.count() >= 2, "Should find verses with love or vexation");
    }

    @Test
    void testSearchAndStillWorks() {
        // Default AND behavior should still work
        SearchResult result = service.search("vanity vexation", 50);

        assertNotNull(result);
        // Only Ecclesiastes 1:14 has both "vanity" AND "vexation"
        assertEquals(1, result.count(), "Should find exactly 1 verse with both vanity and vexation");
    }

    @Test
    void testSearchPhraseStillWorks() {
        // Phrase search should still work
        SearchResult result = service.search("\"all is vanity\"", 50);

        assertNotNull(result);
        assertTrue(result.count() >= 1, "Should find verses with exact phrase 'all is vanity'");
    }

    @Test
    void testMultipleOrTerms() {
        // Multiple OR terms: vanity | love | begotten
        SearchResult result = service.search("vanity | love | begotten", 50);

        assertNotNull(result);
        // Should find all verses except possibly none
        assertTrue(result.count() >= 4, "Should find verses with any of the three terms");
    }
}
