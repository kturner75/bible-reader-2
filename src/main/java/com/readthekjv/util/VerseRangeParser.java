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
 * {@code [v=26136-26138,26140]}.
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

    private static final Pattern V_TOKEN =
            Pattern.compile("^\\[?v=([^\\]]+)\\]?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern INNER =
            Pattern.compile("^v=(.+)$", Pattern.CASE_INSENSITIVE);

    private VerseRangeParser() {}

    /**
     * Parses a full token like {@code [v=1-3,5]} or bare {@code v=1-3,5} or
     * just the body {@code 1-3,5}.
     */
    public static List<Range> parseVToken(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("v-token must not be blank");
        }
        String s = raw.trim();
        Matcher bracketed = V_TOKEN.matcher(s);
        if (bracketed.matches()) {
            s = bracketed.group(1).trim();
        } else {
            Matcher inner = INNER.matcher(s);
            if (inner.matches()) {
                s = inner.group(1).trim();
            }
        }
        return parseRangeBody(s);
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
                int a = Integer.parseInt(bounds[0].trim());
                int b = Integer.parseInt(bounds[1].trim());
                ranges.add(new Range(a, b));
            } else {
                int v = Integer.parseInt(part);
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

    /** True if both normalize to the same range list. */
    public static boolean equalRanges(List<Range> a, List<Range> b) {
        try {
            return normalizeRanges(a).equals(normalizeRanges(b));
        } catch (Exception e) {
            return false;
        }
    }
}
