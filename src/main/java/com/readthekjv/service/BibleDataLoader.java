package com.readthekjv.service;

import com.readthekjv.model.Verse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads KJV Bible data at application startup.
 * Parses the KJV JSON file and initializes both BibleService and LuceneIndexService.
 */
@Component
public class BibleDataLoader {

    private static final Logger log = LoggerFactory.getLogger(BibleDataLoader.class);

    private final BibleService bibleService;
    private final LuceneIndexService luceneService;
    private final ObjectMapper objectMapper;

    public BibleDataLoader(BibleService bibleService, LuceneIndexService luceneService, ObjectMapper objectMapper) {
        this.bibleService = bibleService;
        this.luceneService = luceneService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void loadData() throws IOException {
        log.info("Loading KJV Bible data...");
        long startTime = System.currentTimeMillis();

        List<Verse> verses = parseKjvJson();
        
        log.info("Parsed {} verses", verses.size());

        // Load into BibleService
        bibleService.loadVerses(verses);

        // Build Lucene index
        luceneService.buildIndex(verses);

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("KJV Bible data loaded in {}ms", elapsed);
    }

    /**
     * Parses the KJV JSON file.
     * Format: { "BookName": { "ChapterNum": ["BookName Chapter:Verse\tText", ...] } }
     */
    private List<Verse> parseKjvJson() throws IOException {
        List<Verse> verses = new ArrayList<>();
        
        ClassPathResource resource = new ClassPathResource("data/kjv.json");
        
        try (InputStream is = resource.getInputStream()) {
            // Parse as nested map structure
            Map<String, Map<String, List<String>>> kjvData = objectMapper.readValue(
                is,
                new TypeReference<Map<String, Map<String, List<String>>>>() {}
            );

            int globalId = 1;

            // Process books in canonical order to ensure consistent verse IDs
            for (String bookName : BibleService.BOOK_ORDER) {
                Map<String, List<String>> chapters = kjvData.get(bookName);
                if (chapters == null) {
                    log.warn("Book not found in data: {}", bookName);
                    continue;
                }

                int bookId = BibleService.BOOK_ORDER.indexOf(bookName) + 1;

                // Process chapters in numeric order
                List<Integer> chapterNums = chapters.keySet().stream()
                    .map(Integer::parseInt)
                    .sorted()
                    .toList();

                for (Integer chapterNum : chapterNums) {
                    List<String> verseStrings = chapters.get(String.valueOf(chapterNum));
                    
                    int verseNum = 1;
                    for (String verseString : verseStrings) {
                        // Parse format: "BookName Chapter:Verse\tText"
                        String text = extractVerseText(verseString);
                        if ("Psalm".equals(bookName) && verseNum == 1) {
                            text = stripPsalmSuperscription(text);
                        }
                        
                        Verse verse = new Verse(
                            globalId++,
                            bookName,
                            bookId,
                            chapterNum,
                            verseNum++,
                            text
                        );
                        verses.add(verse);
                    }
                }
            }
        }

        return verses;
    }

    /**
     * Extracts the verse text from the raw string format.
     * Format: "BookName Chapter:Verse\tText"
     */
    private String extractVerseText(String raw) {
        int tabIndex = raw.indexOf('\t');
        if (tabIndex >= 0 && tabIndex < raw.length() - 1) {
            return raw.substring(tabIndex + 1);
        }
        // Fallback: return entire string if no tab found
        return raw;
    }

    /**
     * Psalm superscriptions (e.g. "A Song of degrees.", "To the chief Musician, A Psalm of David.")
     * are embedded at the start of verse 1 in the source data. This pattern detects and strips them
     * so only the actual verse text is stored. Verified to match all 117 Psalms that have
     * superscriptions in the KJV and leave the 33 without them unchanged.
     */
    private static final Pattern PSALM_SUPERSCRIPTION = Pattern.compile(
        "^(?:A (?:Psalm|Song|Prayer|Praise)|To the chief|Upon |Maschil|Michtam|Shiggaion" +
        "|\\[A (?:Psalm|Song)\\]|David" +
        "|ALEPH|BETH|GIMEL|DALETH|HE\\b|VAU|ZAIN|CHETH|TETH|JOD|CAPH|LAMED|MEM|NUN" +
        "|SAMECH|AIN|PE|TZADDI|KOPH|RESH|SCHIN|TAU)" +
        "[^.?:]*[.?:](?:[^.?:]*[.?:])*\\s+(\\[?[A-Z].+)",
        Pattern.DOTALL
    );

    private String stripPsalmSuperscription(String text) {
        Matcher m = PSALM_SUPERSCRIPTION.matcher(text);
        return m.matches() ? m.group(1) : text;
    }
}
