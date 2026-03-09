package com.readthekjv.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses and validates the passage natural key format.
 *
 * Format:
 *   "26930"              — single verse
 *   "26930:26944"        — consecutive range
 *   "26930:26944,27100"  — range + single (non-consecutive)
 *   "26930:26944,27100:27115" — two ranges
 *
 * UUID is the primary key; natural key is the authoritative segment definition.
 * fromVerseId / toVerseId on the Passage entity are the outer bounds (min/max)
 * for indexed range queries, computed from the natural key.
 */
public class NaturalKeyParser {

    public record Segment(int from, int to) {}

    /**
     * Parses a natural key string into an ordered list of segments.
     * Throws IllegalArgumentException on malformed input.
     */
    public static List<Segment> parse(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Natural key must not be blank");
        }
        List<Segment> segments = new ArrayList<>();
        for (String part : key.split(",")) {
            part = part.trim();
            if (part.contains(":")) {
                String[] bounds = part.split(":", 2);
                int from = Integer.parseInt(bounds[0].trim());
                int to   = Integer.parseInt(bounds[1].trim());
                segments.add(new Segment(from, to));
            } else {
                int v = Integer.parseInt(part);
                segments.add(new Segment(v, v));
            }
        }
        return segments;
    }

    /** Returns the smallest fromVerseId across all segments (outer lower bound). */
    public static int outerFrom(List<Segment> segments) {
        return segments.stream().mapToInt(Segment::from).min()
                .orElseThrow(() -> new IllegalArgumentException("Empty segment list"));
    }

    /** Returns the largest toVerseId across all segments (outer upper bound). */
    public static int outerTo(List<Segment> segments) {
        return segments.stream().mapToInt(Segment::to).max()
                .orElseThrow(() -> new IllegalArgumentException("Empty segment list"));
    }

    /**
     * Validates that a natural key is well-formed and within Bible bounds.
     * Each segment must have from &le; to, both in [1, 31102].
     */
    public static boolean isValid(String key) {
        try {
            List<Segment> segs = parse(key);
            if (segs.isEmpty()) return false;
            return segs.stream().allMatch(s ->
                s.from() >= 1 && s.to() <= 31102 && s.from() <= s.to()
            );
        } catch (Exception e) {
            return false;
        }
    }
}
