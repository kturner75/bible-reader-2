package com.readthekjv.service;

import com.readthekjv.model.entity.ReadingRhythmProgress;
import com.readthekjv.repository.ReadingPlanCompletionRepository;
import com.readthekjv.repository.ReadingRhythmProgressRepository;
import com.readthekjv.repository.ReviewHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ActivityServiceTest {

    private static final Long USER_ID = 42L;

    private ReviewHistoryRepository          reviewRepo;
    private ReadingPlanCompletionRepository  completionRepo;
    private ReadingRhythmProgressRepository  rhythmProgressRepo;
    private ActivityService                  service;

    @BeforeEach
    void setUp() {
        reviewRepo         = mock(ReviewHistoryRepository.class);
        completionRepo     = mock(ReadingPlanCompletionRepository.class);
        rhythmProgressRepo = mock(ReadingRhythmProgressRepository.class);
        service = new ActivityService(reviewRepo, completionRepo, rhythmProgressRepo);

        when(reviewRepo.findByUserIdAndReviewedAtAfter(eq(USER_ID), any())).thenReturn(List.of());
        when(completionRepo.findByUserIdAndCompletedAtAfter(eq(USER_ID), any())).thenReturn(List.of());
        when(rhythmProgressRepo.findByUserIdAndCompletedAtAfter(eq(USER_ID), any())).thenReturn(List.of());
    }

    private ReadingRhythmProgress progressAt(OffsetDateTime when) {
        ReadingRhythmProgress p = new ReadingRhythmProgress();
        ReflectionTestUtils.setField(p, "completedAt", when);
        return p;
    }

    @Test
    void rhythmProgressIsBucketedByTheCallersCalendarDay() {
        // 2026-08-08T02:30Z is still the evening of the 7th in New York. A reader
        // there must see the square on the 7th, not tomorrow's.
        when(rhythmProgressRepo.findByUserIdAndCompletedAtAfter(eq(USER_ID), any()))
                .thenReturn(List.of(progressAt(OffsetDateTime.parse("2026-08-08T02:30:00Z"))));

        Map<String, Integer> newYork = service.getHeatmap(USER_ID, ZoneId.of("America/New_York"));
        Map<String, Integer> utc     = service.getHeatmap(USER_ID, ZoneOffset.UTC);

        assertEquals(Map.of("2026-08-07", 1), newYork, "should land on the reader's local day");
        assertEquals(Map.of("2026-08-08", 1), utc, "UTC caller sees the UTC day");
    }

    @Test
    void countsFromAllThreeSourcesMergeOnTheSameDay() {
        OffsetDateTime noonUtc = OffsetDateTime.parse("2026-08-07T12:00:00Z");
        when(rhythmProgressRepo.findByUserIdAndCompletedAtAfter(eq(USER_ID), any()))
                .thenReturn(List.of(progressAt(noonUtc), progressAt(noonUtc)));

        Map<String, Integer> heat = service.getHeatmap(USER_ID, ZoneOffset.UTC);

        assertEquals(2, heat.get("2026-08-07"));
        assertTrue(heat.size() == 1, "only active days are returned");
    }
}
