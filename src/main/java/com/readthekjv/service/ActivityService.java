package com.readthekjv.service;

import com.readthekjv.repository.ReadingPlanCompletionRepository;
import com.readthekjv.repository.ReviewHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.TreeMap;

@Service
@Transactional(readOnly = true)
public class ActivityService {

    private final ReviewHistoryRepository         reviewRepo;
    private final ReadingPlanCompletionRepository completionRepo;

    public ActivityService(ReviewHistoryRepository reviewRepo,
                           ReadingPlanCompletionRepository completionRepo) {
        this.reviewRepo     = reviewRepo;
        this.completionRepo = completionRepo;
    }

    /**
     * Returns a date → activity-count map for the past 365 days.
     * Each memorization review and each reading-plan day completion counts as 1 activity.
     * Only dates with at least 1 activity are included; the client fills in zeros.
     * Dates are returned in ascending order (TreeMap).
     */
    public Map<String, Integer> getHeatmap(Long userId) {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusDays(365);

        Map<String, Integer> counts = new TreeMap<>();

        reviewRepo.findByUserIdAndReviewedAtAfter(userId, cutoff)
                .forEach(r -> {
                    String day = r.getReviewedAt().toLocalDate().toString();
                    counts.merge(day, 1, Integer::sum);
                });

        completionRepo.findByUserIdAndCompletedAtAfter(userId, cutoff)
                .forEach(c -> {
                    String day = c.getCompletedAt().toLocalDate().toString();
                    counts.merge(day, 1, Integer::sum);
                });

        return counts;
    }
}
