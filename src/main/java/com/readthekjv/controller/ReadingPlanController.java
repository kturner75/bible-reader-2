package com.readthekjv.controller;

import com.readthekjv.model.dto.ReadingPlanResponse;
import com.readthekjv.model.entity.User;
import com.readthekjv.repository.UserRepository;
import com.readthekjv.service.ReadingPlanService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * All endpoints require an active session — "/api/plans/**" is NOT in SecurityConfig's
 * permitAll list, so unauthenticated requests are rejected before reaching this controller.
 */
@RestController
@RequestMapping("/api/plans")
public class ReadingPlanController {

    private final ReadingPlanService planService;
    private final UserRepository     userRepository;

    public ReadingPlanController(ReadingPlanService planService,
                                 UserRepository userRepository) {
        this.planService    = planService;
        this.userRepository = userRepository;
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private User resolveUser(UserDetails ud) {
        return userRepository.findByEmail(ud.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }

    // ── Endpoints ─────────────────────────────────────────────────────────────

    /** List all plans with the authenticated user's enrollment state. */
    @GetMapping
    public List<ReadingPlanResponse> getPlans(@AuthenticationPrincipal UserDetails ud) {
        return planService.getPlans(resolveUser(ud).getId());
    }

    /** Enroll in a plan (idempotent — safe to call if already enrolled). */
    @PostMapping("/{planId}/enroll")
    public ReadingPlanResponse enroll(@AuthenticationPrincipal UserDetails ud,
                                      @PathVariable UUID planId) {
        return planService.enroll(resolveUser(ud).getId(), planId);
    }

    /** Unenroll from a plan (idempotent — no-op if not enrolled). */
    @DeleteMapping("/{planId}/enroll")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unenroll(@AuthenticationPrincipal UserDetails ud,
                         @PathVariable UUID planId) {
        planService.unenroll(resolveUser(ud).getId(), planId);
    }

    /** Mark the current day complete and advance to the next day. */
    @PostMapping("/{planId}/complete-day")
    public ReadingPlanResponse completeDay(@AuthenticationPrincipal UserDetails ud,
                                           @PathVariable UUID planId) {
        return planService.completeDay(resolveUser(ud).getId(), planId);
    }
}
