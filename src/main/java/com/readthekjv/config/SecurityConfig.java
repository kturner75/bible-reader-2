package com.readthekjv.config;

import com.readthekjv.security.OAuth2SuccessHandler;
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
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final OAuth2UserServiceImpl oAuth2UserService;
    private final OAuth2SuccessHandler oAuth2SuccessHandler;

    public SecurityConfig(OAuth2UserServiceImpl oAuth2UserService,
                          OAuth2SuccessHandler oAuth2SuccessHandler) {
        this.oAuth2UserService = oAuth2UserService;
        this.oAuth2SuccessHandler = oAuth2SuccessHandler;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF for all API endpoints — REST calls use same-site session cookies
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))

            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/", "/read", "/index.html",
                    "/landing.html", "/landing.css",       // public landing page
                    "/login.html", "/register.html",
                    "/auth.css",
                    "/style.css", "/app.js",
                    "/error",                              // Spring Boot error controller — must be public
                    "/api/verses", "/api/books/**", "/api/search",
                    "/api/reference", "/api/navigate/**",
                    "/api/audio/**", "/api/tts/status",   // audio + TTS feature detection
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
                    res.setStatus(HttpServletResponse.SC_FOUND);
                    res.setHeader("Location", "/login.html?error=google");
                })
            )

            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
            )

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
