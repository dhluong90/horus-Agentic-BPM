package io.github.dhluong90.bpm.rules.rest;

import io.github.dhluong90.bpm.rules.api.DecisionLogEntry;
import io.github.dhluong90.bpm.rules.api.DecisionLogQuery;
import io.github.dhluong90.bpm.rules.api.DecisionRequest;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.DecisionSearch;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.Page;
import io.github.dhluong90.bpm.rules.api.PageRequest;
import io.github.dhluong90.bpm.rules.api.QueryInvalidException;
import io.github.dhluong90.bpm.rules.api.RuleEngine;
import io.github.dhluong90.bpm.rules.internal.json.EngineValues;
import io.github.dhluong90.bpm.rules.internal.service.EngineDefaults;
import io.github.dhluong90.bpm.rules.spi.CallerResolver;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
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
import tools.jackson.databind.node.ObjectNode;

/**
 * Evaluation of the effective version. The body is {@code {"inputs": {...}}}; any other member, such
 * as a {@code tenantId}, is ignored (RLE-011).
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
     * {@code query.max-range}, newest first, keyset-paged (RLE-029). Writes nothing.
     */
    @GetMapping
    public ResponseEntity<byte[]> search(
            @RequestParam(value = "ruleSet", required = false) String ruleSet,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "limit", required = false) String limit,
            @RequestParam(value = "cursor", required = false) String cursor) {
        RestSupport.require(callers, "rules:log:read");
        DecisionSearch search = DecisionSearch.of(ruleSet, instant("from", from), instant("to", to));
        Page<DecisionLogEntry> page = log.search(search, new PageRequest(RestSupport.limit(limit), cursor));
        return RestSupport.json(HttpStatus.OK, RestSupport.page(page, EngineValues::decisionLogEntry));
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
     * {@code POST /decisions/{ruleSet}:evaluate}. A failed evaluation ({@code UNIQUE_HIT_VIOLATION},
     * {@code EVALUATION_ERROR}) is logged and answered {@code 422} with its {@code decisionId} and
     * {@code matchedRuleIds}, never with partial outputs.
     */
    @PostMapping("/{ruleSet}:evaluate")
    public ResponseEntity<byte[]> evaluate(
            @PathVariable("ruleSet") String ruleSet,
            InputStream requestBody,
            @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = ProblemDetails.CORRELATION_HEADER, required = false) String correlationId)
            throws IOException {
        RestSupport.require(callers, "rules:evaluate");
        byte[] body = RestSupport.boundedBody(requestBody, contentLength, EngineDefaults.EVALUATE_BODY_MAX_BYTES);
        String correlation = ProblemDetails.correlationId(correlationId);
        JsonNode request = EngineValues.readRequest(body);
        if (!request.isObject()) {
            throw new InputInvalidException("Request body must be a JSON object");
        }
        DecisionRequest decision = DecisionRequest.of(ruleSet, EngineValues.inputsFromJson(request.get("inputs")))
                .withIdempotencyKey(idempotencyKey)
                .withCorrelationId(correlation);
        DecisionResult result = engine.evaluate(decision);
        ObjectNode json = EngineValues.decision(result);
        if (result.outcome() instanceof DecisionResult.Failed failed) {
            ObjectNode problem = ProblemDetails.body(failed.code(), ProblemDetails.UNPROCESSABLE, failed.detail(),
                    correlation);
            problem.put("decisionId", result.decisionId());
            problem.set("matchedRuleIds", EngineValues.value(failed.matchedRuleIds()));
            return ProblemDetails.response(ProblemDetails.UNPROCESSABLE, problem);
        }
        return RestSupport.json(HttpStatus.OK, json);
    }
}
