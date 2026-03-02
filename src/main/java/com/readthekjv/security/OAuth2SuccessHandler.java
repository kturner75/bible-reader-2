package com.readthekjv.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * After a successful Google OAuth2 login, re-authenticates the session as a standard
 * UsernamePasswordAuthenticationToken so that all existing controllers that use
 * {@code @AuthenticationPrincipal UserDetails} continue to work unchanged.
 */
@Component
public class OAuth2SuccessHandler implements AuthenticationSuccessHandler {

    private final UserDetailsService userDetailsService;
    private final HttpSessionSecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    public OAuth2SuccessHandler(UserDetailsService userDetailsService) {
        this.userDetailsService = userDetailsService;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2User oAuth2User = (OAuth2User) authentication.getPrincipal();
        String rawEmail = oAuth2User.getAttribute("email");
        if (rawEmail == null) {
            response.sendRedirect("/login.html?error=google");
            return;
        }
        String email = rawEmail.trim().toLowerCase();

        // Load our UserDetails (user is guaranteed to exist — OAuth2UserServiceImpl created it)
        UserDetails userDetails = userDetailsService.loadUserByUsername(email);

        // Build a standard auth token and persist it to the HTTP session
        UsernamePasswordAuthenticationToken newAuth =
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());

        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(newAuth);
        SecurityContextHolder.setContext(ctx);
        securityContextRepository.saveContext(ctx, request, response);

        response.sendRedirect("/");
    }
}
