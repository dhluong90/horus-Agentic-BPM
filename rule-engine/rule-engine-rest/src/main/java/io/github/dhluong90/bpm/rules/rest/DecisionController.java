package io.github.dhluong90.bpm.rules.rest;

import io.github.dhluong90.bpm.rules.api.DecisionRequest;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.RuleEngine;
import io.github.dhluong90.bpm.rules.internal.json.EngineValues;
import io.github.dhluong90.bpm.rules.internal.service.EngineDefaults;
import io.github.dhluong90.bpm.rules.spi.CallerResolver;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Evaluation of the version in force at a clock, of a pinned version, and replay of a logged decision. The evaluate
 * body is {@code {"inputs": {...}, "clock": "..."}}; any other member, such as a {@code tenantId}, is ignored
 * (RLE-011).
 */
@RestController
@RequestMapping(RestSupport.BASE + "/decisions")
public class DecisionController {

    private final RuleEngine engine;
    private final CallerResolver callers;

    /**
     * Creates the controller.
     *
     * @param engine the rule engine
     * @param callers resolves the caller and its permissions
     */
    public DecisionController(RuleEngine engine, CallerResolver callers) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.callers = Objects.requireNonNull(callers, "callers must not be null");
    }

    /**
     * {@code POST /decisions/{ruleSet}:evaluate}: the version in force at {@code clock} (default the host clock). A
     * failed evaluation ({@code UNIQUE_HIT_VIOLATION}, {@code EVALUATION_ERROR}) is logged and answered {@code 422}
     * with its {@code decisionId} and {@code matchedRuleIds}, never with partial outputs.
     */
    @PostMapping("/{ruleSet}:evaluate")
    public ResponseEntity<byte[]> evaluate(
            @PathVariable("ruleSet") String ruleSet,
            InputStream requestBody,
            @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = ProblemDetails.CORRELATION_HEADER, required = false) String correlationId)
            throws IOException {
        return evaluate(ruleSet, null, "rules:evaluate", requestBody, contentLength, idempotencyKey, correlationId);
    }

    /**
     * {@code POST /decisions/{ruleSet}/versions/{v}:evaluate}: one approved version, at {@code clock} or the host
     * clock. Needs {@code rules:pin}, checked before the body is read. A draft is {@code 409 INVALID_STATE}; it is simulated instead, which writes no decision row.
     */
    @PostMapping("/{ruleSet}/versions/{version:\\d+}:evaluate")
    public ResponseEntity<byte[]> evaluateVersion(
            @PathVariable("ruleSet") String ruleSet,
            @PathVariable("version") int version,
            InputStream requestBody,
            @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = ProblemDetails.CORRELATION_HEADER, required = false) String correlationId)
            throws IOException {
        if (version < 1) {
            throw new InputInvalidException("version must be at least 1");
        }
        return evaluate(ruleSet, version, "rules:pin", requestBody, contentLength, idempotencyKey, correlationId);
    }

    /**
     * {@code POST /decisions/{decisionId}:replay}: the logged decision evaluated again with its version, inputs and
     * clock; writes nothing. A logged failure is answered as at evaluation, {@code 422} with the logged id.
     */
    @PostMapping("/{decisionId}:replay")
    public ResponseEntity<byte[]> replay(
            @PathVariable("decisionId") String decisionId,
            @RequestHeader(value = ProblemDetails.CORRELATION_HEADER, required = false) String correlationId) {
        RestSupport.require(callers, "rules:log:read");
        return answer(engine.replay(decisionId), ProblemDetails.correlationId(correlationId));
    }

    private ResponseEntity<byte[]> evaluate(String ruleSet, Integer version, String permission, InputStream requestBody,
            Long contentLength, String idempotencyKey, String correlationId) throws IOException {
        RestSupport.require(callers, permission);
        byte[] body = RestSupport.boundedBody(requestBody, contentLength, EngineDefaults.EVALUATE_BODY_MAX_BYTES);
        String correlation = ProblemDetails.correlationId(correlationId);
        JsonNode request = EngineValues.readRequest(body);
        if (!request.isObject()) {
            throw new InputInvalidException("Request body must be a JSON object");
        }
        DecisionRequest decision = DecisionRequest.of(ruleSet, EngineValues.inputsFromJson(request.get("inputs")))
                .withIdempotencyKey(idempotencyKey)
                .withCorrelationId(correlation)
                .withClock(clock(request.get("clock")))
                .withVersion(version);
        return answer(engine.evaluate(decision), correlation);
    }

    /** An ISO 8601 instant with {@code Z} or an offset, or null when the member is absent or null. */
    private static Instant clock(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw new InputInvalidException("clock must be an ISO 8601 instant such as 2026-11-20T10:00:00Z");
        }
        try {
            return OffsetDateTime.parse(value.stringValue()).toInstant();
        } catch (DateTimeParseException e) {
            throw new InputInvalidException("clock must be an ISO 8601 instant with Z or an offset, such as"
                    + " 2026-11-20T10:00:00Z");
        }
    }

    private static ResponseEntity<byte[]> answer(DecisionResult result, String correlation) {
        if (result.outcome() instanceof DecisionResult.Failed failed) {
            ObjectNode problem = ProblemDetails.body(failed.code(), ProblemDetails.UNPROCESSABLE, failed.detail(),
                    correlation);
            problem.put("decisionId", result.decisionId());
            problem.set("matchedRuleIds", EngineValues.value(failed.matchedRuleIds()));
            return ProblemDetails.response(ProblemDetails.UNPROCESSABLE, problem);
        }
        return RestSupport.json(HttpStatus.OK, EngineValues.decision(result));
    }
}
