package com.horus.bpm.host.autoconfigure;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code Cache-Control: no-store} on every engine API response, {@code /api/bpm/rules/**} and
 * {@code /api/bpm/processes/**} (TR-SEC-09). {@code /api/bpm/meta} and {@code /api/bpm/me} are not
 * matched; they set their own private caching. The header is set before the request is handled, so an
 * endpoint that needs a different policy can still replace it.
 */
final class EngineApiCacheControlFilter extends OncePerRequestFilter {

    static final String[] URL_PATTERNS = {"/api/bpm/rules/*", "/api/bpm/processes/*"};

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }
}
