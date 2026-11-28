package io.github.dhluong90.bpm.rules.rest;

import io.github.dhluong90.bpm.rules.api.BatchRequest;
import io.github.dhluong90.bpm.rules.api.BatchResult;
import io.github.dhluong90.bpm.rules.api.CorrelationSearch;
import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionLogEntry;
import io.github.dhluong90.bpm.rules.api.DecisionLogQuery;
import io.github.dhluong90.bpm.rules.api.DecisionRequest;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.DecisionSearch;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.InputSearch;
import io.github.dhluong90.bpm.rules.api.Page;
import io.github.dhluong90.bpm.rules.api.PageRequest;
import io.github.dhluong90.bpm.rules.api.QueryInvalidException;
import io.github.dhluong90.bpm.rules.api.RuleEngine;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import io.github.dhluong90.bpm.rules.internal.json.EngineValues;
import io.github.dhluong90.bpm.rules.internal.service.EngineDefaults;
import io.github.dhluong90.bpm.rules.spi.CallerResolver;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
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
    private final DecisionLogQuery log;
    private final CallerResolver callers;

    /**
     * Creates the controller.
     *
     * @param engine the rule engine
     * @param log the decision log search
     * @param callers resolves the caller and its permissions
     */
    public DecisionController(RuleEngine engine, DecisionLogQuery log, CallerResolver callers) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.log = Objects.requireNonNull(log, "log must not be null");
        this.callers = Objects.requireNonNull(callers, "callers must not be null");
    }

    /**
     * {@code GET /decisions?ruleSet=&from=&to=}: one rule set's decisions in a period of at most
     * {@code query.max-range}, newest first, keyset-paged (RLE-029). With {@code correlationId} it is the search by
     * the host's case reference across the tenant's rule sets, {@code ruleSet} then optional (RLE-070). Writes
     * nothing.
     */
    @GetMapping
    public ResponseEntity<byte[]> search(
            @RequestParam(value = "ruleSet", required = false) String ruleSet,
            @RequestParam(value = "correlationId", required = false) String correlationId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "limit", required = false) String limit,
            @RequestParam(value = "cursor", required = false) String cursor) {
        RestSupport.require(callers, "rules:log:read");
        PageRequest page = new PageRequest(RestSupport.limit(limit), cursor);
        if (correlationId != null) {
            CorrelationSearch search = new CorrelationSearch(correlationId, ruleSet, instant("from", from),
                    instant("to", to));
            return RestSupport.json(HttpStatus.OK, RestSupport.page(log.searchByCorrelationId(search, page),
                    entry -> RestSupport.withErrorMode(EngineValues.decisionLogEntry(entry), null)));
        }
        DecisionSearch search = DecisionSearch.of(ruleSet, instant("from", from), instant("to", to));
        Page<DecisionLogEntry> result = log.search(search, page);
        return RestSupport.json(HttpStatus.OK, RestSupport.page(result, entry -> RestSupport.withErrorMode(EngineValues.decisionLogEntry(entry), null)));
    }

    private static Instant instant(String name, String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value.strip());
        } catch (DateTimeParseException e) {
            throw new QueryInvalidException(name + " must be an ISO 8601 instant in UTC, for example "
                    + "2027-01-01T00:00:00Z");
        }
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
     * {@code POST /decisions/{ruleSet}:evaluate-batch}: up to {@code evaluation.max-batch} items evaluated in order
     * against one version selected at {@code clock} (default the host clock), one logged decision per item, in one
     * transaction (RLE-067 to RLE-069). Always {@code 200} once the batch is accepted: an item that failed is a
     * {@code problem} entry and does not stop the others. A tenant in the body is ignored.
     */
    @PostMapping("/{ruleSet}:evaluate-batch")
    public ResponseEntity<byte[]> evaluateBatch(
            @PathVariable("ruleSet") String ruleSet,
            InputStream requestBody,
            @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = ProblemDetails.CORRELATION_HEADER, required = false) String correlationId)
            throws IOException {
        RestSupport.require(callers, "rules:evaluate");
        // A declared length over the limit is refused before the body is read (RLE-068).
        byte[] body = RestSupport.boundedBody(requestBody, contentLength, EngineDefaults.BATCH_BODY_MAX_BYTES);
        String correlation = ProblemDetails.correlationId(correlationId);
        JsonNode request = EngineValues.readRequest(body);
        if (!request.isObject()) {
            throw new InputInvalidException("Request body must be a JSON object");
        }
        JsonNode items = request.get("items");
        if (items == null || !items.isArray()) {
            throw new InputInvalidException("items must be an array of {inputs, correlationId}");
        }
        List<BatchRequest.Item> parsed = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            JsonNode item = items.get(i);
            if (!item.isObject()) {
                throw new InputInvalidException("items[" + i + "] must be an object with inputs");
            }
            JsonNode itemCorrelation = item.get("correlationId");
            if (itemCorrelation != null && !itemCorrelation.isNull() && !itemCorrelation.isString()) {
                throw new InputInvalidException("items[" + i + "].correlationId must be a string");
            }
            DecisionInput input;
            try {
                input = EngineValues.inputsFromJson(item.get("inputs"));
            } catch (InputInvalidException e) {
                throw new InputInvalidException("items[" + i + "]: " + e.getMessage());
            }
            parsed.add(new BatchRequest.Item(input,
                    itemCorrelation == null || itemCorrelation.isNull() ? null : itemCorrelation.stringValue()));
        }
        BatchRequest batch = new BatchRequest(ruleSet, parsed, idempotencyKey, clock(request.get("clock")));
        return RestSupport.json(HttpStatus.OK, batch(engine.evaluateBatch(batch), correlation));
    }

    /** The response of a batch: the version and clock, the counts, and one result or problem per item. */
    private static ObjectNode batch(BatchResult result, String correlation) {
        ObjectNode node = EngineJson.NODES.objectNode();
        node.put("ruleSet", result.ruleSet());
        node.put("version", result.version());
        node.put("evalClock", result.clock().toString());
        node.put("succeeded", result.succeeded());
        node.put("failed", result.failed());
        ArrayNode results = node.putArray("results");
        for (BatchResult.Item item : result.items()) {
            ObjectNode entry = results.addObject().put("index", item.index());
            if (item.decision() == null) {
                entry.set("problem", ProblemDetails.body(item.code(), ProblemDetails.STATUS.get(item.code()),
                        item.detail(), correlation));
            } else if (item.decision().outcome() instanceof DecisionResult.Failed failed) {
                ObjectNode problem = ProblemDetails.body(failed.code(), ProblemDetails.UNPROCESSABLE, failed.detail(),
                        correlation);
                problem.put("decisionId", item.decision().decisionId());
                problem.set("matchedRuleIds", EngineValues.value(failed.matchedRuleIds()));
                if (failed.limit() != null) {
                    problem.put("limit", failed.limit());
                }
                entry.set("problem", problem);
            } else {
                entry.set("decision", EngineValues.decision(item.decision()));
            }
        }
        return node;
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
                .withVersion(version)
                .withDecision(decisionId(request.get("decision")));
        return answer(engine.evaluate(decision), correlation);
    }

    /** The decision the request names, or null when the member is absent or null (DN-08, RLE-097). */
    private static String decisionId(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw new InputInvalidException("decision must be a string, the rule set's name");
        }
        return value.stringValue();
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
            if (failed.limit() != null) {
                // EVALUATION_BUDGET_EXCEEDED: STEPS or TIME, so a client tells a rule problem from a slow machine.
                problem.put("limit", failed.limit());
            }
            return ProblemDetails.response(ProblemDetails.UNPROCESSABLE, problem);
        }
        return RestSupport.json(HttpStatus.OK, EngineValues.decision(result));
    }
}
