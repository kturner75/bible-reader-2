package com.readthekjv.repository;

import com.readthekjv.model.entity.ReadingRhythm;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReadingRhythmRepository extends JpaRepository<ReadingRhythm, Long> {

    List<ReadingRhythm> findByUserIdOrderByCreatedAtAsc(Long userId);

    Optional<ReadingRhythm> findByIdAndUserId(Long id, Long userId);
}
