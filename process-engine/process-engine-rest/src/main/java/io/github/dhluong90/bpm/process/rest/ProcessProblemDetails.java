package io.github.dhluong90.bpm.process.rest;

import io.github.dhluong90.bpm.process.api.BpmnUnsupportedElementException;
import io.github.dhluong90.bpm.process.api.ProcessException;
import io.github.dhluong90.bpm.process.api.StepFailedException;
import io.github.dhluong90.bpm.process.api.ValidationFailedException;
import io.github.dhluong90.bpm.process.internal.json.EngineJson;
import io.github.dhluong90.bpm.process.internal.json.Ulid;
import io.github.dhluong90.bpm.process.internal.service.Views;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import tools.jackson.databind.node.ObjectNode;

/** RFC 9457 problem details for Process Engine endpoints, with the engine error code in {@code code}. */
@RestControllerAdvice(assignableTypes = ProcessController.class)
public class ProcessProblemDetails {

    private static final MediaType PROBLEM = MediaType.parseMediaType("application/problem+json");
    private static final String TYPE_BASE = "https://github.com/dhluong90/horus-Agentic-BPM/wiki/Common-Error-Codes#";
    private static final Pattern CORRELATION = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");
    private static final HttpStatus UNPROCESSABLE = HttpStatus.valueOf(422);
    private static final Map<String, HttpStatus> STATUS = Map.ofEntries(
            Map.entry("INPUT_INVALID", HttpStatus.BAD_REQUEST),
            Map.entry("UNAUTHENTICATED", HttpStatus.UNAUTHORIZED),
            Map.entry("PERMISSION_DENIED", HttpStatus.FORBIDDEN),
            Map.entry("TASK_NOT_ASSIGNED", HttpStatus.FORBIDDEN),
            Map.entry("NO_EFFECTIVE_VERSION", HttpStatus.NOT_FOUND),
            Map.entry("INSTANCE_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("TASK_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("INVALID_STATE", HttpStatus.CONFLICT),
            Map.entry("VERSION_CONFLICT", HttpStatus.CONFLICT),
            Map.entry("VALIDATION_FAILED", UNPROCESSABLE),
            Map.entry("BPMN_UNSUPPORTED_ELEMENT", UNPROCESSABLE),
            Map.entry("BPMN_UNSAFE_XML", UNPROCESSABLE),
            Map.entry("DEFINITION_TOO_LARGE", UNPROCESSABLE),
            Map.entry("IDEMPOTENCY_KEY_REUSE_MISMATCH", UNPROCESSABLE),
            Map.entry("STEP_FAILED", UNPROCESSABLE));

    /**
     * @param e the engine exception
     * @param request the request
     * @return the problem response
     */
    @ExceptionHandler(ProcessException.class)
    public ResponseEntity<byte[]> handle(ProcessException e, WebRequest request) {
        HttpStatus status = STATUS.getOrDefault(e.code(), HttpStatus.INTERNAL_SERVER_ERROR);
        String header = request.getHeader("X-Correlation-Id");
        ObjectNode body = EngineJson.NODES.objectNode()
                .put("type", TYPE_BASE + e.code().toLowerCase(Locale.ROOT))
                .put("title", status.getReasonPhrase())
                .put("status", status.value())
                .put("code", e.code())
                .put("detail", e.getMessage())
                .put("correlationId", header != null && CORRELATION.matcher(header).matches() ? header
                        : Ulid.next(Instant.now()));
        switch (e) {
            case BpmnUnsupportedElementException u -> body.set("problems", Views.problems(u.problems()));
            case ValidationFailedException v -> body.set("problems", Views.problems(v.problems()));
            case StepFailedException s -> body.put("nodeId", s.nodeId()).put("reason", s.reason());
            default -> { }
        }
        return ResponseEntity.status(status).contentType(PROBLEM)
                .body(EngineJson.JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
    }
}
