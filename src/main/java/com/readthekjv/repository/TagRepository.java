package com.readthekjv.repository;

import com.readthekjv.model.entity.Tag;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TagRepository extends JpaRepository<Tag, UUID> {

    List<Tag> findByUserIdOrderByCreatedAtAsc(Long userId);

    long countByUserId(Long userId);

    boolean existsByUserIdAndName(Long userId, String name);
}
