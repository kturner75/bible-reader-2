package com.readthekjv.repository;

import com.readthekjv.model.entity.MemorizationEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MemorizationEntryRepository extends JpaRepository<MemorizationEntry, UUID> {

    @Query("SELECT me FROM MemorizationEntry me JOIN FETCH me.passage WHERE me.user.id = :userId ORDER BY me.addedAt DESC")
    List<MemorizationEntry> findByUserIdWithPassage(@Param("userId") Long userId);

    Optional<MemorizationEntry> findByUserIdAndPassageId(Long userId, UUID passageId);
}
