package com.readthekjv.repository;

import com.readthekjv.model.entity.SavedVerse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SavedVerseRepository extends JpaRepository<SavedVerse, Long> {

    // Sort: newest first (default)
    List<SavedVerse> findByUserIdOrderBySavedAtDesc(Long userId);

    // Sort: oldest first
    List<SavedVerse> findByUserIdOrderBySavedAtAsc(Long userId);

    // Sort: Bible order
    List<SavedVerse> findByUserIdOrderByVerseIdAsc(Long userId);

    Optional<SavedVerse> findByUserIdAndVerseId(Long userId, int verseId);

    boolean existsByUserIdAndVerseId(Long userId, int verseId);

    // Fetch with tags to avoid N+1 when rendering the library
    @Query("SELECT sv FROM SavedVerse sv LEFT JOIN FETCH sv.savedVerseTags svt LEFT JOIN FETCH svt.tag WHERE sv.user.id = :userId ORDER BY sv.savedAt DESC")
    List<SavedVerse> findByUserIdWithTagsOrderBySavedAtDesc(@Param("userId") Long userId);
}
