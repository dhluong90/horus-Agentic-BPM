package com.horus.bpm.cockpit.autoconfigure;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * CSRF contract of {@code host-session} mode (DESIGN section 13): the host keeps its CSRF token in a
 * cookie the cockpit can read ({@code CookieCsrfTokenRepository.withHttpOnlyFalse()}, cookie
 * {@code XSRF-TOKEN}, with a handler that accepts the raw value in the header), and the cockpit sends it in
 * {@code X-XSRF-TOKEN} on every mutating request.
 *
 * <p>Spring Security loads the token lazily, so the cookie would only be written once something reads
 * the token. This interceptor reads it when a cockpit file is served, so the cookie exists before the
 * cockpit's first mutating call. It does nothing when the host has no CSRF protection.
 */
final class CockpitCsrfTokenLoader implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token) {
            token.getToken();
        }
        return true;
    }
}
