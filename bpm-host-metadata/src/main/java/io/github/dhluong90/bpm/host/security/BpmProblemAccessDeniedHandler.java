package io.github.dhluong90.bpm.host.security;

import io.github.dhluong90.bpm.host.internal.Problem;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Answers a request the host's security chain refuses with {@code 403} and an RFC 9457 body carrying
 * {@code code: PERMISSION_DENIED} and a {@code correlationId} (TR-SEC-08). Wired by the host next to
 * {@link BpmProblemAuthenticationEntryPoint}. Stateless and safe for concurrent use.
 */
public final class BpmProblemAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        Problem.permissionDenied().write(request, response);
    }
}
