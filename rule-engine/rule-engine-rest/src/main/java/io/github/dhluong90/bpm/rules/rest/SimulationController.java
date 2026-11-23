package io.github.dhluong90.bpm.rules.rest;

import io.github.dhluong90.bpm.rules.api.ComparisonRequest;
import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.HistorySimulation;
import io.github.dhluong90.bpm.rules.api.HistorySimulationResult;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.QueryInvalidException;
import io.github.dhluong90.bpm.rules.api.RuleEngine;
import io.github.dhluong90.bpm.rules.api.RuleReadings;
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
                EngineValues.simulation(simulateSamples(name, version, requestBody, contentLength, null)));
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
        List<String> labels = new ArrayList<>();
        SimulationResult result = simulateSamples(name, version, requestBody, contentLength, labels);
        return RestSupport.json(HttpStatus.OK, RestSupport.simulationReadings(versions.readSimulation(result, labels)));
    }

    /** Runs the samples; {@code labels}, when given, receives each sample's {@code label} or null. */
    private SimulationResult simulateSamples(String name, int version, InputStream requestBody, Long contentLength,
            List<String> labels) throws IOException {
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
            JsonNode label = sample.get("label");
            if (label != null && !label.isNull() && (!label.isString() || label.stringValue().isBlank()
                    || label.stringValue().length() > RuleReadings.LABEL_MAX)) {
                throw new InputInvalidException("samples[" + i + "].label must be text of 1 to "
                        + RuleReadings.LABEL_MAX + " characters");
            }
            if (labels != null) {
                labels.add(label == null || label.isNull() ? null : label.stringValue());
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
        HistorySimulation period = historyPeriod(requestBody, contentLength);
        return RestSupport.json(HttpStatus.OK,
                EngineValues.historySimulation(engine.simulateHistory(name, version, period)));
    }

    /** Reads {@code from}, {@code to} and the optional {@code limit} of a history simulation body (RLE-015). */
    private static HistorySimulation historyPeriod(InputStream requestBody, Long contentLength) throws IOException {
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
        return new HistorySimulation(instant(request, "from"), instant(request, "to"), limit);
    }

    /**
     * {@code POST /rule-sets/{name}/versions/{v}:simulate-history-readings}, the body of {@code :simulate-history}: the
     * replay in plain language, each decision's outcome read in the version that decided it (RLE-202). Needs
     * {@code rules:simulate}, {@code rules:log:read} and {@code rules:read}, all checked before the body is read. A
     * decision with a masked input that had a value, or whose logged version cannot be evaluated again, is listed as
     * {@code REPLAY_NOT_POSSIBLE} with the names of the masked inputs and no reading. Without a {@code limit} the run is
     * the most one request reads (500); a larger {@code limit} is {@code 422 READINGS_TOO_LARGE}. Nothing is logged.
     *
     * @param name the rule set name
     * @param version the version to simulate
     * @param requestBody the body, read up to {@link EngineDefaults#COMMAND_BODY_MAX_BYTES}
     * @param contentLength the declared length, or null
     * @return one reading per logged decision, newest first
     * @throws IOException when the body cannot be read
     */
    @PostMapping("/{name}/versions/{version:\\d+}:simulate-history-readings")
    public ResponseEntity<byte[]> simulateHistoryReadings(
            @PathVariable("name") String name,
            @PathVariable("version") int version,
            InputStream requestBody,
            @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength) throws IOException {
        RestSupport.require(callers, "rules:simulate");
        RestSupport.require(callers, "rules:log:read");
        RestSupport.require(callers, "rules:read");
        HistorySimulation period = historyPeriod(requestBody, contentLength);
        int limit = period.limit() == null ? EngineDefaults.READINGS_MAX : period.limit();
        if (limit > EngineDefaults.READINGS_MAX) {
            throw new io.github.dhluong90.bpm.rules.api.ReadingsTooLargeException("decisions", limit,
                    EngineDefaults.READINGS_MAX);
        }
        HistorySimulationResult result = engine.simulateHistory(name, version, period.withLimit(limit));
        return RestSupport.json(HttpStatus.OK, RestSupport.historyReadings(versions.readHistorySimulation(result)));
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

    /**
     * {@code POST /rule-sets/{name}:compare}, body {@code {"base": 1, "candidate": 2, "from": "...", "to": "..."}}: the
     * two versions compared over the logged decisions of a past period (RLE-075 to RLE-077). It needs both
     * {@code rules:simulate} and {@code rules:log:read}, writes nothing and takes no {@code Idempotency-Key}.
     * Members other than the four are ignored, a {@code tenantId} included.
     *
     * @param name the rule set name
     * @param requestBody the body, read up to {@link EngineDefaults#COMMAND_BODY_MAX_BYTES}
     * @param contentLength the declared length, or null
     * @return the outcome counts, totals and transitions
     * @throws IOException when the body cannot be read
     */
    @PostMapping("/{name}:compare")
    public ResponseEntity<byte[]> compare(
            @PathVariable("name") String name,
            InputStream requestBody,
            @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength) throws IOException {
        // Both permissions first, then a bounded read: a caller without either never makes the host buffer a body.
        RestSupport.require(callers, "rules:simulate");
        RestSupport.require(callers, "rules:log:read");
        byte[] body = RestSupport.boundedBody(requestBody, contentLength, EngineDefaults.COMMAND_BODY_MAX_BYTES);
        JsonNode request = EngineValues.readRequest(body);
        if (!request.isObject()) {
            throw new InputInvalidException("Request body must be a JSON object with base, candidate, from and to");
        }
        return RestSupport.json(HttpStatus.OK, EngineValues.comparison(engine.compare(name, new ComparisonRequest(
                versionNumber(request, "base"), versionNumber(request, "candidate"), requiredInstant(request, "from"),
                requiredInstant(request, "to")))));
    }

    private static int versionNumber(JsonNode request, String member) {
        JsonNode value = request.get(member);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 1) {
            throw new InputInvalidException(member + " must be a version number, a whole number of 1 or more");
        }
        return value.asInt();
    }

    private static java.time.Instant requiredInstant(JsonNode request, String member) {
        JsonNode value = request.get(member);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            throw new QueryInvalidException(member + " is required");
        }
        try {
            return java.time.Instant.parse(value.stringValue());
        } catch (java.time.format.DateTimeParseException e) {
            throw new QueryInvalidException(member + " must be an ISO 8601 instant such as 2027-01-01T00:00:00Z");
        }
    }
}
