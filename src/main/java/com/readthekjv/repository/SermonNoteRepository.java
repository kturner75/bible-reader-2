package com.readthekjv.repository;

import com.readthekjv.model.entity.SermonNote;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SermonNoteRepository extends JpaRepository<SermonNote, UUID> {

    Optional<SermonNote> findByIdAndUserId(UUID id, Long userId);

    /**
     * Notes with no derived scripture rows yet, in id order after {@code afterId}.
     *
     * The cursor is what makes the backfill terminate: a note that genuinely cites nothing
     * still has no rows after being indexed, so a "fetch the unindexed until empty" loop
     * would never end. Walking ids forward passes over each note exactly once.
     */
    @Query("""
           SELECT n FROM SermonNote n
           WHERE n.id > :afterId
             AND NOT EXISTS (SELECT 1 FROM SermonNoteRef r WHERE r.note = n)
           ORDER BY n.id
           """)
    List<SermonNote> findUnindexedAfter(@Param("afterId") UUID afterId, Pageable page);

    /**
     * Finder query. Every filter is independently optional, expressed as a value that
     * matches everything rather than as NULL: {@code like} is {@code "%"}, {@code bookId}
     * is {@code -1}, {@code since} is the epoch. The caller escapes LIKE metacharacters in a
     * real query, so the sentinel {@code "%"} is the only wildcard that survives.
     *
     * That is not stylistic. Postgres cannot infer the type of a bare parameter in
     * {@code ? IS NULL}, so the natural "(:since IS NULL OR ...)" shape fails at runtime
     * with "could not determine data type of parameter". Comparing against a typed column
     * or an integer literal lets it infer every type, with no casts to keep in sync.
     *
     * {@code qBookIds} carries the book ids whose names match the same query text — that is
     * how typing "psalm" finds a note whose only mention of the Psalms is an encoded
     * [v=…] token. Callers pass a non-empty sentinel list, since JPQL has no IN ().
     *
     * Deliberately does not match the KJV text behind a reference: a note citing John 3:16
     * does not match "begotten". That is a different feature, and folding it in here would
     * make one parameter mean two things.
     */
    @Query("""
           SELECT n FROM SermonNote n
           WHERE n.user.id = :userId
             AND n.updatedAt >= :since
             AND (:bookId = -1 OR EXISTS (
                     SELECT 1 FROM SermonNoteRef r
                     WHERE r.note = n AND r.bookId = :bookId))
             AND (LOWER(n.title) LIKE :like ESCAPE '\\'
                  OR LOWER(n.note) LIKE :like ESCAPE '\\'
                  OR EXISTS (
                     SELECT 1 FROM SermonNoteRef qr
                     WHERE qr.note = n AND qr.bookId IN :qBookIds))
           """)
    List<SermonNote> search(@Param("userId") Long userId,
                            @Param("like") String like,
                            @Param("qBookIds") Collection<Integer> qBookIds,
                            @Param("bookId") int bookId,
                            @Param("since") OffsetDateTime since,
                            Sort sort);
}
