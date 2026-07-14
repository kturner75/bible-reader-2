package com.readthekjv.controller;

import com.readthekjv.model.dto.BookNoteResponse;
import com.readthekjv.model.dto.UpsertBookNoteRequest;
import com.readthekjv.model.entity.User;
import com.readthekjv.repository.UserRepository;
import com.readthekjv.service.BookNoteService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * All endpoints require an active session — "/api/book-notes/**" is NOT in SecurityConfig's
 * permitAll list, so unauthenticated requests are rejected before reaching this controller.
 */
@RestController
@RequestMapping("/api/book-notes")
public class BookNoteController {

    private final BookNoteService bookNoteService;
    private final UserRepository userRepository;

    public BookNoteController(BookNoteService bookNoteService, UserRepository userRepository) {
        this.bookNoteService = bookNoteService;
        this.userRepository = userRepository;
    }

    private User resolveUser(UserDetails ud) {
        return userRepository.findByEmail(ud.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }

    @GetMapping
    public List<BookNoteResponse> getNotes(@AuthenticationPrincipal UserDetails ud) {
        return bookNoteService.getNotes(resolveUser(ud).getId());
    }

    @PutMapping("/{bookId}")
    public BookNoteResponse upsertNote(@AuthenticationPrincipal UserDetails ud,
                                       @PathVariable int bookId,
                                       @Valid @RequestBody UpsertBookNoteRequest req) {
        return bookNoteService.upsertNote(resolveUser(ud).getId(), bookId, req.note());
    }

    @DeleteMapping("/{bookId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteNote(@AuthenticationPrincipal UserDetails ud,
                           @PathVariable int bookId) {
        bookNoteService.deleteNote(resolveUser(ud).getId(), bookId);
    }
}
