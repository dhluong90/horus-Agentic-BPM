package io.github.dhluong90.bpm.host.autoconfigure;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * {@code Cache-Control: no-store} on every response under {@code /api/bpm/**}: the engine APIs
 * ({@code /api/bpm/rules/**}, {@code /api/bpm/processes/**} and any later engine prefix) and an unmapped
 * {@code /api/bpm/...} path alike (TR-SEC-09). {@code /api/bpm/meta} and {@code /api/bpm/me} are skipped; they
 * set their own private caching. The header is set before the request is handled, so it is on success, problem
 * and {@code 304} answers, and an endpoint that needs a different policy can still replace it. Host paths outside
 * {@code /api/bpm/**} are never matched.
 */
final class EngineApiCacheControlFilter extends OncePerRequestFilter {

    static final String[] URL_PATTERNS = {"/api/bpm/*"};
    static final Set<String> OWN_POLICY = Set.of("/api/bpm/meta", "/api/bpm/me");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return OWN_POLICY.contains(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }
}
