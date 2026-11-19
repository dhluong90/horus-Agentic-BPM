package io.github.dhluong90.bpm.cockpit.autoconfigure;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Guards every cockpit request. It adds the security headers and serves nothing to a request without
 * an authenticated user: the host's own security normally sends such a request to its sign-in first;
 * this is the second line, for a host whose security does not cover the cockpit path.
 */
final class CockpitAccessInterceptor implements HandlerInterceptor {

    private static final String PROBLEM_TYPE =
            "https://github.com/dhluong90/horus-Agentic-BPM/wiki/Common-Error-Codes#unauthenticated";

    private final String path;

    CockpitAccessInterceptor(String path) {
        this.path = path;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        CockpitSecurityHeaders.apply(response);
        if (request.getUserPrincipal() == null) {
            writeUnauthenticated(response);
            return false;
        }
        response.setHeader("Cache-Control", cacheControl(request));
        return true;
    }

    /** Hashed assets never change; the page and the registry are always fetched fresh. */
    private String cacheControl(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String withinCockpit = uri.substring(Math.min(uri.length(), contextPath.length() + path.length()));
        return withinCockpit.startsWith("/assets/") ? "private, max-age=31536000, immutable" : "no-store";
    }

    private static void writeUnauthenticated(HttpServletResponse response) throws IOException {
        String correlationId = UUID.randomUUID().toString();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"type\":" + CockpitHostRegistry.quote(PROBLEM_TYPE)
                + ",\"title\":\"Unauthenticated\",\"status\":401,\"code\":\"UNAUTHENTICATED\""
                + ",\"correlationId\":" + CockpitHostRegistry.quote(correlationId) + "}");
    }
}
