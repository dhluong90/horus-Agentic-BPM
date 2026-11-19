package io.github.dhluong90.bpm.host.autoconfigure;

import io.github.dhluong90.bpm.host.spi.EngineAccessDeniedException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Answers an {@link EngineAccessDeniedException} that leaves any controller with the RFC 9457 problem the
 * metadata endpoints send (ADR-0010, TR-SEC-08): {@code no-store}, the host's correlation id and, on a
 * {@code 401}, the {@code WWW-Authenticate} challenge. Handles that one exception type only, at the highest
 * precedence, so a broad handler of a host or an engine (for example one for every {@code RuntimeException})
 * cannot turn a refusal into another status or body. Registered whether or not the metadata endpoints are on.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
final class EngineAccessProblems {

    @ExceptionHandler(EngineAccessDeniedException.class)
    ResponseEntity<String> refused(EngineAccessDeniedException exception, HttpServletRequest request) {
        return response(exception, request);
    }

    static ResponseEntity<String> response(EngineAccessDeniedException exception, HttpServletRequest request) {
        Problem problem = Problem.of(exception.reason());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(problem.status())
                .contentType(MediaType.parseMediaType(Problem.MEDIA_TYPE))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(CorrelationIds.HEADER, CorrelationIds.of(request));
        if (exception.challenge() != null) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, exception.challenge());
        }
        return response.body(problem.toJson(request));
    }
}
