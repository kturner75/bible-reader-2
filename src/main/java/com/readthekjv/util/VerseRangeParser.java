package com.readthekjv.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Portable verse-range links for note bodies and focused reading.
 *
 * Canonical stored form: {@code [v=26136]}, {@code [v=26136-26138]},
 * {@code [v=26136-26138,26140]}. Embed-quoted twins use the same range
 * grammar with an {@code e=} prefix: {@code [e=14625]}, {@code [e=14625-14627]}.
 *
 * Normalization sorts segments, swaps reversed bounds, merges overlaps/adjacents,
 * and validates ids in {@code 1…31102}. Equality of two pointers is equality of
 * normalized range lists.
 *
 * Natural keys use {@code :} for ranges; v-tokens use {@code -}. Both describe
 * the same segment list after conversion.
 */
public final class VerseRangeParser {

    public static final int MIN_VERSE_ID = 1;
    public static final int MAX_VERSE_ID = 31102;

    /** First-cut write-side cap for {@code [e=…]} — refuse, do not truncate. */
    public static final int EMBED_VERSE_CAP = 12;

    /** Inclusive verse id range. */
    public record Range(int from, int to) {
        public Range {
            if (from > to) {
                int tmp = from;
                from = to;
                to = tmp;
            }
        }

        public int length() {
            return to - from + 1;
        }
    }

    private static final Pattern TOKEN =
            Pattern.compile("^\\[?([ve])=([^\\]]+)\\]?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern INNER =
            Pattern.compile("^[ve]=(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMBED_TOKEN_IN_BODY =
            Pattern.compile("\\[e=([^\\]]+)\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSE_ID_DIGITS = Pattern.compile("\\d+");

    private VerseRangeParser() {}

    /** Entire token must be digits — {@code Integer.parseInt} would accept {@code 13junk}. */
    private static int parseVerseId(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (!VERSE_ID_DIGITS.matcher(s).matches()) {
            throw new IllegalArgumentException("Malformed verse id: " + raw);
        }
        return Integer.parseInt(s);
    }

    /**
     * Parses a full token like {@code [v=1-3,5]} / {@code [e=1-3,5]} or bare
     * {@code v=1-3,5} / {@code e=1-3,5} or just the body {@code 1-3,5}.
     * Prefix is a render-mode flag; the range grammar is the same.
     */
    public static List<Range> parseVToken(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("v-token must not be blank");
        }
        String s = raw.trim();
        Matcher bracketed = TOKEN.matcher(s);
        if (bracketed.matches()) {
            s = bracketed.group(2).trim();
        } else {
            Matcher inner = INNER.matcher(s);
            if (inner.matches()) {
                s = inner.group(1).trim();
            }
        }
        return parseRangeBody(s);
    }

    /** True when {@code raw} is an {@code [e=…]} / {@code e=…} token. */
    public static boolean isEmbedToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String s = raw.trim();
        Matcher bracketed = TOKEN.matcher(s);
        if (bracketed.matches()) {
            return bracketed.group(1).equalsIgnoreCase("e");
        }
        return s.length() >= 2
                && (s.charAt(0) == 'e' || s.charAt(0) == 'E')
                && s.charAt(1) == '=';
    }

    /** Parses the comma-separated body after {@code v=} (no brackets). */
    public static List<Range> parseRangeBody(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Range body must not be blank");
        }
        List<Range> ranges = new ArrayList<>();
        for (String part : body.split(",")) {
            part = part.trim();
            if (part.isEmpty()) {
                throw new IllegalArgumentException("Empty range segment");
            }
            if (part.contains("-")) {
                String[] bounds = part.split("-", 2);
                if (bounds.length != 2 || bounds[0].isBlank() || bounds[1].isBlank()) {
                    throw new IllegalArgumentException("Malformed range segment: " + part);
                }
                ranges.add(new Range(parseVerseId(bounds[0]), parseVerseId(bounds[1])));
            } else {
                int v = parseVerseId(part);
                ranges.add(new Range(v, v));
            }
        }
        return normalizeRanges(ranges);
    }

    /**
     * Sorts by from, merges overlapping or adjacent segments, validates bounds.
     */
    public static List<Range> normalizeRanges(List<Range> ranges) {
        if (ranges == null || ranges.isEmpty()) {
            throw new IllegalArgumentException("Range list must not be empty");
        }
        List<Range> sorted = new ArrayList<>(ranges.size());
        for (Range r : ranges) {
            if (r == null) {
                throw new IllegalArgumentException("Range must not be null");
            }
            int from = Math.min(r.from(), r.to());
            int to = Math.max(r.from(), r.to());
            if (from < MIN_VERSE_ID || to > MAX_VERSE_ID) {
                throw new IllegalArgumentException(
                        "Verse id out of bounds: " + from + "-" + to);
            }
            sorted.add(new Range(from, to));
        }
        sorted.sort(Comparator.comparingInt(Range::from).thenComparingInt(Range::to));

        List<Range> merged = new ArrayList<>();
        Range cur = sorted.get(0);
        for (int i = 1; i < sorted.size(); i++) {
            Range next = sorted.get(i);
            // Overlap or adjacent → merge
            if (next.from() <= cur.to() + 1) {
                cur = new Range(cur.from(), Math.max(cur.to(), next.to()));
            } else {
                merged.add(cur);
                cur = next;
            }
        }
        merged.add(cur);
        return List.copyOf(merged);
    }

    /** Canonical serialization without brackets: {@code 1-3,5}. */
    public static String serializeRangeBody(List<Range> ranges) {
        List<Range> n = normalizeRanges(ranges);
        StringBuilder sb = new StringBuilder();
        for (Range r : n) {
            if (sb.length() > 0) sb.append(',');
            if (r.from() == r.to()) sb.append(r.from());
            else sb.append(r.from()).append('-').append(r.to());
        }
        return sb.toString();
    }

    /** Canonical note token: {@code [v=1-3,5]}. */
    public static String serializeVToken(List<Range> ranges) {
        return "[v=" + serializeRangeBody(ranges) + "]";
    }

    /** Canonical embed token: {@code [e=1-3,5]}. Same ranges as {@link #serializeVToken}. */
    public static String serializeEToken(List<Range> ranges) {
        return "[e=" + serializeRangeBody(ranges) + "]";
    }

    public static boolean isValidVToken(String raw) {
        try {
            parseVToken(raw);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static List<Range> rangesFromNaturalKey(String naturalKey) {
        List<NaturalKeyParser.Segment> segs = NaturalKeyParser.parse(naturalKey);
        List<Range> ranges = new ArrayList<>(segs.size());
        for (NaturalKeyParser.Segment s : segs) {
            ranges.add(new Range(s.from(), s.to()));
        }
        return normalizeRanges(ranges);
    }

    /**
     * Builds a natural key from normalized ranges (sorted/merged).
     * Uses {@code :} between bounds to match {@link NaturalKeyParser}.
     */
    public static String naturalKeyFromRanges(List<Range> ranges) {
        List<Range> n = normalizeRanges(ranges);
        StringBuilder sb = new StringBuilder();
        for (Range r : n) {
            if (sb.length() > 0) sb.append(',');
            if (r.from() == r.to()) sb.append(r.from());
            else sb.append(r.from()).append(':').append(r.to());
        }
        return sb.toString();
    }

    /** Expand ranges to an ordered flat list of verse ids. */
    public static List<Integer> expandVerseIds(List<Range> ranges) {
        List<Range> n = normalizeRanges(ranges);
        List<Integer> ids = new ArrayList<>();
        for (Range r : n) {
            for (int id = r.from(); id <= r.to(); id++) {
                ids.add(id);
            }
        }
        return ids;
    }

    public static String embedCapMessage(int count) {
        return "Quoted scripture is limited to " + EMBED_VERSE_CAP
                + " verses (this reference is " + count + ").";
    }

    /**
     * Write-side refuse for any {@code [e=…]} in a note body over {@link #EMBED_VERSE_CAP}.
     * Does not truncate. Malformed tokens are skipped (render / other validators handle them).
     *
     * @throws IllegalArgumentException when a well-formed embed exceeds the cap
     */
    public static void requireNoteEmbedCap(String noteBody) {
        if (noteBody == null || noteBody.isEmpty()) {
            return;
        }
        Matcher m = EMBED_TOKEN_IN_BODY.matcher(noteBody);
        while (m.find()) {
            List<Range> ranges;
            try {
                ranges = parseVToken(m.group(0));
            } catch (IllegalArgumentException ex) {
                continue;
            }
            int count = expandVerseIds(ranges).size();
            if (count > EMBED_VERSE_CAP) {
                throw new IllegalArgumentException(embedCapMessage(count));
            }
        }
    }

    /** True if both normalize to the same range list. */
    public static boolean equalRanges(List<Range> a, List<Range> b) {
        try {
            return normalizeRanges(a).equals(normalizeRanges(b));
        } catch (Exception e) {
            return false;
        }
    }
}
