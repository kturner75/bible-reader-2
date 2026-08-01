package com.readthekjv.controller;

import com.readthekjv.model.*;
import com.readthekjv.service.BibleService;
import com.readthekjv.service.LuceneIndexService;
import com.readthekjv.util.ReferenceParser;
import com.readthekjv.util.VerseRangeParser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST controller for Bible API endpoints.
 */
@RestController
@RequestMapping("/api")
public class BibleController {

    private final BibleService bibleService;
    private final LuceneIndexService luceneService;

    public BibleController(BibleService bibleService, LuceneIndexService luceneService) {
        this.bibleService = bibleService;
        this.luceneService = luceneService;
    }

    /**
     * Get a range of verses starting from a given ID.
     * 
     * @param from Starting verse ID (default: 1)
     * @param count Number of verses to return (default: 50, max: 200)
     * @return List of verses
     */
    @GetMapping("/verses")
    public ResponseEntity<Map<String, Object>> getVerses(
            @RequestParam(defaultValue = "1") int from,
            @RequestParam(defaultValue = "50") int count) {
        
        // Clamp count to reasonable limits
        count = Math.min(Math.max(count, 1), 200);
        from = Math.max(from, 1);
        
        List<Verse> verses = bibleService.getVerses(from, count);
        
        return ResponseEntity.ok(Map.of(
            "verses", verses,
            "from", from,
            "count", verses.size(),
            "total", bibleService.getTotalVerses()
        ));
    }

    /**
     * Get a single verse by ID.
     * 
     * @param id Verse ID (1-31102)
     * @return The verse or 404
     */
    @GetMapping("/verses/{id}")
    public ResponseEntity<Verse> getVerse(@PathVariable int id) {
        return bibleService.getVerse(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get all books with metadata.
     * 
     * @return List of all 66 books
     */
    @GetMapping("/books")
    public ResponseEntity<List<Book>> getBooks() {
        return ResponseEntity.ok(bibleService.getBooks());
    }

    /**
     * Get a specific book by ID.
     * 
     * @param id Book ID (1-66)
     * @return The book or 404
     */
    @GetMapping("/books/{id}")
    public ResponseEntity<Book> getBook(@PathVariable int id) {
        return bibleService.getBook(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get chapters for a specific book.
     * 
     * @param id Book ID (1-66)
     * @return List of chapter info
     */
    @GetMapping("/books/{id}/chapters")
    public ResponseEntity<List<ChapterInfo>> getChapters(@PathVariable int id) {
        List<ChapterInfo> chapters = bibleService.getChapters(id);
        if (chapters.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(chapters);
    }

    /**
     * Full-text search across all verses.
     * 
     * @param q Search query string
     * @param limit Maximum results (default: 50)
     * @return Search results with matching verses
     */
    @GetMapping("/search")
    public ResponseEntity<SearchResult> search(
            @RequestParam String q,
            @RequestParam(defaultValue = "50") int limit) {
        
        if (q == null || q.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        
        limit = Math.min(Math.max(limit, 1), 100);
        
        SearchResult result = luceneService.search(q.trim(), limit);
        return ResponseEntity.ok(result);
    }

    /**
     * Full-text search returning verse ids only (for Matching Passages overlap).
     * Default/max cover the entire KJV (31,102 verses) so overlap is complete.
     *
     * @param q Search query string
     * @param limit Maximum ids (default/max: 32000)
     */
    @GetMapping("/search/ids")
    public ResponseEntity<SearchIdsResult> searchIds(
            @RequestParam String q,
            @RequestParam(defaultValue = "32000") int limit) {

        if (q == null || q.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        limit = Math.min(Math.max(limit, 1), 32000);
        return ResponseEntity.ok(luceneService.searchIds(q.trim(), limit));
    }

    /**
     * Parse a Bible reference and return the verse ID.
     * Supports formats: "John 3:16", "ps 23", "gen1:1", etc.
     * Same-chapter multi-verse: "John 3:16-18", "John 3:1-11,15" → ranges + {@code v} body.
     *
     * @param ref Reference string
     * @return Verse ID (and ranges for multi-verse) or valid:false if not found
     */
    @GetMapping("/reference")
    public ResponseEntity<Map<String, Object>> parseReference(@RequestParam String ref) {
        if (ref == null || ref.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        String trimmed = ref.trim();

        // Same-chapter absolute multi-verse (range and/or comma list)
        if (ReferenceParser.looksLikeAbsoluteMultiVerse(trimmed)) {
            ReferenceParser.ParsedAbsoluteLink link = ReferenceParser.parseAbsoluteLink(trimmed);
            Optional<List<VerseRangeParser.Range>> resolved = bibleService.resolveAbsoluteLink(link);
            if (resolved.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                    "valid", false,
                    "input", ref
                ));
            }
            List<VerseRangeParser.Range> ranges = resolved.get();
            String vBody = VerseRangeParser.serializeRangeBody(ranges);
            int firstId = ranges.get(0).from();
            List<Map<String, Integer>> rangeMaps = new ArrayList<>();
            for (VerseRangeParser.Range r : ranges) {
                rangeMaps.add(Map.of("from", r.from(), "to", r.to()));
            }
            Map<String, Object> body = new HashMap<>();
            body.put("valid", true);
            body.put("input", ref);
            body.put("verseId", firstId);
            body.put("verseSpecified", true);
            body.put("v", vBody);
            body.put("ranges", rangeMaps);
            bibleService.getVerse(firstId).ifPresent(v -> body.put("verse", v));
            return ResponseEntity.ok(body);
        }

        ReferenceParser.ParsedReference parsed = ReferenceParser.parse(trimmed);
        if (parsed == null) {
            return ResponseEntity.ok(Map.of(
                "valid", false,
                "input", ref
            ));
        }

        Optional<Integer> verseId = bibleService.resolveReference(parsed);

        if (verseId.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                "valid", false,
                "input", ref,
                "parsed", Map.of(
                    "book", parsed.book(),
                    "chapter", parsed.chapter(),
                    "verse", parsed.verse() != null ? parsed.verse() : 1
                )
            ));
        }

        Optional<Verse> verse = bibleService.getVerse(verseId.get());
        Map<String, Object> body = new HashMap<>();
        body.put("valid", true);
        body.put("input", ref);
        body.put("verseId", verseId.get());
        body.put("verse", verse.orElse(null));
        // false when input was chapter/book-scoped (e.g. "ps 24") so clients can
        // expand Matching Passages overlap to the whole chapter.
        body.put("verseSpecified", parsed.verse() != null);
        return ResponseEntity.ok(body);
    }

    /**
     * Navigation helper: get next/previous chapter or book verse IDs.
     * 
     * @param currentId Current verse ID
     * @return Navigation targets
     */
    @GetMapping("/navigate/{currentId}")
    public ResponseEntity<Map<String, Object>> getNavigation(@PathVariable int currentId) {
        Optional<Verse> current = bibleService.getVerse(currentId);
        if (current.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // Use HashMap instead of Map.of() because Map.of() doesn't allow null values
        Map<String, Object> result = new HashMap<>();
        result.put("current", current.get());
        bibleService.getNextChapter(currentId).ifPresent(v -> result.put("nextChapter", v));
        bibleService.getPreviousChapter(currentId).ifPresent(v -> result.put("prevChapter", v));
        bibleService.getNextBook(currentId).ifPresent(v -> result.put("nextBook", v));
        bibleService.getPreviousBook(currentId).ifPresent(v -> result.put("prevBook", v));
        
        return ResponseEntity.ok(result);
    }

    /**
     * Get total verse count and other stats.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(Map.of(
            "totalVerses", bibleService.getTotalVerses(),
            "totalBooks", bibleService.getBooks().size()
        ));
    }
}
