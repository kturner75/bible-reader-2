package com.readthekjv.service;

import com.readthekjv.model.Verse;
import com.readthekjv.util.VerseRangeParser;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the portable {@code [v=…]} / {@code [e=…]} tokens in a note body to the
 * distinct {@code (bookId, chapter)} pairs the note references.
 *
 * Book+chapter, not verse: that is the level a reader browses at, it keeps one row per
 * chapter rather than per verse, and the body stays the authority for the exact range.
 *
 * Malformed tokens are skipped rather than rejected — this is a derived index, and a body
 * that fails to parse here is a body the renderer will also decline to linkify. Refusing
 * the save is {@link NoteEmbedCap}'s job, not this one's.
 */
@Component
public class NoteScriptureIndexer {

    /** Both render modes; the range grammar behind them is identical. */
    private static final Pattern LINK_TOKEN =
            Pattern.compile("\\[[veVE]=([^\\]]+)\\]");

    private final BibleService bibleService;

    public NoteScriptureIndexer(BibleService bibleService) {
        this.bibleService = bibleService;
    }

    /** One referenced chapter. Ordered by book then chapter so chips render in Bible order. */
    public record BookChapter(int bookId, int chapter) implements Comparable<BookChapter> {
        @Override
        public int compareTo(BookChapter o) {
            return bookId != o.bookId
                    ? Integer.compare(bookId, o.bookId)
                    : Integer.compare(chapter, o.chapter);
        }
    }

    /**
     * @return referenced chapters in Bible order; empty when the body cites nothing
     */
    public Set<BookChapter> extract(String body) {
        if (body == null || body.isEmpty()) {
            return Set.of();
        }

        List<VerseRangeParser.Range> all = new ArrayList<>();
        Matcher m = LINK_TOKEN.matcher(body);
        while (m.find()) {
            try {
                all.addAll(VerseRangeParser.parseVToken(m.group(0)));
            } catch (IllegalArgumentException ignored) {
                // Not a resolvable pointer — the renderer will leave it as literal text too.
            }
        }
        if (all.isEmpty()) {
            return Set.of();
        }

        // Merging the union first bounds the walk below at the whole canon (31,102 ids)
        // no matter how many overlapping tokens a 20,000-char body packs in.
        List<VerseRangeParser.Range> merged;
        try {
            merged = VerseRangeParser.normalizeRanges(all);
        } catch (IllegalArgumentException e) {
            return Set.of();
        }

        Set<BookChapter> chapters = new TreeSet<>();
        for (VerseRangeParser.Range r : merged) {
            for (int id = r.from(); id <= r.to(); id++) {
                Verse v = bibleService.getVerse(id).orElse(null);
                if (v == null) {
                    continue;
                }
                chapters.add(new BookChapter(v.bookId(), v.chapter()));
                // Skip the rest of this chapter: the next id worth looking at is one past
                // the chapter's last verse. Turns a whole-book range into a walk per
                // chapter instead of per verse.
                int lastOfChapter = lastVerseIdOfChapter(v);
                if (lastOfChapter > id) {
                    id = lastOfChapter;
                }
            }
        }
        return new LinkedHashSet<>(chapters);
    }

    /**
     * Global id of the final verse in {@code v}'s chapter. Falls back to {@code v}'s own id
     * when the chapter cannot be resolved, which degrades the skip to a plain step.
     */
    private int lastVerseIdOfChapter(Verse v) {
        return bibleService.getChapters(v.bookId()).stream()
                .filter(c -> c.chapter() == v.chapter())
                .findFirst()
                .map(c -> c.firstVerseId() + c.verseCount() - 1)
                .orElse(v.id());
    }
}
