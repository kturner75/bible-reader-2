package com.readthekjv.repository;

import com.readthekjv.model.entity.SermonNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SermonNoteRepository extends JpaRepository<SermonNote, UUID> {

    List<SermonNote> findByUserIdOrderByUpdatedAtDesc(Long userId);

    Optional<SermonNote> findByIdAndUserId(UUID id, Long userId);
}
