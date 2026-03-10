package com.readthekjv.repository;

import com.readthekjv.model.entity.Passage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PassageRepository extends JpaRepository<Passage, UUID> {

    // Per-user passage lookup (existing)
    Optional<Passage> findByUserIdAndNaturalKey(Long userId, String naturalKey);

    // Global passage lookup by natural key (user IS NULL)
    Optional<Passage> findByUserIsNullAndNaturalKey(String naturalKey);

    // All global passages ordered for dashboard display
    List<Passage> findByUserIsNullOrderBySortOrderAsc();
}
