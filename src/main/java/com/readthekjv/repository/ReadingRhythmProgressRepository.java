package com.readthekjv.repository;

import com.readthekjv.model.entity.ReadingRhythmProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

public interface ReadingRhythmProgressRepository extends JpaRepository<ReadingRhythmProgress, Long> {

    /** Heatmap source — mirrors ReviewHistoryRepository / ReadingPlanCompletionRepository. */
    List<ReadingRhythmProgress> findByUserIdAndCompletedAtAfter(Long userId, OffsetDateTime after);

    /**
     * Lane ids the user has marked since {@code after} — one query for the whole
     * dashboard rather than a per-lane existence check.
     */
    @Query("select distinct p.lane.id from ReadingRhythmProgress p "
         + "where p.userId = :userId and p.completedAt >= :after")
    Set<Long> findLaneIdsMarkedSince(Long userId, OffsetDateTime after);
}
