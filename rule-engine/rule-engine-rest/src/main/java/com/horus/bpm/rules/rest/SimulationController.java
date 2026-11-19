package com.horus.bpm.rules.rest;

import com.horus.bpm.rules.api.DecisionInput;
import com.horus.bpm.rules.api.InputInvalidException;
import com.horus.bpm.rules.api.RuleEngine;
import com.horus.bpm.rules.internal.json.EngineValues;
import com.horus.bpm.rules.internal.service.EngineDefaults;
import com.horus.bpm.rules.spi.CallerResolver;
import java.io.IOException;
import java.io.InputStream;
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
 * Simulation of a stored version over sample inputs, beside the version effective now (RLE-016). Writes nothing:
 * no decision row, idempotency record or event, so it takes no {@code Idempotency-Key}. Members of the body other
 * than {@code samples} and their {@code inputs} are ignored, a {@code tenantId} included.
 */
@RestController
@RequestMapping(RestSupport.BASE + "/rule-sets")
public class SimulationController {

    private final RuleEngine engine;
    private final CallerResolver callers;

    /**
     * Creates the controller.
     *
     * @param engine the rule engine
     * @param callers resolves the caller and its permissions
     */
    public SimulationController(RuleEngine engine, CallerResolver callers) {
        this.engine = Objects.requireNonNull(engine, "engine must not be null");
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
        return RestSupport.json(HttpStatus.OK, EngineValues.simulation(engine.simulate(name, version, inputs)));
    }
}
