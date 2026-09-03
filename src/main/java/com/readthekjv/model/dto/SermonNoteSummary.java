package com.readthekjv.model.dto;

import com.readthekjv.model.entity.SermonNote;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.regex.Pattern;

public record SermonNoteSummary(
    String id,
    String title,
    String snippet,
    List<ScriptureRef> refs,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    /**
     * One chapter the note cites. {@code label} is the rendered chip ("Psalm 23") —
     * built server-side because book names live in the in-memory {@code BibleService},
     * not in the database.
     */
    public record ScriptureRef(int bookId, int chapter, String label) {}

    /**
     * Two lines on a finder card at the desktop column width. The previous fixed 160
     * overflowed the space the card gives it.
     */
    private static final int SNIPPET_LENGTH = 140;

    /** How much of the window sits before the match, so the hit is not flush left. */
    private static final int LEAD_IN = 40;

    /** More chips than a card can show without becoming a wall. */
    private static final int MAX_REFS = 12;

    /**
     * Portable link tokens are storage, not prose. Left in, a snippet reads
     * "The shepherd leads through, not around. [v=14237-14242] Close with…" — and the
     * scripture chips already say what the token pointed at.
     */
    private static final Pattern LINK_TOKEN = Pattern.compile("\\[[veVE]=[^\\]]*\\]");

    /**
     * @param previewBody the note body with portable tokens already rewritten to human
     *                    labels by the service; the token strip below is the fallback for
     *                    anything it could not resolve
     */
    public static SermonNoteSummary from(SermonNote n, String previewBody,
                                         List<ScriptureRef> refs, String query) {
        List<ScriptureRef> capped = refs == null ? List.of()
                : refs.size() <= MAX_REFS ? List.copyOf(refs) : List.copyOf(refs.subList(0, MAX_REFS));
        return new SermonNoteSummary(
            n.getId().toString(), n.getTitle(), snippetOf(previewBody, query), capped,
            n.getCreatedAt(), n.getUpdatedAt()
        );
    }

    /**
     * Text around the first hit for {@code query}, or the leading text when there is no
     * query or no hit in the body. The caller highlights the match itself — this returns
     * plain text so nothing has to trust server-built markup.
     */
    static String snippetOf(String note, String query) {
        String flat = note == null ? ""
                : LINK_TOKEN.matcher(note).replaceAll(" ").replaceAll("\\s+", " ").trim();
        if (flat.length() <= SNIPPET_LENGTH) {
            return flat;
        }

        int at = -1;
        if (query != null && !query.isBlank()) {
            at = flat.toLowerCase().indexOf(query.trim().toLowerCase());
        }
        if (at < 0) {
            return flat.substring(0, SNIPPET_LENGTH).trim() + "…";
        }

        // Window the match, then pull both edges back to a space so the snippet does not
        // start or end mid-word.
        int start = Math.max(0, at - LEAD_IN);
        int end = Math.min(flat.length(), start + SNIPPET_LENGTH);
        if (start > 0) {
            int space = flat.indexOf(' ', start);
            if (space >= 0 && space < at) {
                start = space + 1;
            }
        }
        if (end < flat.length()) {
            int space = flat.lastIndexOf(' ', end);
            if (space > at) {
                end = space;
            }
        }
        return (start > 0 ? "…" : "")
                + flat.substring(start, end).trim()
                + (end < flat.length() ? "…" : "");
    }
}
