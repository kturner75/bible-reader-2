package com.readthekjv.controller;

import com.readthekjv.model.dto.SermonNoteResponse;
import com.readthekjv.model.dto.SermonNoteSummary;
import com.readthekjv.model.dto.UpsertSermonNoteRequest;
import com.readthekjv.model.entity.User;
import com.readthekjv.repository.UserRepository;
import com.readthekjv.service.SermonNoteService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * All endpoints require an active session — "/api/sermon-notes/**" is NOT in SecurityConfig's
 * permitAll list, so unauthenticated requests are rejected before reaching this controller.
 */
@RestController
@RequestMapping("/api/sermon-notes")
public class SermonNoteController {

    private final SermonNoteService sermonNoteService;
    private final UserRepository userRepository;

    public SermonNoteController(SermonNoteService sermonNoteService, UserRepository userRepository) {
        this.sermonNoteService = sermonNoteService;
        this.userRepository = userRepository;
    }

    private User resolveUser(UserDetails ud) {
        return userRepository.findByEmail(ud.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }

    @GetMapping
    public List<SermonNoteSummary> list(@AuthenticationPrincipal UserDetails ud) {
        return sermonNoteService.list(resolveUser(ud).getId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SermonNoteResponse create(@AuthenticationPrincipal UserDetails ud,
                                     @Valid @RequestBody UpsertSermonNoteRequest req) {
        return sermonNoteService.create(resolveUser(ud).getId(), req.title(), req.note());
    }

    @GetMapping("/{id}")
    public SermonNoteResponse get(@AuthenticationPrincipal UserDetails ud, @PathVariable UUID id) {
        return sermonNoteService.get(resolveUser(ud).getId(), id);
    }

    @PutMapping("/{id}")
    public SermonNoteResponse update(@AuthenticationPrincipal UserDetails ud,
                                     @PathVariable UUID id,
                                     @Valid @RequestBody UpsertSermonNoteRequest req) {
        return sermonNoteService.update(resolveUser(ud).getId(), id, req.title(), req.note());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal UserDetails ud, @PathVariable UUID id) {
        sermonNoteService.delete(resolveUser(ud).getId(), id);
    }
}
