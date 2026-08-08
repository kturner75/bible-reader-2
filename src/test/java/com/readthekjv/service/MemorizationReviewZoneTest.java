package com.readthekjv.service;

import com.readthekjv.model.Verse;
import com.readthekjv.model.entity.MemorizationEntry;
import com.readthekjv.model.entity.Passage;
import com.readthekjv.model.entity.User;
import com.readthekjv.repository.MemorizationEntryRepository;
import com.readthekjv.repository.PassageRepository;
import com.readthekjv.repository.ReviewHistoryRepository;
import com.readthekjv.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * nextReviewAt is compared client-side against the *reader's* today, so it has to
 * be scheduled from the reader's day too. Scheduling from the server's day makes an
 * "Again" review either immediately due again (reader ahead of the server) or
 * delayed an extra day (reader behind).
 */
class MemorizationReviewZoneTest {

    private static final Long USER_ID  = 42L;
    private static final UUID ENTRY_ID = UUID.randomUUID();

    private MemorizationEntryRepository entryRepo;
    private UserRepository              userRepo;
    private MemorizationService         service;
    private MemorizationEntry           entry;

    @BeforeEach
    void setUp() {
        entryRepo = mock(MemorizationEntryRepository.class);
        userRepo  = mock(UserRepository.class);
        PassageRepository       passageRepo = mock(PassageRepository.class);
        ReviewHistoryRepository historyRepo = mock(ReviewHistoryRepository.class);

        // Constructed, not mocked — Mockito cannot modify this class on this JDK,
        // and the other service tests build a real one the same way.
        BibleService bibleService = new BibleService();
        bibleService.loadVerses(List.of(
                new Verse(1, "Genesis", 1, 1, 1, "In the beginning...")
        ));
        service = new MemorizationService(entryRepo, passageRepo, userRepo, bibleService, historyRepo);

        User user = new User();
        ReflectionTestUtils.setField(user, "id", USER_ID);

        Passage passage = new Passage();
        ReflectionTestUtils.setField(passage, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(passage, "fromVerseId", 1);
        ReflectionTestUtils.setField(passage, "toVerseId", 1);

        entry = new MemorizationEntry();
        ReflectionTestUtils.setField(entry, "id", ENTRY_ID);
        ReflectionTestUtils.setField(entry, "user", user);
        ReflectionTestUtils.setField(entry, "passage", passage);

        when(entryRepo.findById(ENTRY_ID)).thenReturn(Optional.of(entry));
        when(entryRepo.save(any(MemorizationEntry.class))).thenAnswer(i -> i.getArgument(0));
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(user));
    }

    @Test
    void nextReviewIsScheduledFromTheCallersCalendarDay() {
        ZoneId tokyo   = ZoneId.of("Asia/Tokyo");
        ZoneId honolulu = ZoneId.of("Pacific/Honolulu");   // 19 hours behind Tokyo

        service.submitReview(USER_ID, ENTRY_ID, 0, tokyo);   // 0 = Again -> interval 1
        LocalDate fromTokyo = entry.getNextReviewAt();

        service.submitReview(USER_ID, ENTRY_ID, 0, honolulu);
        LocalDate fromHonolulu = entry.getNextReviewAt();

        assertEquals(LocalDate.now(tokyo).plusDays(1), fromTokyo,
                "Tokyo caller schedules from Tokyo's today");
        assertEquals(LocalDate.now(honolulu).plusDays(1), fromHonolulu,
                "Honolulu caller schedules from Honolulu's today");
    }

    @Test
    void anAgainReviewIsNotStillDueOnTheCallersToday() {
        // The failure this guards: scheduling from a server day already behind the
        // reader's produced nextReviewAt == the reader's today, so the passage the
        // user had just reviewed stayed in the due list.
        for (String zoneId : new String[]{"Asia/Tokyo", "Europe/London", "America/Chicago", "Pacific/Honolulu"}) {
            ZoneId zone = ZoneId.of(zoneId);
            service.submitReview(USER_ID, ENTRY_ID, 0, zone);
            LocalDate callerToday = LocalDate.now(zone);
            assertEquals(callerToday.plusDays(1), entry.getNextReviewAt(), zoneId);
            org.junit.jupiter.api.Assertions.assertTrue(
                    entry.getNextReviewAt().isAfter(callerToday),
                    zoneId + ": a just-reviewed passage must not still be due today");
        }
    }
}
