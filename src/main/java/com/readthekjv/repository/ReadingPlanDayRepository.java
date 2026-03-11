package com.readthekjv.repository;

import com.readthekjv.model.entity.ReadingPlanDay;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReadingPlanDayRepository extends JpaRepository<ReadingPlanDay, UUID> {

    List<ReadingPlanDay> findByPlanIdOrderByDayNumberAsc(UUID planId);

    Optional<ReadingPlanDay> findByPlanIdAndDayNumber(UUID planId, int dayNumber);
}
