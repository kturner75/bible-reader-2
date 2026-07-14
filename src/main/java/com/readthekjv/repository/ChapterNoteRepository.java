package com.readthekjv.repository;

import com.readthekjv.model.entity.ChapterNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChapterNoteRepository extends JpaRepository<ChapterNote, UUID> {

    List<ChapterNote> findByUserIdOrderByBookIdAscChapterAsc(Long userId);

    Optional<ChapterNote> findByUserIdAndBookIdAndChapter(Long userId, int bookId, int chapter);
}
