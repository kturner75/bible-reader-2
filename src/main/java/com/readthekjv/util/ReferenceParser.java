package com.readthekjv.util;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for parsing Bible references like "John 3:16", "ps 23", "gen1:1".
 * Also parses same-chapter absolute multi-verse links: "John 3:16-18", "John 3:1-11,15".
 */
public class ReferenceParser {

    // Pattern to match various Bible reference formats
    // Captures: book name, optional chapter, optional verse
    private static final Pattern REFERENCE_PATTERN = Pattern.compile(
        "^\\s*" +
        "(\\d?\\s*[a-zA-Z]+(?:\\s+[a-zA-Z]+)*)" + // Book name; may start with number ("1 John") or span words ("Song of Solomon")
        "(?:" +                       // Optional chapter/verse group
            "\\s+" +                  // Whitespace before chapter (required if chapter present)
            "(\\d+)" +                // Chapter number
            "(?:\\s*[:.v]\\s*(\\d+))?" + // Optional verse number with separator
        ")?" +
        "\\s*$",
        Pattern.CASE_INSENSITIVE
    );

    // Secondary pattern for book aliases with chapter directly attached (e.g., "1p2", "gen1")
    private static final Pattern ATTACHED_CHAPTER_PATTERN = Pattern.compile(
        "^\\s*" +
        "(\\d?\\s*[a-zA-Z]+?)" +     // Book name (non-greedy to allow chapter to attach)
        "(\\d+)" +                    // Chapter number attached directly
        "(?:\\s*[:.v]\\s*(\\d+))?" +  // Optional verse number with separator
        "\\s*$",
        Pattern.CASE_INSENSITIVE
    );

    // Book + chapter + same-chapter verse list/range (space before chapter)
    // e.g. "John 3:16-18", "jer 13:1-11,15", "Song of Solomon 2:1-3"
    private static final Pattern ABSOLUTE_LINK_PATTERN = Pattern.compile(
        "^\\s*" +
        "(\\d?\\s*[a-zA-Z]+(?:\\s+[a-zA-Z]+)*)" +
        "\\s+" +
        "(\\d+)" +
        "\\s*[:.v]\\s*" +
        "(.+?)" +
        "\\s*$",
        Pattern.CASE_INSENSITIVE
    );

    // Attached book+chapter + verse list: "jn3:16-18", "gen1:1-3"
    private static final Pattern ABSOLUTE_LINK_ATTACHED_PATTERN = Pattern.compile(
        "^\\s*" +
        "(\\d?\\s*[a-zA-Z]+?)" +
        "(\\d+)" +
        "\\s*[:.v]\\s*" +
        "(.+?)" +
        "\\s*$",
        Pattern.CASE_INSENSITIVE
    );

    // Mapping of book name variations to canonical names
    private static final Map<String, String> BOOK_ALIASES = Map.ofEntries(
        // Genesis
        Map.entry("genesis", "Genesis"),
        Map.entry("gen", "Genesis"),
        Map.entry("ge", "Genesis"),
        // Exodus
        Map.entry("exodus", "Exodus"),
        Map.entry("exod", "Exodus"),
        Map.entry("exo", "Exodus"),
        Map.entry("ex", "Exodus"),
        // Leviticus
        Map.entry("leviticus", "Leviticus"),
        Map.entry("lev", "Leviticus"),
        Map.entry("le", "Leviticus"),
        // Numbers
        Map.entry("numbers", "Numbers"),
        Map.entry("num", "Numbers"),
        Map.entry("nu", "Numbers"),
        // Deuteronomy
        Map.entry("deuteronomy", "Deuteronomy"),
        Map.entry("deut", "Deuteronomy"),
        Map.entry("deu", "Deuteronomy"),
        Map.entry("de", "Deuteronomy"),
        // Joshua
        Map.entry("joshua", "Joshua"),
        Map.entry("josh", "Joshua"),
        Map.entry("jos", "Joshua"),
        // Judges
        Map.entry("judges", "Judges"),
        Map.entry("judg", "Judges"),
        Map.entry("jdg", "Judges"),
        // Ruth
        Map.entry("ruth", "Ruth"),
        Map.entry("ru", "Ruth"),
        // 1 Samuel
        Map.entry("1 samuel", "1 Samuel"),
        Map.entry("1samuel", "1 Samuel"),
        Map.entry("1 sam", "1 Samuel"),
        Map.entry("1sam", "1 Samuel"),
        Map.entry("1sa", "1 Samuel"),
        // 2 Samuel
        Map.entry("2 samuel", "2 Samuel"),
        Map.entry("2samuel", "2 Samuel"),
        Map.entry("2 sam", "2 Samuel"),
        Map.entry("2sam", "2 Samuel"),
        Map.entry("2sa", "2 Samuel"),
        // 1 Kings
        Map.entry("1 kings", "1 Kings"),
        Map.entry("1kings", "1 Kings"),
        Map.entry("1 ki", "1 Kings"),
        Map.entry("1ki", "1 Kings"),
        Map.entry("1k", "1 Kings"),
        // 2 Kings
        Map.entry("2 kings", "2 Kings"),
        Map.entry("2kings", "2 Kings"),
        Map.entry("2 ki", "2 Kings"),
        Map.entry("2ki", "2 Kings"),
        Map.entry("2k", "2 Kings"),
        // 1 Chronicles
        Map.entry("1 chronicles", "1 Chronicles"),
        Map.entry("1chronicles", "1 Chronicles"),
        Map.entry("1 chron", "1 Chronicles"),
        Map.entry("1chron", "1 Chronicles"),
        Map.entry("1 chr", "1 Chronicles"),
        Map.entry("1chr", "1 Chronicles"),
        Map.entry("1ch", "1 Chronicles"),
        // 2 Chronicles
        Map.entry("2 chronicles", "2 Chronicles"),
        Map.entry("2chronicles", "2 Chronicles"),
        Map.entry("2 chron", "2 Chronicles"),
        Map.entry("2chron", "2 Chronicles"),
        Map.entry("2 chr", "2 Chronicles"),
        Map.entry("2chr", "2 Chronicles"),
        Map.entry("2ch", "2 Chronicles"),
        // Ezra
        Map.entry("ezra", "Ezra"),
        Map.entry("ezr", "Ezra"),
        // Nehemiah
        Map.entry("nehemiah", "Nehemiah"),
        Map.entry("neh", "Nehemiah"),
        Map.entry("ne", "Nehemiah"),
        // Esther
        Map.entry("esther", "Esther"),
        Map.entry("est", "Esther"),
        Map.entry("es", "Esther"),
        // Job
        Map.entry("job", "Job"),
        // Psalm
        Map.entry("psalms", "Psalm"),
        Map.entry("psalm", "Psalm"),
        Map.entry("ps", "Psalm"),
        Map.entry("psa", "Psalm"),
        // Proverbs
        Map.entry("proverbs", "Proverbs"),
        Map.entry("prov", "Proverbs"),
        Map.entry("pro", "Proverbs"),
        Map.entry("pr", "Proverbs"),
        // Ecclesiastes
        Map.entry("ecclesiastes", "Ecclesiastes"),
        Map.entry("eccl", "Ecclesiastes"),
        Map.entry("ecc", "Ecclesiastes"),
        Map.entry("ec", "Ecclesiastes"),
        // Song of Solomon
        Map.entry("song of solomon", "Song of Solomon"),
        Map.entry("song", "Song of Solomon"),
        Map.entry("sos", "Song of Solomon"),
        Map.entry("ss", "Song of Solomon"),
        // Isaiah
        Map.entry("isaiah", "Isaiah"),
        Map.entry("isa", "Isaiah"),
        Map.entry("is", "Isaiah"),
        // Jeremiah
        Map.entry("jeremiah", "Jeremiah"),
        Map.entry("jer", "Jeremiah"),
        Map.entry("je", "Jeremiah"),
        // Lamentations
        Map.entry("lamentations", "Lamentations"),
        Map.entry("lam", "Lamentations"),
        Map.entry("la", "Lamentations"),
        // Ezekiel
        Map.entry("ezekiel", "Ezekiel"),
        Map.entry("ezek", "Ezekiel"),
        Map.entry("eze", "Ezekiel"),
        // Daniel
        Map.entry("daniel", "Daniel"),
        Map.entry("dan", "Daniel"),
        Map.entry("da", "Daniel"),
        // Hosea
        Map.entry("hosea", "Hosea"),
        Map.entry("hos", "Hosea"),
        Map.entry("ho", "Hosea"),
        // Joel
        Map.entry("joel", "Joel"),
        Map.entry("joe", "Joel"),
        // Amos
        Map.entry("amos", "Amos"),
        Map.entry("am", "Amos"),
        // Obadiah
        Map.entry("obadiah", "Obadiah"),
        Map.entry("obad", "Obadiah"),
        Map.entry("ob", "Obadiah"),
        // Jonah
        Map.entry("jonah", "Jonah"),
        Map.entry("jon", "Jonah"),
        // Micah
        Map.entry("micah", "Micah"),
        Map.entry("mic", "Micah"),
        // Nahum
        Map.entry("nahum", "Nahum"),
        Map.entry("nah", "Nahum"),
        Map.entry("na", "Nahum"),
        // Habakkuk
        Map.entry("habakkuk", "Habakkuk"),
        Map.entry("hab", "Habakkuk"),
        // Zephaniah
        Map.entry("zephaniah", "Zephaniah"),
        Map.entry("zeph", "Zephaniah"),
        Map.entry("zep", "Zephaniah"),
        // Haggai
        Map.entry("haggai", "Haggai"),
        Map.entry("hag", "Haggai"),
        // Zechariah
        Map.entry("zechariah", "Zechariah"),
        Map.entry("zech", "Zechariah"),
        Map.entry("zec", "Zechariah"),
        // Malachi
        Map.entry("malachi", "Malachi"),
        Map.entry("mal", "Malachi"),
        // Matthew
        Map.entry("matthew", "Matthew"),
        Map.entry("matt", "Matthew"),
        Map.entry("mat", "Matthew"),
        Map.entry("mt", "Matthew"),
        // Mark
        Map.entry("mark", "Mark"),
        Map.entry("mk", "Mark"),
        Map.entry("mr", "Mark"),
        // Luke
        Map.entry("luke", "Luke"),
        Map.entry("luk", "Luke"),
        Map.entry("lk", "Luke"),
        // John
        Map.entry("john", "John"),
        Map.entry("joh", "John"),
        Map.entry("jn", "John"),
        // Acts
        Map.entry("acts", "Acts"),
        Map.entry("act", "Acts"),
        Map.entry("ac", "Acts"),
        // Romans
        Map.entry("romans", "Romans"),
        Map.entry("rom", "Romans"),
        Map.entry("ro", "Romans"),
        // 1 Corinthians
        Map.entry("1 corinthians", "1 Corinthians"),
        Map.entry("1corinthians", "1 Corinthians"),
        Map.entry("1 cor", "1 Corinthians"),
        Map.entry("1cor", "1 Corinthians"),
        Map.entry("1co", "1 Corinthians"),
        Map.entry("1c", "1 Corinthians"),
        // 2 Corinthians
        Map.entry("2 corinthians", "2 Corinthians"),
        Map.entry("2corinthians", "2 Corinthians"),
        Map.entry("2 cor", "2 Corinthians"),
        Map.entry("2cor", "2 Corinthians"),
        Map.entry("2co", "2 Corinthians"),
        Map.entry("2c", "2 Corinthians"),
        // Galatians
        Map.entry("galatians", "Galatians"),
        Map.entry("gal", "Galatians"),
        Map.entry("ga", "Galatians"),
        // Ephesians
        Map.entry("ephesians", "Ephesians"),
        Map.entry("eph", "Ephesians"),
        // Philippians
        Map.entry("philippians", "Philippians"),
        Map.entry("phil", "Philippians"),
        Map.entry("php", "Philippians"),
        // Colossians
        Map.entry("colossians", "Colossians"),
        Map.entry("col", "Colossians"),
        // 1 Thessalonians
        Map.entry("1 thessalonians", "1 Thessalonians"),
        Map.entry("1thessalonians", "1 Thessalonians"),
        Map.entry("1 thess", "1 Thessalonians"),
        Map.entry("1thess", "1 Thessalonians"),
        Map.entry("1 th", "1 Thessalonians"),
        Map.entry("1th", "1 Thessalonians"),
        // 2 Thessalonians
        Map.entry("2 thessalonians", "2 Thessalonians"),
        Map.entry("2thessalonians", "2 Thessalonians"),
        Map.entry("2 thess", "2 Thessalonians"),
        Map.entry("2thess", "2 Thessalonians"),
        Map.entry("2 th", "2 Thessalonians"),
        Map.entry("2th", "2 Thessalonians"),
        // 1 Timothy
        Map.entry("1 timothy", "1 Timothy"),
        Map.entry("1timothy", "1 Timothy"),
        Map.entry("1 tim", "1 Timothy"),
        Map.entry("1tim", "1 Timothy"),
        Map.entry("1ti", "1 Timothy"),
        Map.entry("1t", "1 Timothy"),
        // 2 Timothy
        Map.entry("2 timothy", "2 Timothy"),
        Map.entry("2timothy", "2 Timothy"),
        Map.entry("2 tim", "2 Timothy"),
        Map.entry("2tim", "2 Timothy"),
        Map.entry("2ti", "2 Timothy"),
        Map.entry("2t", "2 Timothy"),
        // Titus
        Map.entry("titus", "Titus"),
        Map.entry("tit", "Titus"),
        // Philemon
        Map.entry("philemon", "Philemon"),
        Map.entry("phlm", "Philemon"),
        Map.entry("phm", "Philemon"),
        // Hebrews
        Map.entry("hebrews", "Hebrews"),
        Map.entry("heb", "Hebrews"),
        // James
        Map.entry("james", "James"),
        Map.entry("jas", "James"),
        Map.entry("jam", "James"),
        // 1 Peter
        Map.entry("1 peter", "1 Peter"),
        Map.entry("1peter", "1 Peter"),
        Map.entry("1 pet", "1 Peter"),
        Map.entry("1pet", "1 Peter"),
        Map.entry("1pe", "1 Peter"),
        Map.entry("1p", "1 Peter"),
        // 2 Peter
        Map.entry("2 peter", "2 Peter"),
        Map.entry("2peter", "2 Peter"),
        Map.entry("2 pet", "2 Peter"),
        Map.entry("2pet", "2 Peter"),
        Map.entry("2pe", "2 Peter"),
        Map.entry("2p", "2 Peter"),
        // 1 John
        Map.entry("1 john", "1 John"),
        Map.entry("1john", "1 John"),
        Map.entry("1 jn", "1 John"),
        Map.entry("1jn", "1 John"),
        Map.entry("1j", "1 John"),
        // 2 John
        Map.entry("2 john", "2 John"),
        Map.entry("2john", "2 John"),
        Map.entry("2 jn", "2 John"),
        Map.entry("2jn", "2 John"),
        Map.entry("2j", "2 John"),
        // 3 John
        Map.entry("3 john", "3 John"),
        Map.entry("3john", "3 John"),
        Map.entry("3 jn", "3 John"),
        Map.entry("3jn", "3 John"),
        Map.entry("3j", "3 John"),
        // Jude
        Map.entry("jude", "Jude"),
        Map.entry("jud", "Jude"),
        // Revelation
        Map.entry("revelation", "Revelation"),
        Map.entry("rev", "Revelation"),
        Map.entry("re", "Revelation")
    );

    /**
     * Result of parsing a Bible reference.
     */
    public record ParsedReference(
        String book,
        int chapter,
        Integer verse  // null if only chapter specified
    ) {}

    /**
     * Same-chapter book-qualified link with one or more verse spans.
     * Input UX only — persist as {@code [v=…]} after resolve.
     */
    public record ParsedAbsoluteLink(
        String book,
        int chapter,
        List<ScopeRelativeLinkParser.Span> verseSpans
    ) {
        /** True when more than one verse is addressed. */
        public boolean isMultiVerse() {
            if (verseSpans == null || verseSpans.isEmpty()) {
                return false;
            }
            if (verseSpans.size() > 1) {
                return true;
            }
            ScopeRelativeLinkParser.Span s = verseSpans.get(0);
            return s.from() != s.to();
        }
    }

    /**
     * Attempts to parse a string as a Bible reference.
     * Supports various formats:
     * - "1p" or "genesis" (book only) → defaults to chapter 1, verse 1
     * - "1p 2" or "gen 3" (book + chapter) → chapter N, verse 1
     * - "gen1" (book with attached chapter) → chapter N, verse 1
     * - "john 3:16" (full reference) → specific chapter and verse
     *
     * @param input The input string to parse
     * @return ParsedReference if successful, null if not a valid reference
     */
    public static ParsedReference parse(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        // First, try the main pattern (book with space then chapter/verse)
        Matcher matcher = REFERENCE_PATTERN.matcher(input);
        if (matcher.matches()) {
            String bookPart = matcher.group(1).toLowerCase().trim().replaceAll("\\s+", " ");
            String chapterPart = matcher.group(2);
            String versePart = matcher.group(3);

            // Normalize book name
            String canonicalBook = resolveBookAlias(bookPart);
            if (canonicalBook != null) {
                int chapter = 1; // Default chapter
                if (chapterPart != null && !chapterPart.isEmpty()) {
                    try {
                        chapter = Integer.parseInt(chapterPart);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }

                Integer verse = null;
                if (versePart != null && !versePart.isEmpty()) {
                    try {
                        verse = Integer.parseInt(versePart);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }

                return new ParsedReference(canonicalBook, chapter, verse);
            }
        }

        // Second, try the attached chapter pattern (e.g., "gen1", "1p2")
        Matcher attachedMatcher = ATTACHED_CHAPTER_PATTERN.matcher(input);
        if (attachedMatcher.matches()) {
            String bookPart = attachedMatcher.group(1).toLowerCase().trim();
            String chapterPart = attachedMatcher.group(2);
            String versePart = attachedMatcher.group(3);

            // Normalize book name
            String canonicalBook = resolveBookAlias(bookPart);
            if (canonicalBook != null) {
                int chapter;
                try {
                    chapter = Integer.parseInt(chapterPart);
                } catch (NumberFormatException e) {
                    return null;
                }

                Integer verse = null;
                if (versePart != null && !versePart.isEmpty()) {
                    try {
                        verse = Integer.parseInt(versePart);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }

                return new ParsedReference(canonicalBook, chapter, verse);
            }
        }

        return null;
    }

    /**
     * Parses a same-chapter absolute link: {@code John 3:16}, {@code John 3:16-18},
     * {@code John 3:1-11,15}, {@code jn3:16-18}.
     * <p>
     * Cross-chapter forms like {@code John 3:16-4:2} are rejected (verse part
     * must be a pure number list).
     *
     * @return parsed link, or {@code null} if not a valid absolute verse link
     */
    public static ParsedAbsoluteLink parseAbsoluteLink(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        Matcher matcher = ABSOLUTE_LINK_PATTERN.matcher(input);
        if (matcher.matches()) {
            ParsedAbsoluteLink link = buildAbsoluteLink(
                    matcher.group(1), matcher.group(2), matcher.group(3));
            if (link != null) {
                return link;
            }
        }

        Matcher attached = ABSOLUTE_LINK_ATTACHED_PATTERN.matcher(input);
        if (attached.matches()) {
            return buildAbsoluteLink(
                    attached.group(1), attached.group(2), attached.group(3));
        }

        return null;
    }

    /**
     * True when {@code input} is a book-qualified same-chapter multi-verse
     * (range and/or comma list), e.g. {@code John 3:16-18}.
     */
    public static boolean looksLikeAbsoluteMultiVerse(String input) {
        ParsedAbsoluteLink link = parseAbsoluteLink(input);
        return link != null && link.isMultiVerse();
    }

    private static ParsedAbsoluteLink buildAbsoluteLink(
            String bookRaw, String chapterRaw, String verseListRaw) {
        if (bookRaw == null || chapterRaw == null || verseListRaw == null) {
            return null;
        }
        String bookPart = bookRaw.toLowerCase().trim().replaceAll("\\s+", " ");
        String canonicalBook = resolveBookAlias(bookPart);
        if (canonicalBook == null) {
            return null;
        }
        int chapter;
        try {
            chapter = Integer.parseInt(chapterRaw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (chapter < 1) {
            return null;
        }
        // Reject cross-chapter residue (e.g. "16-4:2" left after first colon split)
        String verseList = verseListRaw.trim();
        if (verseList.contains(":")) {
            return null;
        }
        List<ScopeRelativeLinkParser.Span> spans =
                ScopeRelativeLinkParser.parseNumberList(verseList);
        if (spans == null) {
            return null;
        }
        return new ParsedAbsoluteLink(canonicalBook, chapter, List.copyOf(spans));
    }

    /**
     * Resolves a book name/alias to its canonical name.
     *
     * @param bookPart The book name or alias (lowercase)
     * @return The canonical book name, or null if not found
     */
    static String resolveBookAlias(String bookPart) {
        String canonicalBook = BOOK_ALIASES.get(bookPart);
        if (canonicalBook == null) {
            // Try removing spaces for numbered books
            canonicalBook = BOOK_ALIASES.get(bookPart.replace(" ", ""));
        }
        return canonicalBook;
    }

    /**
     * Checks if the input string looks like a Bible reference.
     */
    public static boolean looksLikeReference(String input) {
        return parse(input) != null;
    }
}
