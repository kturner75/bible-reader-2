package com.readthekjv.config;

import com.readthekjv.security.CsrfCookieFilter;
import com.readthekjv.security.OAuth2SuccessHandler;
import com.readthekjv.security.SpaCsrfTokenRequestHandler;
import com.readthekjv.service.OAuth2UserServiceImpl;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import java.util.Set;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Set<String> OAUTH_ERROR_CODES = Set.of(
            "account_exists", "email_unverified", "google");

    private final OAuth2UserServiceImpl oAuth2UserService;
    private final OAuth2SuccessHandler oAuth2SuccessHandler;

    public SecurityConfig(OAuth2UserServiceImpl oAuth2UserService,
                          OAuth2SuccessHandler oAuth2SuccessHandler) {
        this.oAuth2UserService = oAuth2UserService;
        this.oAuth2SuccessHandler = oAuth2SuccessHandler;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                            PersistentTokenBasedRememberMeServices rememberMeServices) throws Exception {
        CookieCsrfTokenRepository csrfTokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfTokenRepository.setCookieCustomizer(cookie -> cookie.sameSite("Lax"));

        http
            // H3: CSRF for cookie-authenticated APIs (double-submit cookie + X-XSRF-TOKEN).
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfTokenRepository)
                .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
                // OAuth2 redirect dance is browser-navigated; leave those matchers alone.
                // All /api/** state changes require the header from csrf-utils.js.
            )
            .addFilterAfter(new CsrfCookieFilter(), UsernamePasswordAuthenticationFilter.class)

            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/", "/read", "/read/collection/**", "/read/passage/**", "/read/range", "/index.html",
                    "/landing.html", "/landing.css",       // public landing page
                    "/login.html", "/register.html",
                    "/auth.css",
                    "/train", "/train.html", "/train.js", "/train.css",
                    "/dashboard", "/dashboard.html", "/dashboard.css", "/dashboard.js",
                    "/notes", "/notes.html", "/notes.css", "/notes.js",
                    "/style.css", "/app.js",
                    "/date-utils.js",                      // shared by the public reader — a 302 here breaks it
                    "/view-prefs.js",                      // ditto — window.KjvViewPrefs must exist for signed-out readers
                    "/note-links.js",                      // portable [v=]/[e=] helpers — public reader + notes dock
                    "/note-embed.css",                     // shared [e=…] quote block — reader dock + /notes
                    "/csrf-utils.js",                      // ditto — CSRF helper for public login/register/reader
                    "/error",                              // Spring Boot error controller — must be public
                    "/api/verses", "/api/books/**", "/api/search",
                    "/api/reference", "/api/navigate/**",
                    "/api/ranges", "/api/ranges/**",       // portable [v=…] hydrate + surrounding context — public
                    // Audio: permitAll so cache-hit CDN URLs stay public; on-demand
                    // OpenAI generation is gated inside TtsController (H2).
                    "/api/audio/**", "/api/tts/status",
                    "/api/auth/**",
                    "/api/verse-of-day"                    // public — same verse shown to all visitors
                ).permitAll()
                .anyRequest().authenticated()
            )

            // Spring Security handles POST /api/auth/login
            .formLogin(form -> form
                .loginPage("/login.html")
                .loginProcessingUrl("/api/auth/login")
                .usernameParameter("username")
                .passwordParameter("password")
                .successHandler((req, res, auth) -> {
                    res.setStatus(HttpServletResponse.SC_OK);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"ok\":true}");
                })
                .failureHandler((req, res, ex) -> {
                    res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"error\":\"Invalid email or password\"}");
                })
                .permitAll()
            )

            // Spring Security handles POST /api/auth/logout
            .logout(logout -> logout
                .logoutUrl("/api/auth/logout")
                // Explicit matcher so logout accepts JSON SPA POSTs with CSRF header
                .logoutRequestMatcher(new AntPathRequestMatcher("/api/auth/logout", "POST"))
                .logoutSuccessHandler((req, res, auth) -> {
                    res.setStatus(HttpServletResponse.SC_OK);
                    res.setContentType("application/json");
                    res.getWriter().write("{\"ok\":true}");
                })
            )

            // Google OAuth2 — Spring Security auto-permits /oauth2/authorization/** and /login/oauth2/code/**
            .oauth2Login(oauth2 -> oauth2
                .loginPage("/login.html")
                .userInfoEndpoint(info -> info.userService(oAuth2UserService))
                .successHandler(oAuth2SuccessHandler)
                .failureHandler((req, res, ex) -> {
                    String code = "google";
                    if (ex instanceof OAuth2AuthenticationException oauthEx
                            && oauthEx.getError() != null
                            && OAUTH_ERROR_CODES.contains(oauthEx.getError().getErrorCode())) {
                        code = oauthEx.getError().getErrorCode();
                    }
                    res.setStatus(HttpServletResponse.SC_FOUND);
                    res.setHeader("Location", "/login.html?error=" + code);
                })
            )

            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
            )

            // Keeps users signed in for 30 days across browser restarts (see persistent_logins table).
            // Form login auto-wires this filter correctly; OAuth2SuccessHandler invokes it explicitly
            // (see comment there) since the OAuth2 principal isn't a UserDetails.
            .rememberMe(rm -> rm.rememberMeServices(rememberMeServices))

            // For API calls, return 401 JSON instead of redirecting to login page.
            // Use relative Location header (not sendRedirect) so the browser resolves
            // the correct protocol — sendRedirect() builds an absolute http:// URL
            // because Nginx terminates SSL before reaching Spring Boot.
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, exception) -> {
                    if (req.getRequestURI().startsWith("/api/")) {
                        res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        res.setContentType("application/json");
                        res.getWriter().write("{\"error\":\"Unauthorized\"}");
                    } else {
                        res.setStatus(HttpServletResponse.SC_FOUND);
                        res.setHeader("Location", "/login.html");
                    }
                })
            );

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
