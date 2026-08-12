package com.readthekjv.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;

/**
 * Remember-me services that set {@code SameSite=Lax} explicitly on the
 * remember-me cookie. Spring Security 6.2.1's default {@code setCookie}
 * only sets Secure + HttpOnly (H3).
 */
public class SameSiteRememberMeServices extends PersistentTokenBasedRememberMeServices {

    public SameSiteRememberMeServices(String key,
                                      UserDetailsService userDetailsService,
                                      PersistentTokenRepository tokenRepository) {
        super(key, userDetailsService, tokenRepository);
    }

    @Override
    protected void setCookie(String[] tokens, int maxAge, HttpServletRequest request,
                             HttpServletResponse response) {
        String cookieValue = encodeCookie(tokens);
        Cookie cookie = new Cookie(getCookieName(), cookieValue);
        cookie.setMaxAge(maxAge);
        cookie.setPath(cookiePath(request));
        cookie.setSecure(request.isSecure());
        cookie.setHttpOnly(true);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    @Override
    protected void cancelCookie(HttpServletRequest request, HttpServletResponse response) {
        Cookie cookie = new Cookie(getCookieName(), null);
        cookie.setMaxAge(0);
        cookie.setPath(cookiePath(request));
        cookie.setSecure(request.isSecure());
        cookie.setHttpOnly(true);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    private static String cookiePath(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        return (contextPath != null && !contextPath.isEmpty()) ? contextPath : "/";
    }
}
