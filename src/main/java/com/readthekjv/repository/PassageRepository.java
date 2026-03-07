package com.readthekjv.repository;

import com.readthekjv.model.entity.Passage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PassageRepository extends JpaRepository<Passage, UUID> {

    Optional<Passage> findByUserIdAndNaturalKey(Long userId, String naturalKey);
}
