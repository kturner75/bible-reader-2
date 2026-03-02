package com.readthekjv.model.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Used for documentation; actual login is processed by Spring Security's
 * UsernamePasswordAuthenticationFilter at POST /api/auth/login.
 */
public record LoginRequest(
    @NotBlank @Email String email,
    @NotBlank String password
) {}
