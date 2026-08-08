package com.readthekjv.service;

import com.readthekjv.exception.BadRequestException;
import com.readthekjv.model.Book;
import com.readthekjv.model.ChapterInfo;
import com.readthekjv.model.dto.MarkRhythmProgressRequest;
import com.readthekjv.model.dto.RhythmLaneResponse;
import com.readthekjv.model.dto.RhythmLaneSpec;
import com.readthekjv.model.dto.RhythmResponse;
import com.readthekjv.model.entity.ReadingRhythm;
import com.readthekjv.model.entity.ReadingRhythmLane;
import com.readthekjv.model.entity.ReadingRhythmProgress;
import com.readthekjv.repository.ReadingRhythmLaneRepository;
import com.readthekjv.repository.ReadingRhythmProgressRepository;
import com.readthekjv.repository.ReadingRhythmRepository;
import com.readthekjv.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reading Rhythms — recurring, self-paced lanes over ordered book lists.
 *
 * <p>A lane's cursor is {@code (cursorBookId, cursorChapter)} = the last chapter
 * finished. Books earlier in the lane are complete; books later are untouched.
 * Per-book progress is derived from that, never stored.
 *
 * <p>{@code dayOfWeek} is only a surfacing hint. No method here consults the
 * current day when validating a mutation: any lane may be advanced on any day.
 */
@Service
@Transactional
public class ReadingRhythmService {

    static final int MAX_LANES_PER_RHYTHM = 14;
    static final int MAX_BOOKS_PER_LANE   = 66;
    static final int MAX_TITLE_LENGTH     = 100;
    static final int MAX_LANE_NAME_LENGTH = 60;

    private final ReadingRhythmRepository         rhythmRepo;
    private final ReadingRhythmLaneRepository     laneRepo;
    private final ReadingRhythmProgressRepository progressRepo;
    private final UserRepository                  userRepo;
    private final BibleService                    bibleService;

    public ReadingRhythmService(ReadingRhythmRepository rhythmRepo,
                                ReadingRhythmLaneRepository laneRepo,
                                ReadingRhythmProgressRepository progressRepo,
                                UserRepository userRepo,
                                BibleService bibleService) {
        this.rhythmRepo   = rhythmRepo;
        this.laneRepo     = laneRepo;
        this.progressRepo = progressRepo;
        this.userRepo     = userRepo;
        this.bibleService = bibleService;
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<RhythmResponse> list(Long userId, ZoneId zone) {
        Set<Long> markedToday = markedTodayLaneIds(userId, zone);
        return rhythmRepo.findByUserIdOrderByCreatedAtAsc(userId).stream()
                .map(r -> toResponse(r, markedToday, zone))
                .toList();
    }

    @Transactional(readOnly = true)
    public RhythmResponse get(Long userId, Long id, ZoneId zone) {
        return toResponse(findOwned(userId, id), markedTodayLaneIds(userId, zone), zone);
    }

    @Transactional(readOnly = true)
    public RhythmLaneResponse getLane(Long userId, Long laneId, ZoneId zone) {
        return toLaneResponse(findOwnedLane(userId, laneId), markedTodayLaneIds(userId, zone));
    }

    /**
     * Lanes this user has marked progress on today, in one query.
     *
     * <p>Only feeds the dashboard's "Today's Reading" card so it can settle after you
     * have read. It is never a constraint: an already-marked lane stays fully readable
     * and re-markable, on today or any other day.
     */
    private Set<Long> markedTodayLaneIds(Long userId, ZoneId zone) {
        OffsetDateTime startOfToday = LocalDate.now(zone).atStartOfDay(zone).toOffsetDateTime();
        return progressRepo.findLaneIdsMarkedSince(userId, startOfToday);
    }

    /**
     * Lanes scheduled for today, across all of the user's rhythms.
     *
     * <p>A hint for the dashboard's lead card — it may come back empty (nothing
     * scheduled today) or hold several (weekdays are not exclusive). Callers must
     * not treat an empty result as "nothing to read": every lane stays available
     * through {@link #list}.
     */
    @Transactional(readOnly = true)
    public List<RhythmLaneResponse> todayLanes(Long userId, ZoneId zone) {
        short today = (short) LocalDate.now(zone).getDayOfWeek().getValue();
        Set<Long> markedToday = markedTodayLaneIds(userId, zone);
        List<RhythmLaneResponse> out = new ArrayList<>();
        for (ReadingRhythm rhythm : rhythmRepo.findByUserIdOrderByCreatedAtAsc(userId)) {
            for (ReadingRhythmLane lane : rhythm.getLanes()) {
                if (lane.getDayOfWeek() != null && lane.getDayOfWeek() == today) {
                    out.add(toLaneResponse(lane, markedToday));
                }
            }
        }
        return out;
    }

    // ── Mutations ─────────────────────────────────────────────────────────────

    public RhythmResponse create(Long userId, String title, List<RhythmLaneSpec> lanes, ZoneId zone) {
        ReadingRhythm rhythm = new ReadingRhythm();
        rhythm.setUser(userRepo.getReferenceById(userId));
        rhythm.setTitle(validateTitle(title));
        applyLanes(rhythm, lanes);
        return toResponse(saveHandlingDuplicateTitle(rhythm), markedTodayLaneIds(userId, zone), zone);
    }

    /**
     * Replaces the rhythm's title and lane list.
     *
     * <p>A lane spec carrying an {@code id} that already belongs to this rhythm is
     * updated in place, so its cursor survives a reorder or a book-list edit. Specs
     * without an id create fresh lanes; omitted lanes are deleted via orphanRemoval.
     */
    public RhythmResponse update(Long userId, Long id, String title, List<RhythmLaneSpec> lanes, ZoneId zone) {
        ReadingRhythm rhythm = findOwned(userId, id);
        rhythm.setTitle(validateTitle(title));
        applyLanes(rhythm, lanes);
        rhythm.touch();
        return toResponse(saveHandlingDuplicateTitle(rhythm), markedTodayLaneIds(userId, zone), zone);
    }

    public void delete(Long userId, Long id) {
        rhythmRepo.delete(findOwned(userId, id));
    }

    /**
     * Records "I read {@code bookId} through chapter {@code throughChapter}" and moves
     * the lane cursor there.
     *
     * <p>Deliberately permissive: the book need only be somewhere in the lane, and the
     * target may sit behind the current cursor (correcting an over-eager mark). What it
     * never does is consult today's date — reading Friday's lane on a Tuesday is normal.
     */
    public RhythmLaneResponse markProgress(Long userId, Long laneId, MarkRhythmProgressRequest req) {
        ReadingRhythmLane lane = findOwnedLane(userId, laneId);

        int bookPosition = lane.getBookIds().indexOf(req.bookId());
        if (bookPosition < 0) {
            throw new BadRequestException("Book is not part of this lane");
        }
        Book book = bibleService.getBook(req.bookId())
                .orElseThrow(() -> new BadRequestException("Unknown book: " + req.bookId()));
        if (req.throughChapter() < 1 || req.throughChapter() > book.chapters()) {
            throw new BadRequestException(
                    book.name() + " has " + book.chapters() + " chapters — got " + req.throughChapter());
        }

        int before = chaptersRead(lane);
        lane.setCursorBookId(req.bookId());
        lane.setCursorChapter(req.throughChapter());
        int after = chaptersRead(lane);

        ReadingRhythmProgress event = new ReadingRhythmProgress();
        event.setUserId(userId);
        event.setLane(lane);
        event.setBookId(req.bookId());
        event.setThroughChapter(req.throughChapter());
        event.setChaptersDelta(Math.max(0, after - before));
        progressRepo.save(event);

        lane.getRhythm().touch();
        // This call is itself today's mark, so the response says so without re-querying.
        return toLaneResponse(lane, Set.of(lane.getId()));
    }

    /** Clears a lane's cursor so it starts over from its first book. */
    public RhythmLaneResponse restartLane(Long userId, Long laneId, ZoneId zone) {
        ReadingRhythmLane lane = findOwnedLane(userId, laneId);
        lane.resetCursor();
        lane.getRhythm().touch();
        // Restarting clears the cursor, not the history — today's marks still stand.
        return toLaneResponse(lane, markedTodayLaneIds(userId, zone));
    }

    // ── Lane assembly ─────────────────────────────────────────────────────────

    private void applyLanes(ReadingRhythm rhythm, List<RhythmLaneSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            throw new BadRequestException("A rhythm needs at least one lane");
        }
        if (specs.size() > MAX_LANES_PER_RHYTHM) {
            throw new BadRequestException("A rhythm may have at most " + MAX_LANES_PER_RHYTHM + " lanes");
        }

        Map<Long, ReadingRhythmLane> existing = new HashMap<>();
        for (ReadingRhythmLane lane : rhythm.getLanes()) {
            if (lane.getId() != null) existing.put(lane.getId(), lane);
        }

        // Two specs naming the same lane would resolve to one entity, get mutated
        // twice, and land in the list twice — the later spec silently winning and a
        // lane disappearing. Reject rather than half-apply.
        Set<Long> claimed = new HashSet<>();
        for (RhythmLaneSpec spec : specs) {
            if (spec.id() != null && !claimed.add(spec.id())) {
                throw new BadRequestException("The same lane was submitted twice");
            }
        }

        List<ReadingRhythmLane> rebuilt = new ArrayList<>();
        for (RhythmLaneSpec spec : specs) {
            ReadingRhythmLane lane = spec.id() != null ? existing.get(spec.id()) : null;
            if (lane == null) {
                lane = new ReadingRhythmLane();
                lane.setRhythm(rhythm);
            }
            lane.setPosition(rebuilt.size());
            lane.setName(validateLaneName(spec.name()));
            lane.setDayOfWeek(validateDayOfWeek(spec.dayOfWeek()));
            lane.getBookIds().clear();
            lane.getBookIds().addAll(validateBookIds(spec.bookIds()));
            applyCursor(lane, spec);
            rebuilt.add(lane);
        }

        // Replace in place so @OrderColumn positions are rewritten and dropped
        // lanes are orphan-removed.
        rhythm.getLanes().clear();
        rhythm.getLanes().addAll(rebuilt);
    }

    /**
     * Honours an explicit cursor from the builder's "set position" control, then
     * makes sure whatever cursor the lane carries still points at a book it owns —
     * removing the cursor book from a lane resets it to not-started rather than
     * leaving progress pointing into nothing.
     */
    private void applyCursor(ReadingRhythmLane lane, RhythmLaneSpec spec) {
        // An explicit "Not started" has to be distinguishable from an untouched
        // control, since both leave cursorBookId null.
        if (Boolean.TRUE.equals(spec.clearCursor())) {
            lane.resetCursor();
        } else if (spec.cursorBookId() != null) {
            int chapter = spec.cursorChapter() == null ? 0 : spec.cursorChapter();
            Book book = bibleService.getBook(spec.cursorBookId())
                    .orElseThrow(() -> new BadRequestException("Unknown book: " + spec.cursorBookId()));
            if (chapter < 0 || chapter > book.chapters()) {
                throw new BadRequestException(
                        book.name() + " has " + book.chapters() + " chapters — got " + chapter);
            }
            if (chapter == 0) {
                lane.resetCursor();
            } else {
                lane.setCursorBookId(spec.cursorBookId());
                lane.setCursorChapter(chapter);
            }
        }
        if (lane.getCursorBookId() != null && !lane.getBookIds().contains(lane.getCursorBookId())) {
            lane.resetCursor();
        }
    }

    // ── Derived reading position ──────────────────────────────────────────────

    /**
     * Chapters finished across the whole lane: every book before the cursor book
     * in full, plus the chapters read inside it.
     */
    int chaptersRead(ReadingRhythmLane lane) {
        if (!lane.isStarted()) return 0;
        int cursorPosition = lane.getBookIds().indexOf(lane.getCursorBookId());
        if (cursorPosition < 0) return 0;

        int total = 0;
        for (int i = 0; i < cursorPosition; i++) {
            total += chapterCount(lane.getBookIds().get(i));
        }
        return total + lane.getCursorChapter();
    }

    /**
     * The next chapter to read, rolling into the following book when the cursor sits
     * on a book's last chapter. Null once the lane is finished.
     */
    RhythmLaneResponse.RhythmNextReading nextReading(ReadingRhythmLane lane) {
        List<Integer> books = lane.getBookIds();
        if (books.isEmpty()) return null;

        int bookPosition;
        int chapter;
        if (!lane.isStarted()) {
            bookPosition = 0;
            chapter = 1;
        } else {
            bookPosition = books.indexOf(lane.getCursorBookId());
            if (bookPosition < 0) return firstChapterOf(books.get(0));  // stale cursor
            if (lane.getCursorChapter() < chapterCount(books.get(bookPosition))) {
                chapter = lane.getCursorChapter() + 1;
            } else {
                bookPosition++;
                chapter = 1;
                if (bookPosition >= books.size()) return null;          // lane complete
            }
        }

        int bookId = books.get(bookPosition);
        return chapterReading(bookId, chapter);
    }

    private RhythmLaneResponse.RhythmNextReading firstChapterOf(int bookId) {
        return chapterReading(bookId, 1);
    }

    private RhythmLaneResponse.RhythmNextReading chapterReading(int bookId, int chapter) {
        Book book = bibleService.getBook(bookId).orElse(null);
        if (book == null) return null;
        int firstVerseId = bibleService.getChapters(bookId).stream()
                .filter(c -> c.chapter() == chapter)
                .findFirst()
                .map(ChapterInfo::firstVerseId)
                .orElse(book.firstVerseId());
        return new RhythmLaneResponse.RhythmNextReading(bookId, book.name(), chapter, firstVerseId);
    }

    private int chapterCount(int bookId) {
        return bibleService.getBook(bookId).map(Book::chapters).orElse(0);
    }

    // ── Response mapping ──────────────────────────────────────────────────────

    private RhythmResponse toResponse(ReadingRhythm rhythm, Set<Long> markedToday, ZoneId zone) {
        short today = (short) LocalDate.now(zone).getDayOfWeek().getValue();
        List<RhythmLaneResponse> lanes = rhythm.getLanes().stream()
                .map(l -> toLaneResponse(l, markedToday))
                .toList();
        List<Long> todayLaneIds = rhythm.getLanes().stream()
                .filter(l -> l.getDayOfWeek() != null && l.getDayOfWeek() == today)
                .map(ReadingRhythmLane::getId)
                .toList();
        return new RhythmResponse(rhythm.getId(), rhythm.getTitle(), lanes, todayLaneIds,
                                  rhythm.getCreatedAt(), rhythm.getUpdatedAt());
    }

    /** Package-private so tests can assert the derived position without a repository round trip. */
    RhythmLaneResponse toLaneResponse(ReadingRhythmLane lane, Set<Long> markedToday) {
        int cursorPosition = lane.isStarted() ? lane.getBookIds().indexOf(lane.getCursorBookId()) : -1;

        List<RhythmLaneResponse.RhythmLaneBook> books = new ArrayList<>();
        int chaptersTotal = 0;
        for (int i = 0; i < lane.getBookIds().size(); i++) {
            int bookId = lane.getBookIds().get(i);
            Book book = bibleService.getBook(bookId).orElse(null);
            if (book == null) continue;
            chaptersTotal += book.chapters();

            int read;
            if (cursorPosition < 0 || i > cursorPosition) read = 0;
            else if (i < cursorPosition)                  read = book.chapters();
            else                                          read = lane.getCursorChapter();

            books.add(new RhythmLaneResponse.RhythmLaneBook(bookId, book.name(), book.chapters(), read));
        }

        RhythmLaneResponse.RhythmNextReading next = nextReading(lane);
        return new RhythmLaneResponse(
                lane.getId(),
                lane.getName(),
                lane.getDayOfWeek(),
                lane.getCursorBookId(),
                lane.getCursorChapter(),
                next,
                chaptersRead(lane),
                chaptersTotal,
                next == null && !lane.getBookIds().isEmpty(),
                lane.getId() != null && markedToday.contains(lane.getId()),
                books
        );
    }

    // ── Validation & lookup ───────────────────────────────────────────────────

    private ReadingRhythm findOwned(Long userId, Long id) {
        return rhythmRepo.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Rhythm not found"));
    }

    private ReadingRhythmLane findOwnedLane(Long userId, Long laneId) {
        return laneRepo.findByIdAndUserId(laneId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Lane not found"));
    }

    private String validateTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new BadRequestException("Title is required");
        }
        String trimmed = title.trim();
        if (trimmed.length() > MAX_TITLE_LENGTH) {
            throw new BadRequestException("Title must be " + MAX_TITLE_LENGTH + " characters or fewer");
        }
        return trimmed;
    }

    private String validateLaneName(String name) {
        if (name == null || name.isBlank()) {
            throw new BadRequestException("Every lane needs a name");
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_LANE_NAME_LENGTH) {
            throw new BadRequestException("Lane name must be " + MAX_LANE_NAME_LENGTH + " characters or fewer");
        }
        return trimmed;
    }

    /** Null stays null — an unscheduled lane is valid and simply never auto-surfaces. */
    private Short validateDayOfWeek(Short dayOfWeek) {
        if (dayOfWeek == null) return null;
        if (dayOfWeek < DayOfWeek.MONDAY.getValue() || dayOfWeek > DayOfWeek.SUNDAY.getValue()) {
            throw new BadRequestException("dayOfWeek must be 1 (Monday) through 7 (Sunday)");
        }
        return dayOfWeek;
    }

    private List<Integer> validateBookIds(List<Integer> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) {
            throw new BadRequestException("Every lane needs at least one book");
        }
        if (bookIds.size() > MAX_BOOKS_PER_LANE) {
            throw new BadRequestException("A lane may hold at most " + MAX_BOOKS_PER_LANE + " books");
        }
        Set<Integer> seen = new HashSet<>();
        for (Integer bookId : bookIds) {
            if (bookId == null || bibleService.getBook(bookId).isEmpty()) {
                throw new BadRequestException("Unknown book: " + bookId);
            }
            // The cursor is keyed on book id, so a second occurrence is
            // indistinguishable from the first: nextReading would advance into it
            // while chaptersRead resolved back to the earlier one, stranding the
            // reader. Reject rather than make the cursor occurrence-aware — a lane
            // listing the same book twice has no meaning for this feature.
            if (!seen.add(bookId)) {
                String name = bibleService.getBook(bookId).map(Book::name).orElse("Book " + bookId);
                throw new BadRequestException(name + " is already in this lane");
            }
        }
        return bookIds;
    }

    /**
     * Only the title-uniqueness violation is a user-correctable input problem. Any
     * other constraint failure is a bug in this service — rethrow it so it surfaces
     * as a 500 with a stack trace instead of being disguised as bad user input.
     */
    private ReadingRhythm saveHandlingDuplicateTitle(ReadingRhythm rhythm) {
        try {
            return rhythmRepo.saveAndFlush(rhythm);
        } catch (DataIntegrityViolationException e) {
            String detail = e.getMostSpecificCause().getMessage();
            if (detail != null && detail.contains("uq_reading_rhythms_user_title")) {
                throw new BadRequestException(
                        "You already have a rhythm named \"" + rhythm.getTitle() + "\"");
            }
            throw e;
        }
    }
}
