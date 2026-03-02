package com.readthekjv.service;

import com.readthekjv.model.entity.User;
import com.readthekjv.repository.UserRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads the Google OAuth2 user and ensures a matching app User record exists.
 * Three cases:
 *  1. Returning Google user  — found by google_sub, no change needed
 *  2. Existing password user — found by email, link by setting google_sub
 *  3. New user              — create with google_sub and no password_hash
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
            throw new OAuth2AuthenticationException("Google account did not provide email or sub");
        }

        String email = rawEmail.trim().toLowerCase();

        // Case 1: returning Google user
        if (userRepository.findByGoogleSub(sub).isPresent()) {
            return oAuth2User;
        }

        // Case 2: existing password account — link it
        userRepository.findByEmail(email).ifPresentOrElse(
            existingUser -> {
                existingUser.setGoogleSub(sub);
                userRepository.save(existingUser);
            },
            // Case 3: brand-new user
            () -> {
                User newUser = new User();
                newUser.setEmail(email);
                newUser.setGoogleSub(sub);
                newUser.setDisplayName(name);
                userRepository.save(newUser);
            }
        );

        return oAuth2User;
    }
}
