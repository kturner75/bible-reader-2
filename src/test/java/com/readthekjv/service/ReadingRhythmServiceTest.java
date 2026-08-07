package com.readthekjv.service;

import com.readthekjv.exception.BadRequestException;
import com.readthekjv.model.Verse;
import com.readthekjv.model.dto.MarkRhythmProgressRequest;
import com.readthekjv.model.dto.RhythmLaneResponse;
import com.readthekjv.model.dto.RhythmLaneSpec;
import com.readthekjv.model.dto.RhythmResponse;
import com.readthekjv.model.entity.ReadingRhythm;
import com.readthekjv.model.entity.ReadingRhythmLane;
import com.readthekjv.model.entity.ReadingRhythmProgress;
import com.readthekjv.model.entity.User;
import com.readthekjv.repository.ReadingRhythmLaneRepository;
import com.readthekjv.repository.ReadingRhythmProgressRepository;
import com.readthekjv.repository.ReadingRhythmRepository;
import com.readthekjv.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Fixture mirrors the user's spreadsheet Sunday lane —
 * Matthew(28) Mark(16) Luke(24) John(21) Acts(28) Romans(16), cursor at Luke 7.
 *
 * Book ids here are assigned by BibleService in canonical order among the books
 * that actually carry verses, so they are 1..6 rather than 40..45. Tests resolve
 * them by name rather than hardcoding.
 */
class ReadingRhythmServiceTest {

    private static final Long USER_ID  = 42L;
    private static final Long OTHER_ID = 99L;
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private ReadingRhythmRepository         rhythmRepo;
    private ReadingRhythmLaneRepository     laneRepo;
    private ReadingRhythmProgressRepository progressRepo;
    private UserRepository                  userRepo;
    private BibleService                    bibleService;
    private ReadingRhythmService            service;

    /** Chapter counts of the Sunday lane, in order. */
    private static final Map<String, Integer> SUNDAY = new LinkedHashMap<>(Map.of());
    static {
        SUNDAY.put("Matthew", 28);
        SUNDAY.put("Mark",    16);
        SUNDAY.put("Luke",    24);
        SUNDAY.put("John",    21);
        SUNDAY.put("Acts",    28);
        SUNDAY.put("Romans",  16);
    }

    @BeforeEach
    void setUp() {
        rhythmRepo   = mock(ReadingRhythmRepository.class);
        laneRepo     = mock(ReadingRhythmLaneRepository.class);
        progressRepo = mock(ReadingRhythmProgressRepository.class);
        userRepo     = mock(UserRepository.class);

        bibleService = new BibleService();
        bibleService.loadVerses(buildVerses());

        service = new ReadingRhythmService(rhythmRepo, laneRepo, progressRepo, userRepo, bibleService);

        when(userRepo.getReferenceById(USER_ID)).thenReturn(new User());
        when(rhythmRepo.saveAndFlush(any(ReadingRhythm.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** Two verses per chapter is enough — only chapter counts and first-verse ids matter here. */
    private List<Verse> buildVerses() {
        List<Verse> verses = new ArrayList<>();
        int id = 1;
        int bookId = 1;
        for (Map.Entry<String, Integer> book : SUNDAY.entrySet()) {
            for (int chapter = 1; chapter <= book.getValue(); chapter++) {
                verses.add(new Verse(id++, book.getKey(), bookId, chapter, 1, "verse one"));
                verses.add(new Verse(id++, book.getKey(), bookId, chapter, 2, "verse two"));
            }
            bookId++;
        }
        return verses;
    }

    private int bookId(String name) {
        return bibleService.getBookByName(name).orElseThrow().id();
    }

    private List<Integer> sundayBookIds() {
        return SUNDAY.keySet().stream().map(this::bookId).toList();
    }

    /** A persisted-looking lane, since the mocked repo never assigns ids. */
    private ReadingRhythmLane lane(Long id, String name, Short dayOfWeek,
                                   List<Integer> bookIds, Integer cursorBookId, int cursorChapter) {
        ReadingRhythm rhythm = new ReadingRhythm();
        rhythm.setUser(new User());
        rhythm.setTitle("Weekly Rhythm");

        ReadingRhythmLane lane = new ReadingRhythmLane();
        lane.setId(id);
        lane.setRhythm(rhythm);
        lane.setName(name);
        lane.setDayOfWeek(dayOfWeek);
        lane.getBookIds().addAll(bookIds);
        lane.setCursorBookId(cursorBookId);
        lane.setCursorChapter(cursorChapter);
        rhythm.getLanes().add(lane);
        return lane;
    }

    /** The spreadsheet's Sunday lane: cursor at Luke 7. */
    private ReadingRhythmLane sundayLane() {
        return lane(1L, "Sunday", (short) DayOfWeek.SUNDAY.getValue(),
                    sundayBookIds(), bookId("Luke"), 7);
    }

    // ── Derived position ──────────────────────────────────────────────────────

    @Test
    void chaptersReadMatchesTheSpreadsheet() {
        RhythmLaneResponse resp = service.toLaneResponse(sundayLane(), Set.of());

        // Matthew 28 + Mark 16 + Luke 7 = 51 read; 133 total; 82 remaining (sheet row 24).
        assertEquals(51, resp.chaptersRead());
        assertEquals(133, resp.chaptersTotal());
        assertEquals(82, resp.chaptersTotal() - resp.chaptersRead());
    }

    @Test
    void perBookBreakdownMirrorsTheSheetsChaptersReadColumn() {
        List<RhythmLaneResponse.RhythmLaneBook> books = service.toLaneResponse(sundayLane(), Set.of()).books();

        assertEquals(28, books.get(0).chaptersRead(), "Matthew complete");
        assertEquals(16, books.get(1).chaptersRead(), "Mark complete");
        assertEquals(7,  books.get(2).chaptersRead(), "Luke partial");
        assertEquals(0,  books.get(3).chaptersRead(), "John untouched");
        assertEquals(0,  books.get(4).chaptersRead(), "Acts untouched");
        assertEquals(0,  books.get(5).chaptersRead(), "Romans untouched");
    }

    @Test
    void nextReadingIsTheChapterAfterTheCursor() {
        RhythmLaneResponse.RhythmNextReading next = service.toLaneResponse(sundayLane(), Set.of()).nextReading();

        assertNotNull(next);
        assertEquals("Luke", next.bookName());
        assertEquals(8, next.chapter());
        assertEquals(bibleService.getChapters(bookId("Luke")).get(7).firstVerseId(), next.firstVerseId());
    }

    @Test
    void nextReadingRollsIntoTheFollowingBookAtABookBoundary() {
        // Cursor sits on Luke's last chapter — next is John 1, not Luke 25.
        RhythmLaneResponse.RhythmNextReading next = service.toLaneResponse(
                lane(1L, "Sunday", (short) 7, sundayBookIds(), bookId("Luke"), 24), Set.of()).nextReading();

        assertNotNull(next);
        assertEquals("John", next.bookName());
        assertEquals(1, next.chapter());
    }

    @Test
    void anUnstartedLaneBeginsAtItsFirstBook() {
        RhythmLaneResponse resp = service.toLaneResponse(
                lane(1L, "Sunday", (short) 7, sundayBookIds(), null, 0), Set.of());

        assertEquals(0, resp.chaptersRead());
        assertFalse(resp.complete());
        assertEquals("Matthew", resp.nextReading().bookName());
        assertEquals(1, resp.nextReading().chapter());
    }

    @Test
    void laneIsCompleteAtTheLastChapterOfTheLastBook() {
        RhythmLaneResponse resp = service.toLaneResponse(
                lane(1L, "Sunday", (short) 7, sundayBookIds(), bookId("Romans"), 16), Set.of());

        assertTrue(resp.complete());
        assertNull(resp.nextReading());
        assertEquals(133, resp.chaptersRead());
        assertEquals(resp.chaptersTotal(), resp.chaptersRead());
    }

    // ── markedToday (feeds the dashboard's Today's Reading card) ──────────────

    @Test
    void markedTodayReflectsWhetherTheLaneIsInTodaysMarkedSet() {
        assertFalse(service.toLaneResponse(sundayLane(), Set.of()).markedToday());
        assertTrue(service.toLaneResponse(sundayLane(), Set.of(1L)).markedToday());
        assertFalse(service.toLaneResponse(sundayLane(), Set.of(99L)).markedToday(),
                "another lane's mark must not satisfy this one");
    }

    @Test
    void markProgressReportsTheLaneAsMarkedToday() {
        ReadingRhythmLane lane = sundayLane();
        when(laneRepo.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.of(lane));

        RhythmLaneResponse resp = service.markProgress(
                USER_ID, 1L, new MarkRhythmProgressRequest(bookId("Luke"), 8));

        assertTrue(resp.markedToday(), "the call that records progress is itself today's mark");
    }

    @Test
    void markedTodaySurvivesARestart() {
        // Restart clears the cursor, not the history — the card should stay settled.
        ReadingRhythmLane lane = sundayLane();
        when(laneRepo.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.of(lane));
        when(progressRepo.findLaneIdsMarkedSince(eq(USER_ID), any(OffsetDateTime.class)))
                .thenReturn(Set.of(1L));

        RhythmLaneResponse resp = service.restartLane(USER_ID, 1L, ZONE);

        assertNull(resp.cursorBookId());
        assertTrue(resp.markedToday());
    }

    // ── markProgress ──────────────────────────────────────────────────────────

    @Test
    void markProgressAdvancesTheCursorAndLogsTheDelta() {
        ReadingRhythmLane lane = sundayLane();
        when(laneRepo.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.of(lane));

        RhythmLaneResponse resp = service.markProgress(
                USER_ID, 1L, new MarkRhythmProgressRequest(bookId("Luke"), 10));

        assertEquals(10, lane.getCursorChapter());
        assertEquals(54, resp.chaptersRead());
        assertEquals(11, resp.nextReading().chapter());

        ArgumentCaptor<ReadingRhythmProgress> saved = ArgumentCaptor.forClass(ReadingRhythmProgress.class);
        verify(progressRepo).save(saved.capture());
        assertEquals(3, saved.getValue().getChaptersDelta());
        assertEquals(10, saved.getValue().getThroughChapter());
    }

    @Test
    void markProgressSucceedsOnALaneScheduledForADifferentDay() {
        // The weekday is a surfacing hint only — nothing gates the mutation on today.
        short notToday = (short) LocalDate.now().getDayOfWeek().plus(3).getValue();
        ReadingRhythmLane lane = lane(1L, "Friday", notToday, sundayBookIds(), bookId("Luke"), 7);
        when(laneRepo.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.of(lane));

        RhythmLaneResponse resp = service.markProgress(
                USER_ID, 1L, new MarkRhythmProgressRequest(bookId("Luke"), 9));

        assertEquals(9, resp.cursorChapter());
        verify(progressRepo).save(any(ReadingRhythmProgress.class));
    }

    @Test
    void markProgressSucceedsOnAnUnscheduledLane() {
        ReadingRhythmLane lane = lane(1L, "Gospels", null, sundayBookIds(), null, 0);
        when(laneRepo.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.of(lane));

        RhythmLaneResponse resp = service.markProgress(
                USER_ID, 1L, new MarkRhythmProgressRequest(bookId("Matthew"), 4));

        assertEquals(4, resp.chaptersRead());
        assertNull(resp.dayOfWeek());
    }

    @Test
    void markProgressAllowsABackwardCorrectionAndLogsZeroDelta() {
        ReadingRhythmLane lane = sundayLane();
        when(laneRepo.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.of(lane));

        RhythmLaneResponse resp = service.markProgress(
                USER_ID, 1L, new MarkRhythmProgressRequest(bookId("Mark"), 10));

        assertEquals(38, resp.chaptersRead());   // Matthew 28 + Mark 10
        assertEquals(0, resp.books().get(2).chaptersRead(), "Luke rolled back to unread");

        ArgumentCaptor<ReadingRhythmProgress> saved = ArgumentCaptor.forClass(ReadingRhythmProgress.class);
        verify(progressRepo).save(saved.capture());
        assertEquals(0, saved.getValue().getChaptersDelta());
    }

    @Test
    void markProgressRejectsABookOutsideTheLane() {
        ReadingRhythmLane lane = lane(1L, "Sunday", (short) 7,
                                      List.of(bookId("Matthew"), bookId("Mark")), null, 0);
        when(laneRepo.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.of(lane));

        assertThrows(BadRequestException.class, () -> service.markProgress(
                USER_ID, 1L, new MarkRhythmProgressRequest(bookId("Romans"), 1)));
        verify(progressRepo, never()).save(any());
    }

    @Test
    void markProgressRejectsAChapterBeyondTheBook() {
        ReadingRhythmLane lane = sundayLane();
        when(laneRepo.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.of(lane));

        assertThrows(BadRequestException.class, () -> service.markProgress(
                USER_ID, 1L, new MarkRhythmProgressRequest(bookId("Luke"), 25)));
    }

    @Test
    void markProgressRejectsAnotherUsersLane() {
        when(laneRepo.findByIdAndUserId(1L, OTHER_ID)).thenReturn(Optional.empty());

        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.markProgress(OTHER_ID, 1L,
                        new MarkRhythmProgressRequest(bookId("Luke"), 8)));
        verify(progressRepo, never()).save(any());
    }

    @Test
    void restartLaneClearsTheCursor() {
        ReadingRhythmLane lane = sundayLane();
        when(laneRepo.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.of(lane));

        RhythmLaneResponse resp = service.restartLane(USER_ID, 1L, ZONE);

        assertNull(resp.cursorBookId());
        assertEquals(0, resp.chaptersRead());
        assertEquals("Matthew", resp.nextReading().bookName());
    }

    // ── create / update ───────────────────────────────────────────────────────

    @Test
    void createBuildsLanesInOrderWithTheirWeekdays() {
        RhythmResponse resp = service.create(USER_ID, "Weekly Rhythm", List.of(
                new RhythmLaneSpec(null, "Sunday", (short) 7, sundayBookIds(), null, null),
                new RhythmLaneSpec(null, "Gospels", null, List.of(bookId("Mark")), null, null)), ZONE);

        assertEquals(2, resp.lanes().size());
        assertEquals("Sunday", resp.lanes().get(0).name());
        assertEquals((short) 7, resp.lanes().get(0).dayOfWeek());
        assertNull(resp.lanes().get(1).dayOfWeek(), "unscheduled lane stays unscheduled");
    }

    @Test
    void createAssignsLanePositionsFromTheirOrder() {
        // position is NOT NULL and written by the service (not @OrderColumn), so an
        // unset value fails the INSERT — which mocked repositories cannot reveal.
        ArgumentCaptor<ReadingRhythm> saved = ArgumentCaptor.forClass(ReadingRhythm.class);
        service.create(USER_ID, "Weekly Rhythm", List.of(
                new RhythmLaneSpec(null, "Sunday",  (short) 7, sundayBookIds(), null, null),
                new RhythmLaneSpec(null, "Monday",  (short) 1, sundayBookIds(), null, null),
                new RhythmLaneSpec(null, "Tuesday", (short) 2, sundayBookIds(), null, null)), ZONE);

        verify(rhythmRepo).saveAndFlush(saved.capture());
        List<ReadingRhythmLane> lanes = saved.getValue().getLanes();
        assertEquals(List.of(0, 1, 2), lanes.stream().map(ReadingRhythmLane::getPosition).toList());
    }

    @Test
    void updateRenumbersLanePositionsAfterAReorder() {
        ReadingRhythm rhythm = sundayLane().getRhythm();
        when(rhythmRepo.findByIdAndUserId(7L, USER_ID)).thenReturn(Optional.of(rhythm));

        service.update(USER_ID, 7L, "Weekly Rhythm", List.of(
                new RhythmLaneSpec(null, "Monday", (short) 1, sundayBookIds(), null, null),
                new RhythmLaneSpec(1L,   "Sunday", (short) 7, sundayBookIds(), null, null)), ZONE);

        assertEquals(List.of(0, 1),
                rhythm.getLanes().stream().map(ReadingRhythmLane::getPosition).toList());
        assertEquals("Sunday", rhythm.getLanes().get(1).getName(), "moved lane renumbered");
    }

    @Test
    void createHonoursAnExplicitStartingPosition() {
        RhythmResponse resp = service.create(USER_ID, "Weekly Rhythm", List.of(
                new RhythmLaneSpec(null, "Sunday", (short) 7, sundayBookIds(), bookId("Luke"), 7)), ZONE);

        assertEquals(51, resp.lanes().get(0).chaptersRead());
        assertEquals(8, resp.lanes().get(0).nextReading().chapter());
    }

    @Test
    void updatePreservesTheCursorWhenBooksAreReordered() {
        ReadingRhythm rhythm = sundayLane().getRhythm();
        when(rhythmRepo.findByIdAndUserId(7L, USER_ID)).thenReturn(Optional.of(rhythm));

        // Move John ahead of Luke; the lane spec carries the existing lane id.
        List<Integer> reordered = List.of(bookId("Matthew"), bookId("Mark"), bookId("John"),
                                          bookId("Luke"), bookId("Acts"), bookId("Romans"));
        RhythmResponse resp = service.update(USER_ID, 7L, "Weekly Rhythm", List.of(
                new RhythmLaneSpec(1L, "Sunday", (short) 7, reordered, null, null)), ZONE);

        RhythmLaneResponse lane = resp.lanes().get(0);
        assertEquals(bookId("Luke"), lane.cursorBookId(), "cursor survives the reorder");
        assertEquals(7, lane.cursorChapter());
        // Luke now sits after John, so completed-books total grows by John's 21.
        assertEquals(72, lane.chaptersRead());
        assertEquals(8, lane.nextReading().chapter());
    }

    @Test
    void updateResetsTheCursorWhenItsBookIsRemovedFromTheLane() {
        ReadingRhythm rhythm = sundayLane().getRhythm();
        when(rhythmRepo.findByIdAndUserId(7L, USER_ID)).thenReturn(Optional.of(rhythm));

        List<Integer> withoutLuke = List.of(bookId("Matthew"), bookId("Mark"),
                                            bookId("John"), bookId("Acts"), bookId("Romans"));
        RhythmResponse resp = service.update(USER_ID, 7L, "Weekly Rhythm", List.of(
                new RhythmLaneSpec(1L, "Sunday", (short) 7, withoutLuke, null, null)), ZONE);

        RhythmLaneResponse lane = resp.lanes().get(0);
        assertNull(lane.cursorBookId());
        assertEquals(0, lane.chaptersRead());
        assertEquals("Matthew", lane.nextReading().bookName());
    }

    @Test
    void updateRejectsAnotherUsersRhythm() {
        when(rhythmRepo.findByIdAndUserId(7L, OTHER_ID)).thenReturn(Optional.empty());

        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> service.update(OTHER_ID, 7L, "Weekly Rhythm", List.of(
                        new RhythmLaneSpec(null, "Sunday", (short) 7, sundayBookIds(), null, null)), ZONE));
    }

    @Test
    void createRejectsALaneWithADuplicateBook() {
        // The cursor is keyed on book id, so a repeat is indistinguishable from the
        // first occurrence: nextReading would advance into it while chaptersRead
        // resolved back, stranding the reader mid-lane.
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> service.create(USER_ID, "Weekly Rhythm", List.of(
                        new RhythmLaneSpec(null, "Sunday", (short) 7,
                                List.of(bookId("Matthew"), bookId("Mark"), bookId("Matthew")),
                                null, null)), ZONE));
        assertTrue(e.getMessage().contains("Matthew"), e.getMessage());
    }

    @Test
    void markedTodayUsesTheCallersZoneNotTheServers() {
        ReadingRhythmLane lane = sundayLane();
        when(laneRepo.findByIdAndUserId(1L, USER_ID)).thenReturn(Optional.of(lane));

        ArgumentCaptor<OffsetDateTime> since = ArgumentCaptor.forClass(OffsetDateTime.class);
        when(progressRepo.findLaneIdsMarkedSince(eq(USER_ID), since.capture()))
                .thenReturn(Set.of());

        ZoneId tokyo = ZoneId.of("Asia/Tokyo");
        service.restartLane(USER_ID, 1L, tokyo);

        assertEquals(LocalDate.now(tokyo).atStartOfDay(tokyo).toOffsetDateTime(),
                     since.getValue(),
                     "day boundary must follow the caller's zone, not the server's");
    }

    @Test
    void resolveZoneFallsBackToTheServerZoneRatherThanFailing() {
        assertEquals(ZoneId.of("Asia/Tokyo"), ReadingRhythmService.resolveZone("Asia/Tokyo"));
        assertEquals(ZoneId.systemDefault(), ReadingRhythmService.resolveZone(null));
        assertEquals(ZoneId.systemDefault(), ReadingRhythmService.resolveZone(""));
        assertEquals(ZoneId.systemDefault(), ReadingRhythmService.resolveZone("Not/AZone"));
    }

    @Test
    void createRejectsALaneWithNoBooks() {
        assertThrows(BadRequestException.class, () -> service.create(USER_ID, "Weekly Rhythm",
                List.of(new RhythmLaneSpec(null, "Sunday", (short) 7, List.of(), null, null)), ZONE));
    }

    @Test
    void createRejectsAnOutOfRangeWeekday() {
        assertThrows(BadRequestException.class, () -> service.create(USER_ID, "Weekly Rhythm",
                List.of(new RhythmLaneSpec(null, "Sunday", (short) 8, sundayBookIds(), null, null)), ZONE));
    }
}
