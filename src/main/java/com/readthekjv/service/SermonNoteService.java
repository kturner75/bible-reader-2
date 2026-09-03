package com.readthekjv.service;

import com.readthekjv.model.Book;
import com.readthekjv.model.Verse;
import com.readthekjv.model.dto.SermonNoteResponse;
import com.readthekjv.model.dto.SermonNoteSummary;
import com.readthekjv.model.entity.SermonNote;
import com.readthekjv.model.entity.SermonNoteRef;
import com.readthekjv.repository.SermonNoteRefRepository;
import com.readthekjv.repository.SermonNoteRepository;
import com.readthekjv.repository.UserRepository;
import com.readthekjv.util.VerseRangeParser;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Transactional
public class SermonNoteService {

    /** JPQL {@code IN ()} is not legal; a book id no note can carry stands in for "no matches". */
    private static final List<Integer> NO_BOOKS = List.of(-1);

    /**
     * "Match everything" stand-ins for the three optional filters. Postgres cannot type a
     * bare parameter in {@code ? IS NULL}, so the query compares real values instead of
     * testing for null — see {@link SermonNoteRepository#search}.
     */
    /** Portable link tokens, for rewriting a body into something readable in a preview. */
    private static final Pattern LINK_TOKEN = Pattern.compile("\\[[veVE]=[^\\]]*\\]");

    private static final String ANY_TEXT = "%";
    private static final int ANY_BOOK = -1;
    private static final OffsetDateTime ANY_TIME = OffsetDateTime.parse("1970-01-01T00:00:00Z");

    private final SermonNoteRepository sermonNoteRepository;
    private final SermonNoteRefRepository refRepository;
    private final UserRepository userRepository;
    private final NoteScriptureIndexer indexer;
    private final BibleService bibleService;

    public SermonNoteService(SermonNoteRepository sermonNoteRepository,
                             SermonNoteRefRepository refRepository,
                             UserRepository userRepository,
                             NoteScriptureIndexer indexer,
                             BibleService bibleService) {
        this.sermonNoteRepository = sermonNoteRepository;
        this.refRepository = refRepository;
        this.userRepository = userRepository;
        this.indexer = indexer;
        this.bibleService = bibleService;
    }

    /** Every note the user owns, newest first — the finder at rest. */
    @Transactional(readOnly = true)
    public List<SermonNoteSummary> list(Long userId) {
        return search(userId, null, null, null, null);
    }

    /**
     * @param q            free text over title, body, and the names of books the note cites
     * @param bookId       restrict to notes citing this book, or null
     * @param updatedWithin rolling window: {@code "30d"}, {@code "365d"}, or null for any time
     * @param sort         {@code "oldest"} | {@code "title"} | anything else = recently updated
     */
    @Transactional(readOnly = true)
    public List<SermonNoteSummary> search(Long userId, String q, Integer bookId,
                                          String updatedWithin, String sort) {
        String query = q == null || q.isBlank() ? null : q.trim();
        String like = query == null ? ANY_TEXT : "%" + query.toLowerCase() + "%";
        List<Integer> queryBookIds = query == null ? NO_BOOKS : booksNamed(query);

        List<SermonNote> notes = sermonNoteRepository.search(
                userId, like, queryBookIds, bookId == null ? ANY_BOOK : bookId,
                since(updatedWithin), sortOf(sort));
        return toSummaries(notes, query);
    }

    /** Books the user has cited anywhere — the scripture filter offers only these. */
    @Transactional(readOnly = true)
    public List<SermonNoteSummary.ScriptureRef> scriptureFilterOptions(Long userId) {
        List<SermonNoteSummary.ScriptureRef> options = new ArrayList<>();
        for (int id : refRepository.findBookIdsForUser(userId)) {
            bibleService.getBook(id).ifPresent(b -> options.add(
                    new SermonNoteSummary.ScriptureRef(b.id(), 0, b.name())));
        }
        return options;
    }

    @Transactional(readOnly = true)
    public SermonNoteResponse get(Long userId, UUID id) {
        return SermonNoteResponse.from(findOwned(userId, id));
    }

    public SermonNoteResponse create(Long userId, String title, String note) {
        SermonNote n = new SermonNote();
        n.setUser(userRepository.getReferenceById(userId));
        n.setTitle(title.trim());
        NoteEmbedCap.require(note);
        n.setNote(note.trim());
        SermonNote saved = sermonNoteRepository.save(n);
        reindex(saved);
        return SermonNoteResponse.from(saved);
    }

    public SermonNoteResponse update(Long userId, UUID id, String title, String note) {
        SermonNote n = findOwned(userId, id);
        n.setTitle(title.trim());
        NoteEmbedCap.require(note);
        n.setNote(note.trim());
        SermonNote saved = sermonNoteRepository.save(n);
        reindex(saved);
        return SermonNoteResponse.from(saved);
    }

    public void delete(Long userId, UUID id) {
        // sermon_note_refs cascades on the FK; the explicit delete keeps the JPA-managed
        // rows from outliving the note inside this transaction.
        SermonNote n = findOwned(userId, id);
        refRepository.deleteForNote(n.getId());
        sermonNoteRepository.delete(n);
    }

    /**
     * Rebuilds the note's derived scripture rows, delete-then-insert, in the caller's
     * transaction. There is no partial-update path, so the rows cannot half-apply.
     */
    void reindex(SermonNote note) {
        refRepository.deleteForNote(note.getId());
        Set<NoteScriptureIndexer.BookChapter> chapters = indexer.extract(note.getNote());
        if (chapters.isEmpty()) {
            return;
        }
        List<SermonNoteRef> rows = new ArrayList<>(chapters.size());
        for (NoteScriptureIndexer.BookChapter bc : chapters) {
            rows.add(new SermonNoteRef(note, bc.bookId(), bc.chapter()));
        }
        refRepository.saveAll(rows);
    }

    /**
     * Reindexes a batch by id, loading each note inside this transaction.
     *
     * Takes ids rather than entities so the backfill cannot hand in detached instances
     * read in another transaction, and so the transaction boundary lands on this bean —
     * a self-invoked @Transactional method is not proxied and would run without one.
     *
     * @return how many notes were indexed
     */
    public int reindexByIds(List<UUID> noteIds) {
        int count = 0;
        for (UUID id : noteIds) {
            SermonNote note = sermonNoteRepository.findById(id).orElse(null);
            if (note == null) {
                continue;
            }
            reindex(note);
            count++;
        }
        return count;
    }

    private List<SermonNoteSummary> toSummaries(List<SermonNote> notes, String query) {
        if (notes.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<SermonNoteSummary.ScriptureRef>> byNote = new HashMap<>();
        for (SermonNoteRef r : refRepository.findForNotes(notes.stream().map(SermonNote::getId).toList())) {
            byNote.computeIfAbsent(r.getNote().getId(), k -> new ArrayList<>())
                  .add(new SermonNoteSummary.ScriptureRef(
                          r.getBookId(), r.getChapter(), chipLabel(r.getBookId(), r.getChapter())));
        }
        List<SermonNoteSummary> out = new ArrayList<>(notes.size());
        for (SermonNote n : notes) {
            out.add(SermonNoteSummary.from(n, previewBody(n.getNote()),
                    byNote.getOrDefault(n.getId(), List.of()), query));
        }
        return out;
    }

    /**
     * Rewrites the stored body for preview, swapping each portable token for the chapter it
     * points at: "Short letter, sharp edge. [v=30675] sets the address." becomes
     * "…sharp edge. Jude sets the address."
     *
     * Simply deleting the token — the DTO's fallback for anything unresolvable — leaves a
     * dangling clause whenever a token opened a sentence. This costs a regex and a verse
     * lookup per token on the page being returned, not per note in the corpus, so it is not
     * the read-time parse that decision 3 in the ADR rejected.
     */
    private String previewBody(String body) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        Matcher m = LINK_TOKEN.matcher(body);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(tokenLabel(m.group(0))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Chapter label for a token's first verse; empty when it cannot be resolved. */
    private String tokenLabel(String token) {
        try {
            List<VerseRangeParser.Range> ranges = VerseRangeParser.parseVToken(token);
            Verse first = bibleService.getVerse(ranges.get(0).from()).orElse(null);
            return first == null ? "" : chipLabel(first.bookId(), first.chapter());
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    /** "Psalm 23"; a one-chapter book is just its name — "Jude 1" reads like a verse. */
    private String chipLabel(int bookId, int chapter) {
        Book book = bibleService.getBook(bookId).orElse(null);
        if (book == null) {
            return "";
        }
        return book.chapters() == 1 ? book.name() : book.name() + " " + chapter;
    }

    /**
     * Book ids whose name contains the query. Substring, so "john" reaches John and the
     * three epistles, and "ps" reaches Psalms — deliberately generous, since this only
     * ever widens a text search the reader already narrowed by typing.
     */
    private List<Integer> booksNamed(String query) {
        String needle = query.toLowerCase();
        List<Integer> ids = new ArrayList<>();
        for (Book b : bibleService.getBooks()) {
            if (b.name().toLowerCase().contains(needle)) {
                ids.add(b.id());
            }
        }
        return ids.isEmpty() ? NO_BOOKS : ids;
    }

    /**
     * Rolling windows, not calendar boundaries — deliberately. A calendar "this year" is
     * the reader's calendar, which would drag the request zone into a coarse convenience
     * filter, and on 2 January it shows two days of notes.
     */
    private OffsetDateTime since(String updatedWithin) {
        if (updatedWithin == null || updatedWithin.isBlank()) {
            return ANY_TIME;
        }
        return switch (updatedWithin.trim()) {
            case "30d" -> OffsetDateTime.now().minusDays(30);
            case "365d" -> OffsetDateTime.now().minusDays(365);
            default -> ANY_TIME;
        };
    }

    private Sort sortOf(String sort) {
        if (sort == null) {
            return Sort.by(Sort.Direction.DESC, "updatedAt");
        }
        return switch (sort.trim()) {
            case "oldest" -> Sort.by(Sort.Direction.ASC, "updatedAt");
            case "title" -> Sort.by(Sort.Direction.ASC, "title");
            default -> Sort.by(Sort.Direction.DESC, "updatedAt");
        };
    }

    private SermonNote findOwned(Long userId, UUID id) {
        return sermonNoteRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Sermon note not found"));
    }
}
