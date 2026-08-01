package com.readthekjv.util;

import com.readthekjv.model.ChapterInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scope-relative scripture tokens typed in notes (input UX only).
 * <p>
 * Chapter / verse-note scope (current chapter):
 * <ul>
 *   <li>{@code 12} — single verse</li>
 *   <li>{@code 1-11} — inclusive verse range</li>
 *   <li>{@code 1,5,7} — discrete verses</li>
 *   <li>{@code 1-11,15} — mix</li>
 * </ul>
 * Book-note scope:
 * <ul>
 *   <li>{@code 12} / {@code 1-11} — whole chapter(s)</li>
 *   <li>{@code 3:16} / {@code 3:1-11} — verse / verse range in a chapter</li>
 *   <li>{@code 3:1-11,15} / {@code 1-2,3:16} — mixed segments</li>
 * </ul>
 * Invalid or out-of-bounds tokens return {@code null} (callers leave the original text).
 */
public final class ScopeRelativeLinkParser {

    /** Match {@code RangeController.MAX_RANGE_VERSES} — focused reader cannot open larger. */
    public static final int MAX_RANGE_VERSES = 500;

    /** Inclusive integer span (verse numbers or chapter numbers). */
    public record Span(int from, int to) {
        public Span {
            if (from > to) {
                int tmp = from;
                from = to;
                to = tmp;
            }
        }
    }

    /**
     * One segment of a book-relative token.
     *
     * @param chapterFrom first chapter (inclusive)
     * @param chapterTo   last chapter (inclusive); equals chapterFrom for verse spans
     * @param verseFrom   first verse, or {@code null} when the span is whole chapter(s)
     * @param verseTo     last verse, or {@code null} when the span is whole chapter(s)
     */
    public record BookSegment(int chapterFrom, int chapterTo, Integer verseFrom, Integer verseTo) {
        public boolean isChapterSpan() {
            return verseFrom == null;
        }
    }

    // 1 | 1-11 | 1,5,7 | 1-11, 15
    private static final Pattern NUMBER_LIST =
            Pattern.compile("^\\d+(?:\\s*-\\s*\\d+)?(?:\\s*,\\s*\\d+(?:\\s*-\\s*\\d+)?)*$");

    // Book segments: N | N-M | N:V | N:V-W  (comma-separated)
    private static final Pattern BOOK_TOKEN =
            Pattern.compile(
                    "^(?:\\d+(?:\\s*-\\s*\\d+)?|\\d+\\s*:\\s*\\d+(?:\\s*-\\s*\\d+)?)"
                            + "(?:\\s*,\\s*(?:\\d+(?:\\s*-\\s*\\d+)?|\\d+\\s*:\\s*\\d+(?:\\s*-\\s*\\d+)?))*$");

    private static final Pattern BOOK_SEGMENT =
            Pattern.compile(
                    "^(\\d+)(?:\\s*-\\s*(\\d+)|\\s*:\\s*(\\d+)(?:\\s*-\\s*(\\d+))?)?$");

    private ScopeRelativeLinkParser() {}

    /**
     * Parses a pure numeric list into 1-based spans (verse or chapter numbers).
     *
     * @return spans, or {@code null} if {@code inner} is not a valid number list
     */
    public static List<Span> parseNumberList(String inner) {
        if (inner == null) {
            return null;
        }
        String s = inner.trim();
        if (s.isEmpty() || !NUMBER_LIST.matcher(s).matches()) {
            return null;
        }
        List<Span> spans = new ArrayList<>();
        try {
            for (String part : s.split(",")) {
                String p = part.trim();
                if (p.contains("-")) {
                    String[] bounds = p.split("-", 2);
                    int a = Integer.parseInt(bounds[0].trim());
                    int b = Integer.parseInt(bounds[1].trim());
                    if (a < 1 || b < 1) {
                        return null;
                    }
                    spans.add(new Span(a, b));
                } else {
                    int v = Integer.parseInt(p);
                    if (v < 1) {
                        return null;
                    }
                    spans.add(new Span(v, v));
                }
            }
        } catch (NumberFormatException e) {
            // Digit strings larger than Integer.MAX_VALUE (e.g. 999999999999)
            return null;
        }
        return spans.isEmpty() ? null : spans;
    }

    /**
     * Chapter-relative token → global verse-id ranges.
     *
     * @return normalized ranges, or {@code null} if not chapter-relative / out of bounds
     */
    public static List<VerseRangeParser.Range> resolveChapterRelative(
            String inner, int firstVerseId, int verseCount) {
        if (firstVerseId < 1 || verseCount < 1) {
            return null;
        }
        List<Span> spans = parseNumberList(inner);
        if (spans == null) {
            return null;
        }
        List<VerseRangeParser.Range> ranges = new ArrayList<>(spans.size());
        for (Span span : spans) {
            if (span.from() < 1 || span.to() > verseCount) {
                return null;
            }
            ranges.add(new VerseRangeParser.Range(
                    firstVerseId + span.from() - 1,
                    firstVerseId + span.to() - 1));
        }
        return capToReaderLimit(VerseRangeParser.normalizeRanges(ranges));
    }

    /**
     * Parses a book-relative token into chapter and/or chapter:verse segments.
     *
     * @return segments, or {@code null} if the token does not match book-relative grammar
     */
    public static List<BookSegment> parseBookRelative(String inner) {
        if (inner == null) {
            return null;
        }
        String s = inner.trim();
        if (s.isEmpty() || !BOOK_TOKEN.matcher(s).matches()) {
            return null;
        }
        List<BookSegment> segs = new ArrayList<>();
        for (String part : s.split(",")) {
            String p = part.trim();
            Matcher m = BOOK_SEGMENT.matcher(p);
            if (!m.matches()) {
                return null;
            }
            int a = Integer.parseInt(m.group(1));
            if (a < 1) {
                return null;
            }
            if (m.group(3) != null) {
                // N:V or N:V-W
                int v1 = Integer.parseInt(m.group(3));
                int v2 = m.group(4) != null ? Integer.parseInt(m.group(4)) : v1;
                if (v1 < 1 || v2 < 1) {
                    return null;
                }
                int fromV = Math.min(v1, v2);
                int toV = Math.max(v1, v2);
                segs.add(new BookSegment(a, a, fromV, toV));
            } else if (m.group(2) != null) {
                // N-M chapter range
                int b = Integer.parseInt(m.group(2));
                if (b < 1) {
                    return null;
                }
                segs.add(new BookSegment(Math.min(a, b), Math.max(a, b), null, null));
            } else {
                // single chapter
                segs.add(new BookSegment(a, a, null, null));
            }
        }
        return segs.isEmpty() ? null : segs;
    }

    /**
     * Book-relative token → global verse-id ranges using chapter metadata.
     *
     * @return normalized ranges, or {@code null} if invalid / out of bounds
     */
    public static List<VerseRangeParser.Range> resolveBookRelative(
            String inner, List<ChapterInfo> chapters) {
        List<BookSegment> segs = parseBookRelative(inner);
        if (segs == null || chapters == null || chapters.isEmpty()) {
            return null;
        }
        Map<Integer, ChapterInfo> byNum = new HashMap<>();
        for (ChapterInfo ch : chapters) {
            byNum.put(ch.chapter(), ch);
        }
        List<VerseRangeParser.Range> ranges = new ArrayList<>();
        for (BookSegment seg : segs) {
            if (seg.isChapterSpan()) {
                for (int c = seg.chapterFrom(); c <= seg.chapterTo(); c++) {
                    ChapterInfo ch = byNum.get(c);
                    if (ch == null || ch.verseCount() < 1) {
                        return null;
                    }
                    ranges.add(new VerseRangeParser.Range(
                            ch.firstVerseId(),
                            ch.firstVerseId() + ch.verseCount() - 1));
                }
            } else {
                ChapterInfo ch = byNum.get(seg.chapterFrom());
                if (ch == null) {
                    return null;
                }
                int fromV = seg.verseFrom();
                int toV = seg.verseTo();
                if (fromV < 1 || toV > ch.verseCount()) {
                    return null;
                }
                ranges.add(new VerseRangeParser.Range(
                        ch.firstVerseId() + fromV - 1,
                        ch.firstVerseId() + toV - 1));
            }
        }
        return capToReaderLimit(VerseRangeParser.normalizeRanges(ranges));
    }

    /**
     * Reject ranges the focused reader cannot open ({@link #MAX_RANGE_VERSES}).
     *
     * @return {@code ranges} if within budget, else {@code null}
     */
    static List<VerseRangeParser.Range> capToReaderLimit(List<VerseRangeParser.Range> ranges) {
        if (ranges == null || ranges.isEmpty()) {
            return null;
        }
        int count = 0;
        for (VerseRangeParser.Range r : ranges) {
            count += r.length();
            if (count > MAX_RANGE_VERSES) {
                return null;
            }
        }
        return ranges;
    }

    /** True if {@code inner} matches chapter-relative number-list grammar. */
    public static boolean isChapterRelativeToken(String inner) {
        return parseNumberList(inner) != null;
    }

    /** True if {@code inner} matches book-relative grammar. */
    public static boolean isBookRelativeToken(String inner) {
        return parseBookRelative(inner) != null;
    }
}
