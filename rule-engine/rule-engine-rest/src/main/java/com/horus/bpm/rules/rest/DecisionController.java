package com.horus.bpm.rules.rest;

import com.horus.bpm.rules.api.DecisionRequest;
import com.horus.bpm.rules.api.DecisionResult;
import com.horus.bpm.rules.api.InputInvalidException;
import com.horus.bpm.rules.api.RuleEngine;
import com.horus.bpm.rules.internal.json.EngineValues;
import com.horus.bpm.rules.spi.CallerResolver;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
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

    /** {@code POST /decisions/{ruleSet}:evaluate}. A failed evaluation is logged and answered {@code 422}. */
    @PostMapping("/{ruleSet}:evaluate")
    public ResponseEntity<byte[]> evaluate(
            @PathVariable("ruleSet") String ruleSet,
            @RequestBody byte[] body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = ProblemDetails.CORRELATION_HEADER, required = false) String correlationId) {
        RestSupport.require(callers, "rules:evaluate");
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
            ObjectNode problem = ProblemDetails.body(failed.code(), HttpStatus.UNPROCESSABLE_CONTENT, failed.detail(),
                    correlation);
            problem.put("decisionId", result.decisionId());
            return ProblemDetails.response(HttpStatus.UNPROCESSABLE_CONTENT, problem);
        }
        return RestSupport.json(HttpStatus.OK, json);
    }
}
