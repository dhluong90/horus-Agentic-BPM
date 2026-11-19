package io.github.dhluong90.bpm.cockpit.autoconfigure;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Keeps {@code X-Frame-Options: SAMEORIGIN} on cockpit responses, matching {@code frame-ancestors 'self'}
 * in the cockpit's Content-Security-Policy. Spring Security writes {@code X-Frame-Options: DENY} when the
 * response is committed, after any handler, so the header is held here, in a filter that runs before the
 * host's security filters. Only paths under the cockpit path are touched.
 */
final class CockpitFrameOptionsFilter extends OncePerRequestFilter implements Ordered {

    static final String HEADER = "X-Frame-Options";
    static final String VALUE = "SAMEORIGIN";

    /** Before Spring Security's filter chain, which Spring Boot registers at order -100. */
    static final int ORDER = -200;

    private final String path;

    CockpitFrameOptionsFilter(String path) {
        this.path = path;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String withinContext = request.getRequestURI().substring(request.getContextPath().length());
        return !(withinContext.equals(path) || withinContext.startsWith(path + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader(HEADER, VALUE);
        chain.doFilter(request, new HttpServletResponseWrapper(response) {
            @Override
            public void setHeader(String name, String value) {
                super.setHeader(name, HEADER.equalsIgnoreCase(name) ? VALUE : value);
            }

            @Override
            public void addHeader(String name, String value) {
                if (HEADER.equalsIgnoreCase(name)) {
                    super.setHeader(name, VALUE);
                } else {
                    super.addHeader(name, value);
                }
            }
        });
    }
}
