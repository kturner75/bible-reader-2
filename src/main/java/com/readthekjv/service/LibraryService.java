package com.readthekjv.service;

import com.readthekjv.exception.BadRequestException;
import com.readthekjv.exception.ConflictException;
import com.readthekjv.model.dto.SavedVerseResponse;
import com.readthekjv.model.dto.TagResponse;
import com.readthekjv.model.entity.SavedVerse;
import com.readthekjv.model.entity.SavedVerseTag;
import com.readthekjv.model.entity.Tag;
import com.readthekjv.model.entity.User;
import com.readthekjv.repository.SavedVerseRepository;
import com.readthekjv.repository.TagRepository;
import com.readthekjv.repository.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class LibraryService {

    private final SavedVerseRepository savedVerseRepository;
    private final TagRepository tagRepository;
    private final UserRepository userRepository;

    public LibraryService(SavedVerseRepository savedVerseRepository,
                          TagRepository tagRepository,
                          UserRepository userRepository) {
        this.savedVerseRepository = savedVerseRepository;
        this.tagRepository = tagRepository;
        this.userRepository = userRepository;
    }

    // ─── Saved Verses ────────────────────────────────────────────────────────

    public List<SavedVerseResponse> getSavedVerses(Long userId) {
        return savedVerseRepository.findByUserIdWithTagsOrderBySavedAtDesc(userId)
                .stream()
                .map(SavedVerseResponse::from)
                .toList();
    }

    /**
     * Idempotent — returns the existing entry if this verse is already saved.
     */
    public SavedVerseResponse saveVerse(Long userId, int verseId) {
        return savedVerseRepository.findByUserIdAndVerseId(userId, verseId)
                .map(sv -> {
                    sv.getSavedVerseTags().size(); // init lazy collection
                    return SavedVerseResponse.from(sv);
                })
                .orElseGet(() -> {
                    User user = userRepository.getReferenceById(userId);
                    SavedVerse sv = new SavedVerse();
                    sv.setUser(user);
                    sv.setVerseId(verseId);
                    SavedVerse saved = savedVerseRepository.save(sv);
                    return SavedVerseResponse.from(saved);
                });
    }

    /**
     * Idempotent — no-op if the verse is not saved.
     * Cascade + orphanRemoval on SavedVerse removes its saved_verse_tags rows automatically.
     */
    public void unsaveVerse(Long userId, int verseId) {
        savedVerseRepository.findByUserIdAndVerseId(userId, verseId)
                .ifPresent(savedVerseRepository::delete);
    }

    /**
     * Updates (or clears) the note on a saved verse.
     *
     * @throws ResponseStatusException 404 if the verse is not in this user's library
     */
    public SavedVerseResponse updateNote(Long userId, int verseId, String note) {
        SavedVerse sv = savedVerseRepository.findByUserIdAndVerseId(userId, verseId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Verse not in library"));
        sv.setNote(note);
        savedVerseRepository.save(sv);
        sv.getSavedVerseTags().size(); // init lazy collection for response mapping
        return SavedVerseResponse.from(sv);
    }

    // ─── Tags ─────────────────────────────────────────────────────────────────

    public List<TagResponse> getTags(Long userId) {
        return tagRepository.findByUserIdOrderByCreatedAtAsc(userId)
                .stream()
                .map(TagResponse::from)
                .toList();
    }

    /**
     * @throws BadRequestException if the user already has 50 tags
     * @throws ConflictException   if a tag with the same name (case-sensitive) already exists
     */
    public TagResponse createTag(Long userId, String name, short colorIndex) {
        if (tagRepository.countByUserId(userId) >= 50) {
            throw new BadRequestException("Tag limit of 50 reached");
        }
        if (tagRepository.existsByUserIdAndName(userId, name)) {
            throw new ConflictException("A tag with that name already exists");
        }
        User user = userRepository.getReferenceById(userId);
        Tag tag = new Tag();
        tag.setUser(user);
        tag.setName(name);
        tag.setColorIndex(colorIndex);
        return TagResponse.from(tagRepository.save(tag));
    }

    /**
     * Deletes a tag and all its verse associations (cascade handled by DB).
     * Idempotent — no-op if the tag doesn't exist or doesn't belong to this user.
     */
    public void deleteTag(Long userId, UUID tagId) {
        tagRepository.findById(tagId)
                .filter(t -> t.getUser().getId().equals(userId))
                .ifPresent(tagRepository::delete);
    }

    // ─── Tag ↔ Verse Links ────────────────────────────────────────────────────

    /**
     * Links a tag to a saved verse.
     * Auto-saves the verse if it isn't already in the library (mirrors frontend behaviour).
     * Idempotent — no-op if the link already exists.
     *
     * @throws ResponseStatusException 404 if tagId doesn't belong to this user
     * @throws BadRequestException     if the verse already has 5 tags
     */
    public void addTagToVerse(Long userId, int verseId, UUID tagId) {
        // Verify tag belongs to this user
        Tag tag = tagRepository.findById(tagId)
                .filter(t -> t.getUser().getId().equals(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tag not found"));

        // Auto-save the verse if not yet saved
        SavedVerse sv = savedVerseRepository.findByUserIdAndVerseId(userId, verseId)
                .orElseGet(() -> {
                    User user = userRepository.getReferenceById(userId);
                    SavedVerse newSv = new SavedVerse();
                    newSv.setUser(user);
                    newSv.setVerseId(verseId);
                    return savedVerseRepository.save(newSv);
                });

        sv.getSavedVerseTags().size(); // init lazy collection

        // Idempotency: skip if link already exists
        boolean alreadyLinked = sv.getSavedVerseTags().stream()
                .anyMatch(svt -> svt.getTag().getId().equals(tagId));
        if (alreadyLinked) return;

        // Enforce 5-tag-per-verse limit
        if (sv.getSavedVerseTags().size() >= 5) {
            throw new BadRequestException("A verse can have at most 5 tags");
        }

        sv.getSavedVerseTags().add(new SavedVerseTag(sv, tag));
        savedVerseRepository.save(sv);
    }

    /**
     * Removes a tag link from a saved verse.
     * Idempotent — no-op if the verse isn't saved or the tag wasn't applied.
     */
    public void removeTagFromVerse(Long userId, int verseId, UUID tagId) {
        savedVerseRepository.findByUserIdAndVerseId(userId, verseId).ifPresent(sv -> {
            sv.getSavedVerseTags().size(); // init lazy collection
            sv.getSavedVerseTags().removeIf(svt -> svt.getTag().getId().equals(tagId));
            savedVerseRepository.save(sv);
        });
    }
}
