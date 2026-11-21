package io.github.dhluong90.bpm.rules.rest;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.HistorySimulation;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.QueryInvalidException;
import io.github.dhluong90.bpm.rules.api.RuleEngine;
import io.github.dhluong90.bpm.rules.api.SimulationResult;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.internal.json.EngineValues;
import io.github.dhluong90.bpm.rules.internal.service.EngineDefaults;
import io.github.dhluong90.bpm.rules.spi.CallerResolver;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
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

/**
 * Simulation of a stored version over sample inputs, beside the version effective now (RLE-016), and over the logged
 * decisions of a period, beside what was decided (RLE-015). Writes nothing: no decision row, idempotency record or
 * event, so neither takes an {@code Idempotency-Key}. Members of the body other than those named are ignored, a
 * {@code tenantId} included.
 */
@RestController
@RequestMapping(RestSupport.BASE + "/rule-sets")
public class SimulationController {

    private final RuleEngine engine;
    private final VersionService versions;
    private final CallerResolver callers;

    /**
     * Creates the controller.
     *
     * @param engine the rule engine
     * @param versions reads the result in plain language
     * @param callers resolves the caller and its permissions
     */
    public SimulationController(RuleEngine engine, VersionService versions, CallerResolver callers) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
        this.versions = Objects.requireNonNull(versions, "versions must not be null");
        this.callers = Objects.requireNonNull(callers, "callers must not be null");
    }

    /**
     * {@code POST /rule-sets/{name}/versions/{v}:simulate}, body {@code {"samples": [{"inputs": {...}}, ...]}}.
     *
     * @param name the rule set name
     * @param version the version to simulate
     * @param requestBody the body, read up to {@link EngineDefaults#EVALUATE_BODY_MAX_BYTES}
     * @param contentLength the declared length, or null
     * @return one result per sample, with the changed ones flagged
     * @throws IOException when the body cannot be read
     */
    @PostMapping("/{name}/versions/{version:\\d+}:simulate")
    public ResponseEntity<byte[]> simulate(
            @PathVariable("name") String name,
            @PathVariable("version") int version,
            InputStream requestBody,
            @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength) throws IOException {
        // Permission first, then a bounded read: a caller without rules:simulate never makes the host buffer a body.
        RestSupport.require(callers, "rules:simulate");
        return RestSupport.json(HttpStatus.OK,
                EngineValues.simulation(simulateSamples(name, version, requestBody, contentLength)));
    }

    /**
     * {@code POST /rule-sets/{name}/versions/{v}:simulate-readings}, the body of {@code :simulate}: the simulation
     * result in plain language, each side read in the version that decided it (RLE-093). The sample inputs are not
     * echoed. Needs {@code rules:simulate} and {@code rules:read}, both checked before the body is read; more samples
     * than one request reads is {@code 422 READINGS_TOO_LARGE}. Nothing is logged.
     *
     * @param name the rule set name
     * @param version the version to simulate
     * @param requestBody the body, read up to {@link EngineDefaults#EVALUATE_BODY_MAX_BYTES}
     * @param contentLength the declared length, or null
     * @return one reading per sample
     * @throws IOException when the body cannot be read
     */
    @PostMapping("/{name}/versions/{version:\\d+}:simulate-readings")
    public ResponseEntity<byte[]> simulateReadings(
            @PathVariable("name") String name,
            @PathVariable("version") int version,
            InputStream requestBody,
            @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength) throws IOException {
        RestSupport.require(callers, "rules:simulate");
        RestSupport.require(callers, "rules:read");
        return RestSupport.json(HttpStatus.OK, RestSupport.simulationReadings(
                versions.readSimulation(simulateSamples(name, version, requestBody, contentLength))));
    }

    private SimulationResult simulateSamples(String name, int version, InputStream requestBody, Long contentLength)
            throws IOException {
        byte[] body = RestSupport.boundedBody(requestBody, contentLength, EngineDefaults.EVALUATE_BODY_MAX_BYTES);
        JsonNode request = EngineValues.readRequest(body);
        JsonNode samples = request.isObject() ? request.get("samples") : null;
        if (samples == null || !samples.isArray()) {
            throw new InputInvalidException("Request body must be a JSON object with a samples array");
        }
        // Bounded before any sample is converted; the engine applies simulation.max-inputs, which is at most this.
        if (samples.isEmpty() || samples.size() > EngineDefaults.SIMULATION_MAX_SAMPLES) {
            throw new InputInvalidException("samples must hold 1 to " + EngineDefaults.SIMULATION_MAX_SAMPLES
                    + " input sets (simulation.max-inputs)");
        }
        List<DecisionInput> inputs = new ArrayList<>(samples.size());
        for (int i = 0; i < samples.size(); i++) {
            JsonNode sample = samples.get(i);
            if (!sample.isObject() || !sample.path("inputs").isObject()) {
                throw new InputInvalidException("samples[" + i + "] must be an object with an inputs object");
            }
            try {
                inputs.add(EngineValues.inputsFromJson(sample.get("inputs")));
            } catch (InputInvalidException e) {
                throw new InputInvalidException("samples[" + i + "]: " + e.getMessage());
            }
        }
        return engine.simulate(name, version, inputs);
    }

    /**
     * {@code POST /rule-sets/{name}/versions/{v}:simulate-history}, body {@code {"from": ..., "to": ..., "limit": n}}.
     *
     * @param name the rule set name
     * @param version the version to simulate
     * @param requestBody the body, read up to {@link EngineDefaults#COMMAND_BODY_MAX_BYTES}
     * @param contentLength the declared length, or null
     * @return one item per logged decision, newest first, with the changed ones flagged
     * @throws IOException when the body cannot be read
     */
    @PostMapping("/{name}/versions/{version:\\d+}:simulate-history")
    public ResponseEntity<byte[]> simulateHistory(
            @PathVariable("name") String name,
            @PathVariable("version") int version,
            InputStream requestBody,
            @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength) throws IOException {
        // Both permissions first, then a bounded read: the answer is built from logged inputs (RLE-015).
        RestSupport.require(callers, "rules:simulate");
        RestSupport.require(callers, "rules:log:read");
        byte[] body = RestSupport.boundedBody(requestBody, contentLength, EngineDefaults.COMMAND_BODY_MAX_BYTES);
        JsonNode request = EngineValues.readRequest(body);
        if (!request.isObject()) {
            throw new QueryInvalidException("Request body must be a JSON object with from and to");
        }
        Integer limit = null;
        JsonNode limitNode = request.get("limit");
        if (limitNode != null && !limitNode.isNull()) {
            if (!limitNode.isIntegralNumber() || !limitNode.canConvertToInt()) {
                throw new QueryInvalidException("limit must be a whole number");
            }
            limit = limitNode.intValue();
        }
        HistorySimulation period = new HistorySimulation(instant(request, "from"), instant(request, "to"), limit);
        return RestSupport.json(HttpStatus.OK,
                EngineValues.historySimulation(engine.simulateHistory(name, version, period)));
    }

    private static Instant instant(JsonNode request, String member) {
        JsonNode node = request.get(member);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            throw new QueryInvalidException(member + " must be an ISO 8601 instant");
        }
        try {
            return Instant.parse(node.stringValue());
        } catch (DateTimeParseException e) {
            throw new QueryInvalidException(member + " must be an ISO 8601 instant, such as 2026-11-01T00:00:00Z");
        }
    }
}
