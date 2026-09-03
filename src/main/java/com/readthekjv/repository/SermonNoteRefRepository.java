package com.readthekjv.repository;

import com.readthekjv.model.entity.SermonNoteRef;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface SermonNoteRefRepository extends JpaRepository<SermonNoteRef, Long> {

    @Query("SELECT r FROM SermonNoteRef r WHERE r.note.id IN :noteIds ORDER BY r.bookId, r.chapter")
    List<SermonNoteRef> findForNotes(@Param("noteIds") Collection<UUID> noteIds);

    @Modifying
    @Query("DELETE FROM SermonNoteRef r WHERE r.note.id = :noteId")
    void deleteForNote(@Param("noteId") UUID noteId);

    /** Book ids the user has any note about — populates the scripture filter. */
    @Query("SELECT DISTINCT r.bookId FROM SermonNoteRef r WHERE r.note.user.id = :userId ORDER BY r.bookId")
    List<Integer> findBookIdsForUser(@Param("userId") Long userId);
}
