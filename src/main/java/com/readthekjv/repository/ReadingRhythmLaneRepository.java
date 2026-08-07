package com.readthekjv.repository;

import com.readthekjv.model.entity.ReadingRhythmLane;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface ReadingRhythmLaneRepository extends JpaRepository<ReadingRhythmLane, Long> {

    /**
     * Owner-scoped lane lookup — lane ids arrive straight from the client on the
     * progress/restart endpoints, so ownership must be proven in the query.
     */
    @Query("select l from ReadingRhythmLane l where l.id = :laneId and l.rhythm.user.id = :userId")
    Optional<ReadingRhythmLane> findByIdAndUserId(Long laneId, Long userId);
}
