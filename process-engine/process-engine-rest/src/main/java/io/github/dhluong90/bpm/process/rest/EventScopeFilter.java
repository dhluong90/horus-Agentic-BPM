package io.github.dhluong90.bpm.process.rest;

import io.github.dhluong90.bpm.process.spi.EventScope;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Opens an {@link EventScope} around each request to the Process Engine API, so the events the request causes carry
 * its {@code X-Correlation-Id} as {@code correlationId} and its {@code Idempotency-Key} as {@code causationId}
 * (DESIGN section 6.4). A header that is missing or not 1 to 64 characters of {@code A-Z a-z 0-9 . _ : -} is left out
 * rather than refused: the request itself is judged by the controller, and an event never carries unchecked text.
 */
public final class EventScopeFilter extends OncePerRequestFilter {

    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().contains("/api/bpm/processes/v1/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try (EventScope ignored = EventScope.open(valid(request.getHeader("X-Correlation-Id")),
                valid(request.getHeader("Idempotency-Key")))) {
            chain.doFilter(request, response);
        }
    }

    private static String valid(String value) {
        return value != null && ID.matcher(value).matches() ? value : null;
    }
}
