package com.readthekjv.controller;

import com.readthekjv.exception.BadRequestException;
import com.readthekjv.model.ChapterInfo;
import com.readthekjv.model.Verse;
import com.readthekjv.model.dto.CollectionReadResponse;
import com.readthekjv.model.dto.PassageContextResponse;
import com.readthekjv.model.dto.PassageContextResponse.ChapterContext;
import com.readthekjv.model.dto.RangeReadResponse;
import com.readthekjv.model.dto.VerseSnippet;
import com.readthekjv.service.BibleService;
import com.readthekjv.service.PassageService;
import com.readthekjv.util.VerseRangeParser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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

    /**
     * Prev/current/next chapter verses for Insert Scripture range expand.
     * Public so anonymous localStorage verse-note users can insert {@code [v=…]}.
     * Chapter boundaries stay within the same book.
     */
    @GetMapping("/context/{verseId}")
    public PassageContextResponse getSurroundingContext(@PathVariable int verseId) {
        Verse verse = bibleService.getVerse(verseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Verse not found"));

        List<ChapterInfo> chapters = bibleService.getChapters(verse.bookId());
        int currentChapterNum = verse.chapter();

        int currentIdx = -1;
        for (int i = 0; i < chapters.size(); i++) {
            if (chapters.get(i).chapter() == currentChapterNum) {
                currentIdx = i;
                break;
            }
        }
        if (currentIdx < 0) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Chapter not found");
        }

        ChapterContext prevChapter = currentIdx > 0
                ? buildChapterContext(verse.book(), verse.bookId(), chapters.get(currentIdx - 1))
                : null;
        ChapterContext currentChapter =
                buildChapterContext(verse.book(), verse.bookId(), chapters.get(currentIdx));
        ChapterContext nextChapter = currentIdx < chapters.size() - 1
                ? buildChapterContext(verse.book(), verse.bookId(), chapters.get(currentIdx + 1))
                : null;

        return new PassageContextResponse(prevChapter, currentChapter, nextChapter);
    }

    private ChapterContext buildChapterContext(String bookName, int bookId, ChapterInfo ci) {
        List<VerseSnippet> verses = bibleService.getVerses(ci.firstVerseId(), ci.verseCount())
                .stream()
                .map(v -> new VerseSnippet(v.id(), v.verse(), v.reference(), v.text()))
                .toList();
        return new ChapterContext(bookId, bookName, ci.chapter(), verses);
    }
}
