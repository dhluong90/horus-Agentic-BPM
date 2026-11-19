package com.horus.bpm.rules.rest;

import com.horus.bpm.rules.api.HorusBpmException;
import com.horus.bpm.rules.api.ValidationFailedException;
import com.horus.bpm.rules.api.Violation;
import com.horus.bpm.rules.internal.json.CorrelationIds;
import com.horus.bpm.rules.internal.json.EngineJson;
import com.horus.bpm.rules.internal.json.EngineValues;
import com.horus.bpm.rules.internal.json.Ulid;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Maps engine exceptions to RFC 9457 {@code application/problem+json} with {@code code},
 * {@code detail} and {@code correlationId} (ADR-0010). Applies to the engine controllers only.
 */
@RestControllerAdvice(assignableTypes = {RuleSetController.class, DecisionController.class})
public class ProblemDetails {

    private static final System.Logger LOG = System.getLogger(ProblemDetails.class.getName());

    /** Request header carrying the caller's correlation id. */
    public static final String CORRELATION_HEADER = "X-Correlation-Id";

    /** 422 by number: the enum constant is named differently in Spring Framework 6.2 and 7 (ADR-0011). */
    static final HttpStatus UNPROCESSABLE = HttpStatus.valueOf(422);

    private static final MediaType PROBLEM = MediaType.parseMediaType("application/problem+json");
    private static final String TYPE_BASE = "https://github.com/dhluong90/horus-Agentic-BPM/wiki/Common-Error-Codes#";

    static final Map<String, HttpStatus> STATUS = Map.ofEntries(
            Map.entry("INPUT_INVALID", HttpStatus.BAD_REQUEST),
            Map.entry("QUERY_INVALID", HttpStatus.BAD_REQUEST),
            Map.entry("UNAUTHENTICATED", HttpStatus.UNAUTHORIZED),
            Map.entry("PERMISSION_DENIED", HttpStatus.FORBIDDEN),
            Map.entry("SELF_APPROVAL_FORBIDDEN", HttpStatus.FORBIDDEN),
            Map.entry("RULE_SET_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("VERSION_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("NO_EFFECTIVE_VERSION", HttpStatus.NOT_FOUND),
            Map.entry("VERSION_CONFLICT", HttpStatus.CONFLICT),
            Map.entry("INVALID_STATE", HttpStatus.CONFLICT),
            Map.entry("IDEMPOTENCY_IN_FLIGHT", HttpStatus.CONFLICT),
            Map.entry("RULE_SET_EXISTS", HttpStatus.CONFLICT),
            Map.entry("VALIDATION_FAILED", UNPROCESSABLE),
            Map.entry("IDEMPOTENCY_KEY_REUSE_MISMATCH", UNPROCESSABLE),
            Map.entry("EVALUATION_ERROR", UNPROCESSABLE),
            Map.entry("UNIQUE_HIT_VIOLATION", UNPROCESSABLE),
            Map.entry("PAYLOAD_TOO_LARGE", HttpStatus.PAYLOAD_TOO_LARGE),
            Map.entry("INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR));

    /**
     * Answers an engine exception.
     *
     * @param e the exception
     * @param request the request, for its correlation id
     * @return the problem response
     */
    @ExceptionHandler(HorusBpmException.class)
    public ResponseEntity<byte[]> handle(HorusBpmException e, WebRequest request) {
        HttpStatus status = STATUS.getOrDefault(e.code(), HttpStatus.INTERNAL_SERVER_ERROR);
        ObjectNode body = body(e.code(), status, e.getMessage(), request.getHeader(CORRELATION_HEADER));
        if (e instanceof ValidationFailedException failed) {
            ArrayNode violations = body.putArray("violations");
            for (Violation v : failed.violations()) {
                ObjectNode item = violations.addObject();
                item.set("ruleId", EngineValues.value(v.ruleId()));
                item.set("column", EngineValues.value(v.column()));
                item.put("message", v.message());
            }
            body.put("violationsTruncated", failed.truncated());
        }
        ResponseEntity<byte[]> response = response(status, body);
        if (status == HttpStatus.CONFLICT && "IDEMPOTENCY_IN_FLIGHT".equals(e.code())) {
            return ResponseEntity.status(status).header("Retry-After", "1").contentType(PROBLEM).body(response.getBody());
        }
        return response;
    }

    /**
     * Answers anything else an engine controller throws (RLE-036, TR-SEC-08). A failure Spring already classifies as
     * a client error (unreadable body, unsupported media type, missing parameter) keeps its 4xx status as
     * {@code INPUT_INVALID}. Everything else is {@code 500 INTERNAL_ERROR} with only a {@code correlationId}: no
     * message, class name, SQL or table name reaches the body. The exception is logged with the same id.
     *
     * @param e the exception
     * @param request the request, for its correlation id
     * @return the problem response
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<byte[]> handleUnexpected(Exception e, WebRequest request) {
        return unexpected(e, request.getHeader(CORRELATION_HEADER));
    }

    static ResponseEntity<byte[]> unexpected(Exception e, String correlationHeader) {
        String correlationId = correlationId(correlationHeader);
        if (e instanceof org.springframework.web.ErrorResponse error && error.getStatusCode().is4xxClientError()) {
            HttpStatus status = HttpStatus.valueOf(error.getStatusCode().value());
            return response(status, body("INPUT_INVALID", status, "The request could not be read: "
                    + status.getReasonPhrase(), correlationId));
        }
        LOG.log(System.Logger.Level.ERROR, "Unexpected failure in a Rule Engine endpoint, correlationId " + correlationId, e);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, body("INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR,
                "Unexpected failure; quote the correlationId to the operator", correlationId));
    }

    static ObjectNode body(String code, HttpStatus status, String detail, String correlationId) {
        return EngineJson.NODES.objectNode()
                .put("type", TYPE_BASE + code.toLowerCase(Locale.ROOT))
                .put("title", status.getReasonPhrase())
                .put("status", status.value())
                .put("code", code)
                .put("detail", detail)
                .put("correlationId", correlationId(correlationId));
    }

    /**
     * Returns the caller's correlation id when it is well-formed, otherwise a generated one, so a header is never
     * echoed or stored unbounded.
     *
     * @param header the {@code X-Correlation-Id} header, possibly null
     * @return a safe correlation id
     */
    static String correlationId(String header) {
        return CorrelationIds.isValid(header) ? header : Ulid.next(Instant.now());
    }

    static ResponseEntity<byte[]> response(HttpStatus status, ObjectNode body) {
        return ResponseEntity.status(status).contentType(PROBLEM)
                .body(EngineValues.write(body).getBytes(StandardCharsets.UTF_8));
    }
}
