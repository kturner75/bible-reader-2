package com.readthekjv.service;

import com.readthekjv.model.entity.User;
import com.readthekjv.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * H1: Google must not auto-link onto a verified password account; unverified
 * squats may be claimed when Google proves email ownership.
 */
class OAuth2UserServiceImplTest {

    private UserRepository userRepository;
    private OAuth2UserServiceImpl service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        service = new OAuth2UserServiceImpl(userRepository);
        when(userRepository.findByGoogleSub(any())).thenReturn(Optional.empty());
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void emailVerifiedClaimAcceptsBooleanAndString() {
        OAuth2User boolTrue = mock(OAuth2User.class);
        when(boolTrue.getAttribute("email_verified")).thenReturn(Boolean.TRUE);
        assertTrue(OAuth2UserServiceImpl.isEmailVerifiedClaim(boolTrue));

        OAuth2User strTrue = mock(OAuth2User.class);
        when(strTrue.getAttribute("email_verified")).thenReturn("true");
        assertTrue(OAuth2UserServiceImpl.isEmailVerifiedClaim(strTrue));

        OAuth2User falseClaim = mock(OAuth2User.class);
        when(falseClaim.getAttribute("email_verified")).thenReturn(Boolean.FALSE);
        assertFalse(OAuth2UserServiceImpl.isEmailVerifiedClaim(falseClaim));

        OAuth2User missing = mock(OAuth2User.class);
        when(missing.getAttribute("email_verified")).thenReturn(null);
        assertFalse(OAuth2UserServiceImpl.isEmailVerifiedClaim(missing));
    }

    @Test
    void createsNewGoogleUserWhenEmailUnknown() {
        service.reconcileGoogleUser("victim@example.com", "sub-1", "Victim");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertEquals("victim@example.com", saved.getEmail());
        assertEquals("sub-1", saved.getGoogleSub());
        assertNull(saved.getPasswordHash());
        assertTrue(saved.isEmailVerified());
    }

    @Test
    void claimsUnverifiedPasswordAccountAndStripsPassword() {
        User squat = new User();
        squat.setEmail("victim@example.com");
        squat.setPasswordHash("$2a$10$attackerhash");
        squat.setEmailVerified(false);
        when(userRepository.findByEmail("victim@example.com")).thenReturn(Optional.of(squat));

        service.reconcileGoogleUser("victim@example.com", "google-sub", "Victim Name");

        assertEquals("google-sub", squat.getGoogleSub());
        assertNull(squat.getPasswordHash());
        assertTrue(squat.isEmailVerified());
        assertEquals("Victim Name", squat.getDisplayName());
        verify(userRepository).save(squat);
    }

    @Test
    void refusesAutoLinkOnVerifiedPasswordAccount() {
        User legit = new User();
        legit.setEmail("user@example.com");
        legit.setPasswordHash("$2a$10$realhash");
        legit.setEmailVerified(true);
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(legit));

        OAuth2AuthenticationException ex = assertThrows(
                OAuth2AuthenticationException.class,
                () -> service.reconcileGoogleUser("user@example.com", "google-sub", "User"));

        assertEquals("account_exists", ex.getError().getErrorCode());
        assertNull(legit.getGoogleSub());
        verify(userRepository, never()).save(any());
    }

    @Test
    void returningGoogleUserIsNoOp() {
        User existing = new User();
        existing.setEmail("user@example.com");
        existing.setGoogleSub("sub-1");
        existing.setEmailVerified(true);
        when(userRepository.findByGoogleSub("sub-1")).thenReturn(Optional.of(existing));

        service.reconcileGoogleUser("user@example.com", "sub-1", "User");

        verify(userRepository, never()).save(any());
        verify(userRepository, never()).findByEmail(any());
    }
}
