package com.readthekjv.repository;

import com.readthekjv.model.entity.ReadingPlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ReadingPlanRepository extends JpaRepository<ReadingPlan, UUID> {

    Optional<ReadingPlan> findBySlug(String slug);
}
