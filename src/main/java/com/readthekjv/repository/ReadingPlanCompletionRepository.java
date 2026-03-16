package com.readthekjv.repository;

import com.readthekjv.model.entity.ReadingPlanCompletion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReadingPlanCompletionRepository extends JpaRepository<ReadingPlanCompletion, UUID> {

    List<ReadingPlanCompletion> findByUserIdAndPlanIdOrderByCompletedAtAsc(Long userId, UUID planId);

    Optional<ReadingPlanCompletion> findByUserIdAndPlanIdAndDayNumber(Long userId, UUID planId, int dayNumber);

    List<ReadingPlanCompletion> findByUserIdAndCompletedAtAfter(Long userId, OffsetDateTime after);
}
