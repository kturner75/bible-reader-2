package com.readthekjv.service;

import com.readthekjv.model.entity.User;
import com.readthekjv.repository.UserRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads the Google OAuth2 user and ensures a matching app User record exists.
 *
 * <p>Cases (H1-hardened):
 * <ol>
 *   <li>Returning Google user — found by {@code google_sub}, no change</li>
 *   <li>Unverified password squat — same email, {@code email_verified=false},
 *       no {@code google_sub}: <em>claim</em> the row (strip password, attach
 *       Google). Google's {@code email_verified} is proof of ownership.</li>
 *   <li>Verified password account — same email, verified, no Google link:
 *       <strong>refuse</strong> auto-link (closes pre-hijack). User must sign
 *       in with password; explicit linking can be added later.</li>
 *   <li>Brand-new email — create Google-only user with {@code email_verified}</li>
 * </ol>
 */
@Service
public class OAuth2UserServiceImpl extends DefaultOAuth2UserService {

    private final UserRepository userRepository;

    public OAuth2UserServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oAuth2User = super.loadUser(userRequest);

        String rawEmail = oAuth2User.getAttribute("email");
        String sub      = oAuth2User.getAttribute("sub");
        String name     = oAuth2User.getAttribute("name");

        if (rawEmail == null || sub == null) {
            throw oauthError("google", "Google account did not provide email or sub");
        }

        if (!isEmailVerifiedClaim(oAuth2User)) {
            throw oauthError("email_unverified",
                    "Google account email is not verified");
        }

        reconcileGoogleUser(rawEmail.trim().toLowerCase(), sub, name);
        return oAuth2User;
    }

    /**
     * Core linking / claim / create logic. Package-visible for unit tests.
     */
    void reconcileGoogleUser(String email, String sub, String name) {
        // Case 1: returning Google user
        if (userRepository.findByGoogleSub(sub).isPresent()) {
            return;
        }

        userRepository.findByEmail(email).ifPresentOrElse(
            existing -> handleExistingEmail(existing, sub, name),
            () -> createGoogleUser(email, sub, name)
        );
    }

    private void handleExistingEmail(User existing, String sub, String name) {
        if (sub.equals(existing.getGoogleSub())) {
            return; // already linked to this Google account
        }
        if (existing.getGoogleSub() != null) {
            // Different Google account already linked — should be unreachable
            // via unique google_sub, but refuse rather than overwrite.
            throw oauthError("account_exists",
                    "This email is already linked to a different Google account");
        }

        // Unverified password registration → safe to claim with Google proof.
        if (!existing.isEmailVerified()) {
            existing.setGoogleSub(sub);
            existing.setPasswordHash(null); // revoke attacker password login
            existing.setEmailVerified(true);
            if (name != null && (existing.getDisplayName() == null || existing.getDisplayName().isBlank())) {
                existing.setDisplayName(name);
            }
            userRepository.save(existing);
            return;
        }

        // Verified password account: do NOT auto-link (H1).
        throw oauthError("account_exists",
                "An account with this email already exists. Sign in with your password.");
    }

    private void createGoogleUser(String email, String sub, String name) {
        User newUser = new User();
        newUser.setEmail(email);
        newUser.setGoogleSub(sub);
        newUser.setDisplayName(name);
        newUser.setEmailVerified(true);
        userRepository.save(newUser);
    }

    /**
     * Google may send {@code email_verified} as Boolean or String depending on
     * the userinfo payload shape.
     */
    static boolean isEmailVerifiedClaim(OAuth2User user) {
        Object claim = user.getAttribute("email_verified");
        if (claim instanceof Boolean b) {
            return b;
        }
        if (claim instanceof String s) {
            return "true".equalsIgnoreCase(s);
        }
        return false;
    }

    private static OAuth2AuthenticationException oauthError(String code, String description) {
        return new OAuth2AuthenticationException(new OAuth2Error(code, description, null));
    }
}
