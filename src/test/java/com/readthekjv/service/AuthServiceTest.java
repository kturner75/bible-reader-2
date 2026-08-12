package com.readthekjv.service;

import com.readthekjv.exception.ConflictException;
import com.readthekjv.model.dto.RegisterRequest;
import com.readthekjv.model.dto.UserResponse;
import com.readthekjv.model.entity.User;
import com.readthekjv.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** H1: password registration must start with email_verified=false. */
class AuthServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        authService = new AuthService(userRepository, passwordEncoder);
        when(passwordEncoder.encode(any())).thenReturn("$2a$10$encoded");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void registerCreatesUnverifiedPasswordAccount() {
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);

        UserResponse response = authService.register(
                new RegisterRequest("New@Example.com", "password1", "New User"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertEquals("new@example.com", saved.getEmail());
        assertEquals("$2a$10$encoded", saved.getPasswordHash());
        assertFalse(saved.isEmailVerified());
        assertEquals("new@example.com", response.email());
    }

    @Test
    void registerRejectsDuplicateEmail() {
        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        assertThrows(ConflictException.class, () ->
                authService.register(new RegisterRequest("taken@example.com", "password1", null)));
        verify(userRepository, never()).save(any());
    }
}
