package com.readthekjv.controller;

import com.readthekjv.model.dto.AddToQueueRequest;
import com.readthekjv.model.dto.MemorizationEntryResponse;
import com.readthekjv.model.entity.User;
import com.readthekjv.repository.UserRepository;
import com.readthekjv.service.MemorizationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * All endpoints require an active session — "/api/memorization/**" is NOT in SecurityConfig's
 * permitAll list, so unauthenticated requests are rejected before reaching this controller.
 */
@RestController
@RequestMapping("/api/memorization")
public class MemorizationController {

    private final MemorizationService memorizationService;
    private final UserRepository userRepository;

    public MemorizationController(MemorizationService memorizationService,
                                  UserRepository userRepository) {
        this.memorizationService = memorizationService;
        this.userRepository = userRepository;
    }

    // ─── Helper ───────────────────────────────────────────────────────────────

    private User resolveUser(UserDetails ud) {
        return userRepository.findByEmail(ud.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }

    // ─── Queue ────────────────────────────────────────────────────────────────

    @GetMapping("/queue")
    public List<MemorizationEntryResponse> getQueue(@AuthenticationPrincipal UserDetails ud) {
        return memorizationService.getQueue(resolveUser(ud).getId());
    }

    @PostMapping("/queue")
    @ResponseStatus(HttpStatus.CREATED)
    public MemorizationEntryResponse addToQueue(@AuthenticationPrincipal UserDetails ud,
                                                @Valid @RequestBody AddToQueueRequest req) {
        return memorizationService.addToQueue(resolveUser(ud).getId(),
                req.fromVerseId(), req.toVerseId());
    }

    @DeleteMapping("/queue/{entryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFromQueue(@AuthenticationPrincipal UserDetails ud,
                                @PathVariable UUID entryId) {
        memorizationService.removeFromQueue(resolveUser(ud).getId(), entryId);
    }
}
