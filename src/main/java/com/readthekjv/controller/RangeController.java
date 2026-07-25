package com.readthekjv.controller;

import com.readthekjv.exception.BadRequestException;
import com.readthekjv.model.Verse;
import com.readthekjv.model.dto.CollectionReadResponse;
import com.readthekjv.model.dto.RangeReadResponse;
import com.readthekjv.service.BibleService;
import com.readthekjv.service.PassageService;
import com.readthekjv.util.VerseRangeParser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Public range hydration for portable {@code [v=…]} note links and
 * {@code /read/range?v=…} deep links. No Passage row required.
 */
@RestController
@RequestMapping("/api/ranges")
public class RangeController {

    /** Same budget as collections — public unauthenticated hydration must stay bounded. */
    static final int MAX_RANGE_VERSES = 500;

    private final BibleService bibleService;

    public RangeController(BibleService bibleService) {
        this.bibleService = bibleService;
    }

    /**
     * @param v range body or full token, e.g. {@code 26136-26138,26140} or {@code [v=26136]}
     */
    @GetMapping
    public RangeReadResponse get(@RequestParam String v) {
        List<VerseRangeParser.Range> ranges;
        try {
            ranges = VerseRangeParser.parseVToken(v.contains("=") || v.startsWith("[") ? v : "v=" + v);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid verse range: " + e.getMessage());
        }
        int verseCount = 0;
        for (VerseRangeParser.Range r : ranges) {
            verseCount += r.to() - r.from() + 1;
            if (verseCount > MAX_RANGE_VERSES) {
                throw new BadRequestException(
                        "Range exceeds the " + MAX_RANGE_VERSES + "-verse limit");
            }
        }
        String body = VerseRangeParser.serializeRangeBody(ranges);
        String naturalKey = VerseRangeParser.naturalKeyFromRanges(ranges);
        List<CollectionReadResponse.CollectionVerse> verses = new ArrayList<>();
        for (int id : VerseRangeParser.expandVerseIds(ranges)) {
            Verse verse = bibleService.getVerse(id)
                    .orElseThrow(() -> new BadRequestException("Invalid verse id: " + id));
            verses.add(CollectionReadResponse.CollectionVerse.from(verse));
        }
        String reference = PassageService.formatReference(verses);
        return new RangeReadResponse(body, reference, naturalKey, verses);
    }
}
