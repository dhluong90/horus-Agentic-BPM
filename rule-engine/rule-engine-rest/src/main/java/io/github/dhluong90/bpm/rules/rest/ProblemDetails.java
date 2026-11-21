package io.github.dhluong90.bpm.rules.rest;

import io.github.dhluong90.bpm.rules.api.HorusBpmException;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.api.Violation;
import io.github.dhluong90.bpm.rules.internal.json.CorrelationIds;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import io.github.dhluong90.bpm.rules.internal.json.EngineValues;
import io.github.dhluong90.bpm.rules.internal.json.Ulid;
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
@RestControllerAdvice(assignableTypes = {RuleSetController.class, DecisionController.class,
    DecisionLogController.class, SimulationController.class, RuleSetHistoryController.class})
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
            Map.entry("CLOCK_NOT_ALLOWED", HttpStatus.FORBIDDEN),
            Map.entry("DECISION_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("RULE_SET_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("VERSION_NOT_FOUND", HttpStatus.NOT_FOUND),
            Map.entry("NO_EFFECTIVE_VERSION", HttpStatus.NOT_FOUND),
            Map.entry("CONTENT_HASH_REQUIRED", HttpStatus.BAD_REQUEST),
            Map.entry("VERSION_CONFLICT", HttpStatus.CONFLICT),
            Map.entry("CONTENT_CHANGED", HttpStatus.CONFLICT),
            Map.entry("INVALID_STATE", HttpStatus.CONFLICT),
            Map.entry("IDEMPOTENCY_IN_FLIGHT", HttpStatus.CONFLICT),
            Map.entry("RULE_SET_EXISTS", HttpStatus.CONFLICT),
            Map.entry("WARNINGS_NOT_ACKNOWLEDGED", HttpStatus.CONFLICT),
            Map.entry("LAST_VERSION_IN_EFFECT", HttpStatus.CONFLICT),
            Map.entry("AUTHOR_UNKNOWN", HttpStatus.CONFLICT),
            Map.entry("PAYLOAD_TOO_LARGE", HttpStatus.PAYLOAD_TOO_LARGE),
            Map.entry("PRECONDITION_REQUIRED", HttpStatus.PRECONDITION_REQUIRED),
            Map.entry("DRAFT_AUTHORING_DISABLED", HttpStatus.FORBIDDEN),
            Map.entry("RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS),
            Map.entry("VALIDATION_FAILED", UNPROCESSABLE),
            Map.entry("BATCH_TOO_LARGE", UNPROCESSABLE),
            Map.entry("HISTORY_TOO_LARGE", UNPROCESSABLE),
            Map.entry("EVIDENCE_TOO_LARGE", UNPROCESSABLE),
            Map.entry("CMMN_UNSUPPORTED", UNPROCESSABLE),
            Map.entry("DMN_UNSUPPORTED_ELEMENT", UNPROCESSABLE),
            Map.entry("EXPRESSION_LANGUAGE_UNSUPPORTED", UNPROCESSABLE),
            Map.entry("IDEMPOTENCY_KEY_REUSE_MISMATCH", UNPROCESSABLE),
            Map.entry("EVALUATION_ERROR", UNPROCESSABLE),
            Map.entry("UNIQUE_HIT_VIOLATION", UNPROCESSABLE),
            Map.entry("REPLAY_NOT_POSSIBLE", UNPROCESSABLE),
            Map.entry("INTERNAL_ERROR", HttpStatus.INTERNAL_SERVER_ERROR),
            Map.entry("SCHEMA_NOT_READY", HttpStatus.SERVICE_UNAVAILABLE));

    /**
     * Answers an engine exception. A code outside the matrix (for example one a host extension made up) is answered
     * as an unexpected failure, so its message never reaches the body of a {@code 500} (TR-SEC-08).
     *
     * @param e the exception
     * @param request the request, for its correlation id
     * @return the problem response
     */
    @ExceptionHandler(HorusBpmException.class)
    public ResponseEntity<byte[]> handle(HorusBpmException e, WebRequest request) {
        HttpStatus status = STATUS.get(e.code());
        if (status == null) {
            return unexpected(e, request.getHeader(CORRELATION_HEADER));
        }
        ObjectNode body = body(e.code(), status, e.getMessage(), request.getHeader(CORRELATION_HEADER));
        if (e instanceof ValidationFailedException failed) {
            ArrayNode violations = body.putArray("violations");
            for (Violation v : failed.violations()) {
                ObjectNode item = violations.addObject();
                item.set("ruleId", EngineValues.value(v.ruleId()));
                item.set("column", EngineValues.value(v.column()));
                item.put("message", v.message());
                // RLE-004: a stable code and the character position in the cell, null when the problem has none.
                item.set("code", EngineValues.value(v.code()));
                item.set("position", EngineValues.value(v.position()));
            }
            body.put("violationsTruncated", failed.truncated());
        }
        if (e instanceof io.github.dhluong90.bpm.rules.api.UnsupportedStandardException unsupported) {
            // RLE-060, RLE-061: what the file uses that the engine does not support, by name, line and stage
            if (unsupported.namespace() != null) {
                body.put("namespace", unsupported.namespace()).put("line", unsupported.line());
            } else {
                ArrayNode elements = body.putArray("elements");
                for (var item : unsupported.elements()) {
                    ObjectNode node = elements.addObject();
                    node.put("element", item.element());
                    node.set("id", EngineValues.value(item.id()));
                    node.put("line", item.line());
                    node.put("stage", item.stage());
                }
                if (unsupported.truncated()) {
                    body.put("violationsTruncated", true);
                }
            }
        }
        if (e instanceof io.github.dhluong90.bpm.rules.api.HistoryTooLargeException large) {
            // RLE-077: how many decisions the period holds and the setting that bounds it
            body.put("found", large.found());
            body.put("atLeast", large.atLeast());
            body.put("maxHistoryDecisions", large.limit());
        }
        if (e instanceof io.github.dhluong90.bpm.rules.api.VersionConflictException conflict
                && conflict.currentCanonicalHash() != null) {
            // RLE-084: what is stored now, so a client can say "changed since you opened it" and offer the new content
            body.put("currentCanonicalHash", conflict.currentCanonicalHash());
            body.put("currentLockVersion", conflict.currentLockVersion());
        }
        if (e instanceof io.github.dhluong90.bpm.rules.api.ContentChangedException changed) {
            // RLE-084: the reviewer sees the content now stored and decides again; a client never retries on its own
            body.put("currentCanonicalHash", changed.currentCanonicalHash());
            body.put("currentLockVersion", changed.currentLockVersion());
        }
        if (e instanceof io.github.dhluong90.bpm.rules.api.WarningsNotAcknowledgedException unacknowledged) {
            // RLE-053: the client shows these again and the approver resends with the codes; never retried on its own.
            ArrayNode warnings = body.putArray("warnings");
            for (var w : unacknowledged.warnings()) {
                warnings.addObject().put("code", w.code()).put("input", w.input())
                        .set("matchedName", EngineValues.value(w.matchedName()));
            }
            ArrayNode setting = body.putArray("setting");
            unacknowledged.setting().forEach(setting::add);
        }
        if (e instanceof io.github.dhluong90.bpm.rules.api.LastVersionInEffectException last) {
            // PD-101: what a client needs to word the warning and to retry with the confirmation
            body.put("ruleSet", last.ruleSet());
            body.put("version", last.version());
            body.put("setting", last.setting());
            body.set("nextEffectiveFrom", EngineValues.value(last.nextEffectiveFrom()));
        }
        ResponseEntity<byte[]> response = response(status, body);
        if (e instanceof io.github.dhluong90.bpm.rules.api.RateLimitedException limited) {
            return ResponseEntity.status(status).header("Retry-After", Long.toString(limited.retryAfterSeconds()))
                    .contentType(PROBLEM).body(response.getBody());
        }
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
