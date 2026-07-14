package com.readthekjv.repository;

import com.readthekjv.model.entity.PassageCollection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PassageCollectionRepository extends JpaRepository<PassageCollection, Long> {

    List<PassageCollection> findByUserIdOrderByUpdatedAtDesc(Long userId);

    Optional<PassageCollection> findByIdAndUserId(Long id, Long userId);
}
