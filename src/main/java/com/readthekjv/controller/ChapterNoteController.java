package com.readthekjv.controller;

import com.readthekjv.model.dto.ChapterNoteResponse;
import com.readthekjv.model.dto.UpsertChapterNoteRequest;
import com.readthekjv.model.entity.User;
import com.readthekjv.repository.UserRepository;
import com.readthekjv.service.ChapterNoteService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * All endpoints require an active session — "/api/chapter-notes/**" is NOT in SecurityConfig's
 * permitAll list, so unauthenticated requests are rejected before reaching this controller.
 */
@RestController
@RequestMapping("/api/chapter-notes")
public class ChapterNoteController {

    private final ChapterNoteService chapterNoteService;
    private final UserRepository userRepository;

    public ChapterNoteController(ChapterNoteService chapterNoteService, UserRepository userRepository) {
        this.chapterNoteService = chapterNoteService;
        this.userRepository = userRepository;
    }

    private User resolveUser(UserDetails ud) {
        return userRepository.findByEmail(ud.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }

    @GetMapping
    public List<ChapterNoteResponse> getNotes(@AuthenticationPrincipal UserDetails ud) {
        return chapterNoteService.getNotes(resolveUser(ud).getId());
    }

    @PutMapping("/{bookId}/{chapter}")
    public ChapterNoteResponse upsertNote(@AuthenticationPrincipal UserDetails ud,
                                          @PathVariable int bookId,
                                          @PathVariable int chapter,
                                          @Valid @RequestBody UpsertChapterNoteRequest req) {
        return chapterNoteService.upsertNote(resolveUser(ud).getId(), bookId, chapter, req.note());
    }

    @DeleteMapping("/{bookId}/{chapter}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteNote(@AuthenticationPrincipal UserDetails ud,
                           @PathVariable int bookId,
                           @PathVariable int chapter) {
        chapterNoteService.deleteNote(resolveUser(ud).getId(), bookId, chapter);
    }
}
