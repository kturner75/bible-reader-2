package com.readthekjv.controller;

import com.readthekjv.model.dto.RegisterRequest;
import com.readthekjv.model.dto.UserResponse;
import com.readthekjv.repository.UserRepository;
import com.readthekjv.service.AuthService;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final UserRepository userRepository;

    public AuthController(AuthService authService, UserRepository userRepository) {
        this.authService = authService;
        this.userRepository = userRepository;
    }

    /**
     * Register a new account.
     * POST /api/auth/register
     * Body: { email, password, displayName? }
     * Response: 201 UserResponse | 409 if email taken | 400 if validation fails
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse register(@Valid @RequestBody RegisterRequest req) {
        return authService.register(req);
    }

    /**
     * Return the currently authenticated user's info.
     * POST /api/auth/login  — handled by Spring Security (not this controller).
     * POST /api/auth/logout — handled by Spring Security (not this controller).
     *
     * GET /api/auth/me
     * Response: 200 UserResponse | 401 if anonymous
     *
     * Note: /api/auth/** is permitAll so Spring Security passes anonymous requests
     * through; we return 401 manually when userDetails is null.
     */
    @GetMapping("/me")
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return userRepository.findByEmail(userDetails.getUsername())
            .map(UserResponse::from)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    /**
     * Marks the one-time localStorage→DB migration as complete for this account.
     * Called by the frontend after migrateLocalStorageToDb() finishes (whether or not
     * there was anything to migrate). Idempotent — safe to call multiple times.
     *
     * POST /api/auth/me/migration-complete
     * Response: 204 No Content
     */
    @PostMapping("/me/migration-complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void markMigrationComplete(@AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) return;
        userRepository.findByEmail(userDetails.getUsername()).ifPresent(user -> {
            if (!user.isLocalStorageMigrated()) {
                user.setLocalStorageMigrated(true);
                userRepository.save(user);
            }
        });
    }
}
