package com.readthekjv.controller;

import com.readthekjv.model.dto.*;
import com.readthekjv.model.entity.User;
import com.readthekjv.repository.UserRepository;
import com.readthekjv.service.LibraryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * All endpoints require an active session — "/api/library/**" is NOT in SecurityConfig's
 * permitAll list, so unauthenticated requests are rejected before reaching this controller.
 */
@RestController
@RequestMapping("/api/library")
public class LibraryController {

    private final LibraryService libraryService;
    private final UserRepository userRepository;

    public LibraryController(LibraryService libraryService, UserRepository userRepository) {
        this.libraryService = libraryService;
        this.userRepository = userRepository;
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private User resolveUser(UserDetails ud) {
        return userRepository.findByEmail(ud.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }

    // ─── Saved Verses ─────────────────────────────────────────────────────────

    @GetMapping("/verses")
    public List<SavedVerseResponse> getSavedVerses(@AuthenticationPrincipal UserDetails ud) {
        return libraryService.getSavedVerses(resolveUser(ud).getId());
    }

    @PostMapping("/verses")
    @ResponseStatus(HttpStatus.CREATED)
    public SavedVerseResponse saveVerse(@AuthenticationPrincipal UserDetails ud,
                                        @Valid @RequestBody SaveVerseRequest req) {
        return libraryService.saveVerse(resolveUser(ud).getId(), req.verseId());
    }

    @DeleteMapping("/verses/{verseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsaveVerse(@AuthenticationPrincipal UserDetails ud,
                            @PathVariable int verseId) {
        libraryService.unsaveVerse(resolveUser(ud).getId(), verseId);
    }

    @PatchMapping("/verses/{verseId}/note")
    public SavedVerseResponse updateNote(@AuthenticationPrincipal UserDetails ud,
                                         @PathVariable int verseId,
                                         @Valid @RequestBody UpdateNoteRequest req) {
        return libraryService.updateNote(resolveUser(ud).getId(), verseId, req.note());
    }

    // ─── Tags ─────────────────────────────────────────────────────────────────

    @GetMapping("/tags")
    public List<TagResponse> getTags(@AuthenticationPrincipal UserDetails ud) {
        return libraryService.getTags(resolveUser(ud).getId());
    }

    @PostMapping("/tags")
    @ResponseStatus(HttpStatus.CREATED)
    public TagResponse createTag(@AuthenticationPrincipal UserDetails ud,
                                  @Valid @RequestBody CreateTagRequest req) {
        return libraryService.createTag(resolveUser(ud).getId(), req.name(), req.colorIndex());
    }

    // ─── Tag ↔ Verse Links ────────────────────────────────────────────────────

    @PostMapping("/verses/{verseId}/tags/{tagId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addTagToVerse(@AuthenticationPrincipal UserDetails ud,
                               @PathVariable int verseId,
                               @PathVariable UUID tagId) {
        libraryService.addTagToVerse(resolveUser(ud).getId(), verseId, tagId);
    }

    @DeleteMapping("/verses/{verseId}/tags/{tagId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeTagFromVerse(@AuthenticationPrincipal UserDetails ud,
                                    @PathVariable int verseId,
                                    @PathVariable UUID tagId) {
        libraryService.removeTagFromVerse(resolveUser(ud).getId(), verseId, tagId);
    }
}
