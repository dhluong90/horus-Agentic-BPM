package io.github.dhluong90.bpm.host.autoconfigure;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Answers an unauthenticated request with {@code 401} and an RFC 9457 body carrying
 * {@code code: UNAUTHENTICATED} and a {@code correlationId}, as every engine endpoint must (TR-SEC-08).
 *
 * <p>The host owns its security filter chain, so it wires this entry point for {@code /api/bpm/**}:
 * <pre>{@code
 * http.exceptionHandling(e -> e
 *     .defaultAuthenticationEntryPointFor(new BpmProblemAuthenticationEntryPoint(),
 *         PathPatternRequestMatcher.withDefaults().matcher("/api/bpm/**"))
 *     .defaultAccessDeniedHandlerFor(new BpmProblemAccessDeniedHandler(),
 *         PathPatternRequestMatcher.withDefaults().matcher("/api/bpm/**")));
 * }</pre>
 * Stateless and safe for concurrent use.
 */
public final class BpmProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final String challenge;

    /** Sends the {@code WWW-Authenticate: Bearer} challenge, for hosts that accept bearer tokens. */
    public BpmProblemAuthenticationEntryPoint() {
        this("Bearer");
    }

    /**
     * Sends the given challenge.
     *
     * @param challenge the {@code WWW-Authenticate} value, for example {@code Basic realm="loans"}
     */
    public BpmProblemAuthenticationEntryPoint(String challenge) {
        this.challenge = Objects.requireNonNull(challenge, "challenge");
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        response.setHeader("WWW-Authenticate", challenge);
        Problem.unauthenticated().write(request, response);
    }
}
