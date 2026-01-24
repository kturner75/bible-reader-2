package com.readthekjv.service;

import com.readthekjv.model.Book;
import com.readthekjv.model.ChapterInfo;
import com.readthekjv.model.Verse;
import com.readthekjv.util.ReferenceParser;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service for Bible data access and navigation.
 * All data is stored in memory for fast O(1) lookups.
 */
@Service
public class BibleService {

    // Canonical book order
    public static final List<String> BOOK_ORDER = List.of(
        "Genesis", "Exodus", "Leviticus", "Numbers", "Deuteronomy",
        "Joshua", "Judges", "Ruth", "1 Samuel", "2 Samuel",
        "1 Kings", "2 Kings", "1 Chronicles", "2 Chronicles",
        "Ezra", "Nehemiah", "Esther", "Job", "Psalm", "Proverbs",
        "Ecclesiastes", "Song of Solomon", "Isaiah", "Jeremiah",
        "Lamentations", "Ezekiel", "Daniel", "Hosea", "Joel", "Amos",
        "Obadiah", "Jonah", "Micah", "Nahum", "Habakkuk", "Zephaniah",
        "Haggai", "Zechariah", "Malachi",
        "Matthew", "Mark", "Luke", "John", "Acts",
        "Romans", "1 Corinthians", "2 Corinthians", "Galatians", "Ephesians",
        "Philippians", "Colossians", "1 Thessalonians", "2 Thessalonians",
        "1 Timothy", "2 Timothy", "Titus", "Philemon", "Hebrews",
        "James", "1 Peter", "2 Peter", "1 John", "2 John", "3 John",
        "Jude", "Revelation"
    );

    // All verses indexed by global ID (1-31102)
    private final Map<Integer, Verse> versesById = new LinkedHashMap<>();
    
    // All books indexed by ID (1-66)
    private final Map<Integer, Book> booksById = new LinkedHashMap<>();
    
    // Book name to ID mapping
    private final Map<String, Integer> bookNameToId = new HashMap<>();
    
    // Verse lookup by book/chapter/verse
    private final Map<String, Integer> referenceToId = new HashMap<>();
    
    // Chapter info by book ID
    private final Map<Integer, List<ChapterInfo>> chaptersByBook = new HashMap<>();
    
    // Total verse count
    private int totalVerses = 0;

    /**
     * Loads all verses into in-memory storage.
     * Called once at startup by BibleDataLoader.
     */
    public void loadVerses(List<Verse> verses) {
        versesById.clear();
        booksById.clear();
        bookNameToId.clear();
        referenceToId.clear();
        chaptersByBook.clear();

        // Index all verses
        for (Verse verse : verses) {
            versesById.put(verse.id(), verse);
            
            String refKey = makeRefKey(verse.book(), verse.chapter(), verse.verse());
            referenceToId.put(refKey, verse.id());
        }
        
        totalVerses = verses.size();

        // Build book and chapter metadata
        buildBookMetadata(verses);
    }

    /**
     * Builds book and chapter metadata from loaded verses.
     */
    private void buildBookMetadata(List<Verse> verses) {
        Map<String, List<Verse>> versesByBook = new LinkedHashMap<>();
        
        // Group verses by book
        for (Verse v : verses) {
            versesByBook.computeIfAbsent(v.book(), k -> new ArrayList<>()).add(v);
        }

        // Create Book records in canonical order
        int bookId = 1;
        for (String bookName : BOOK_ORDER) {
            List<Verse> bookVerses = versesByBook.get(bookName);
            if (bookVerses == null || bookVerses.isEmpty()) continue;

            int firstVerseId = bookVerses.get(0).id();
            int lastVerseId = bookVerses.get(bookVerses.size() - 1).id();
            
            // Count chapters
            Set<Integer> chapters = new LinkedHashSet<>();
            for (Verse v : bookVerses) {
                chapters.add(v.chapter());
            }

            Book book = new Book(bookId, bookName, chapters.size(), firstVerseId, lastVerseId);
            booksById.put(bookId, book);
            bookNameToId.put(bookName, bookId);

            // Build chapter info
            List<ChapterInfo> chapterInfos = new ArrayList<>();
            Map<Integer, List<Verse>> versesByChapter = new LinkedHashMap<>();
            for (Verse v : bookVerses) {
                versesByChapter.computeIfAbsent(v.chapter(), k -> new ArrayList<>()).add(v);
            }
            
            for (Map.Entry<Integer, List<Verse>> entry : versesByChapter.entrySet()) {
                List<Verse> chapterVerses = entry.getValue();
                chapterInfos.add(new ChapterInfo(
                    entry.getKey(),
                    chapterVerses.get(0).id(),
                    chapterVerses.size()
                ));
            }
            chaptersByBook.put(bookId, chapterInfos);

            bookId++;
        }
    }

    private String makeRefKey(String book, int chapter, int verse) {
        return book + "|" + chapter + "|" + verse;
    }

    /**
     * Gets a single verse by global ID.
     */
    public Optional<Verse> getVerse(int id) {
        return Optional.ofNullable(versesById.get(id));
    }

    /**
     * Gets verses in a range (inclusive start, exclusive end count).
     */
    public List<Verse> getVerses(int fromId, int count) {
        List<Verse> result = new ArrayList<>();
        for (int i = fromId; i < fromId + count && i <= totalVerses; i++) {
            Verse v = versesById.get(i);
            if (v != null) {
                result.add(v);
            }
        }
        return result;
    }

    /**
     * Gets all verses for a specific chapter.
     */
    public List<Verse> getChapterVerses(String book, int chapter) {
        List<Verse> result = new ArrayList<>();
        Integer bookId = bookNameToId.get(book);
        if (bookId == null) return result;

        List<ChapterInfo> chapters = chaptersByBook.get(bookId);
        if (chapters == null) return result;

        for (ChapterInfo ci : chapters) {
            if (ci.chapter() == chapter) {
                return getVerses(ci.firstVerseId(), ci.verseCount());
            }
        }
        return result;
    }

    /**
     * Gets all books.
     */
    public List<Book> getBooks() {
        return new ArrayList<>(booksById.values());
    }

    /**
     * Gets a book by ID.
     */
    public Optional<Book> getBook(int bookId) {
        return Optional.ofNullable(booksById.get(bookId));
    }

    /**
     * Gets a book by name.
     */
    public Optional<Book> getBookByName(String name) {
        Integer id = bookNameToId.get(name);
        return id != null ? Optional.ofNullable(booksById.get(id)) : Optional.empty();
    }

    /**
     * Gets chapter info for a book.
     */
    public List<ChapterInfo> getChapters(int bookId) {
        return chaptersByBook.getOrDefault(bookId, List.of());
    }

    /**
     * Gets chapter info for a book by name.
     */
    public List<ChapterInfo> getChaptersByBookName(String bookName) {
        Integer bookId = bookNameToId.get(bookName);
        return bookId != null ? getChapters(bookId) : List.of();
    }

    /**
     * Resolves a parsed reference to a verse ID.
     */
    public Optional<Integer> resolveReference(ReferenceParser.ParsedReference ref) {
        if (ref == null) return Optional.empty();

        int verseNum = ref.verse() != null ? ref.verse() : 1;
        String refKey = makeRefKey(ref.book(), ref.chapter(), verseNum);
        return Optional.ofNullable(referenceToId.get(refKey));
    }

    /**
     * Parses a reference string and returns the verse ID.
     */
    public Optional<Integer> parseAndResolve(String reference) {
        ReferenceParser.ParsedReference parsed = ReferenceParser.parse(reference);
        return resolveReference(parsed);
    }

    /**
     * Gets the first verse ID of the next chapter.
     */
    public Optional<Integer> getNextChapter(int currentVerseId) {
        Verse current = versesById.get(currentVerseId);
        if (current == null) return Optional.empty();

        Integer bookId = bookNameToId.get(current.book());
        if (bookId == null) return Optional.empty();

        List<ChapterInfo> chapters = chaptersByBook.get(bookId);
        if (chapters == null) return Optional.empty();

        // Find current chapter, return next
        for (int i = 0; i < chapters.size(); i++) {
            if (chapters.get(i).chapter() == current.chapter()) {
                if (i + 1 < chapters.size()) {
                    return Optional.of(chapters.get(i + 1).firstVerseId());
                } else {
                    // Move to next book
                    return getNextBook(currentVerseId);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Gets the first verse ID of the previous chapter.
     */
    public Optional<Integer> getPreviousChapter(int currentVerseId) {
        Verse current = versesById.get(currentVerseId);
        if (current == null) return Optional.empty();

        Integer bookId = bookNameToId.get(current.book());
        if (bookId == null) return Optional.empty();

        List<ChapterInfo> chapters = chaptersByBook.get(bookId);
        if (chapters == null) return Optional.empty();

        // Find current chapter, return previous
        for (int i = 0; i < chapters.size(); i++) {
            if (chapters.get(i).chapter() == current.chapter()) {
                if (i > 0) {
                    return Optional.of(chapters.get(i - 1).firstVerseId());
                } else {
                    // Move to previous book's last chapter
                    return getPreviousBookLastChapter(bookId);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Gets the first verse ID of the next book.
     */
    public Optional<Integer> getNextBook(int currentVerseId) {
        Verse current = versesById.get(currentVerseId);
        if (current == null) return Optional.empty();

        Integer currentBookId = bookNameToId.get(current.book());
        if (currentBookId == null) return Optional.empty();

        Book nextBook = booksById.get(currentBookId + 1);
        return nextBook != null ? Optional.of(nextBook.firstVerseId()) : Optional.empty();
    }

    /**
     * Gets the first verse ID of the previous book.
     */
    public Optional<Integer> getPreviousBook(int currentVerseId) {
        Verse current = versesById.get(currentVerseId);
        if (current == null) return Optional.empty();

        Integer currentBookId = bookNameToId.get(current.book());
        if (currentBookId == null) return Optional.empty();

        Book prevBook = booksById.get(currentBookId - 1);
        return prevBook != null ? Optional.of(prevBook.firstVerseId()) : Optional.empty();
    }

    /**
     * Helper to get the last chapter's first verse of previous book.
     */
    private Optional<Integer> getPreviousBookLastChapter(int currentBookId) {
        Book prevBook = booksById.get(currentBookId - 1);
        if (prevBook == null) return Optional.empty();

        List<ChapterInfo> chapters = chaptersByBook.get(prevBook.id());
        if (chapters == null || chapters.isEmpty()) return Optional.empty();

        return Optional.of(chapters.get(chapters.size() - 1).firstVerseId());
    }

    /**
     * Gets total verse count.
     */
    public int getTotalVerses() {
        return totalVerses;
    }
}
