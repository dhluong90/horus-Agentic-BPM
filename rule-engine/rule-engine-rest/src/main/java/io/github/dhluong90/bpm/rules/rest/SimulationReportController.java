package io.github.dhluong90.bpm.rules.rest;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.QueryInvalidException;
import io.github.dhluong90.bpm.rules.api.SimulationReport;
import io.github.dhluong90.bpm.rules.api.SimulationReportRequest;
import io.github.dhluong90.bpm.rules.api.SimulationReportService;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import io.github.dhluong90.bpm.rules.internal.json.EngineValues;
import io.github.dhluong90.bpm.rules.internal.json.SimulationReportJson;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Simulation reports of a version (PD-115, RLE-199, RLE-200): run once by the engine, stored bound to the version's
 * canonical hash and read by the approver. Members of the body other than those named are ignored, a {@code tenantId}
 * and any sample {@code label} included, so no caller text is stored.
 */
@RestController
@RequestMapping(RestSupport.BASE + "/rule-sets")
public class SimulationReportController {

    private final SimulationReportService reports;
    private final CallerResolver callers;

    /**
     * Creates the controller.
     *
     * @param reports runs, stores and reads reports
     * @param callers resolves the caller and its permissions
     */
    public SimulationReportController(SimulationReportService reports, CallerResolver callers) {
        this.reports = Objects.requireNonNull(reports, "reports must not be null");
        this.callers = Objects.requireNonNull(callers, "callers must not be null");
    }

    /**
     * {@code POST /rule-sets/{name}/versions/{v}/simulation-reports}, body
     * {@code {"source": "SAMPLES", "samples": [{"inputs": {...}}]}} or
     * {@code {"source": "REPLAY", "from": "...", "to": "...", "limit": 100}}. Needs {@code rules:simulate}, checked
     * before the body is read. Takes an optional {@code Idempotency-Key}.
     *
     * @param name the rule set name
     * @param version the version to report on
     * @param requestBody the body, read up to {@link EngineDefaults#EVALUATE_BODY_MAX_BYTES}
     * @param contentLength the declared length, or null
     * @param idempotencyKey optional key; a repeat returns the first report
     * @return {@code 201} with the report
     * @throws IOException when the body cannot be read
     */
    @PostMapping("/{name}/versions/{version:\\d+}/simulation-reports")
    public ResponseEntity<byte[]> create(
            @PathVariable("name") String name,
            @PathVariable("version") int version,
            InputStream requestBody,
            @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        RestSupport.require(callers, "rules:simulate");
        byte[] body = RestSupport.boundedBody(requestBody, contentLength, EngineDefaults.EVALUATE_BODY_MAX_BYTES);
        JsonNode request = EngineValues.readRequest(body);
        if (!request.isObject()) {
            throw new InputInvalidException("Request body must be a JSON object with a source");
        }
        SimulationReport report = reports.create(name, version, parse(request).withIdempotencyKey(idempotencyKey));
        return RestSupport.json(HttpStatus.CREATED, SimulationReportJson.write(report));
    }

    private static SimulationReportRequest parse(JsonNode request) {
        JsonNode source = request.get("source");
        if (source == null || !source.isString()) {
            throw new InputInvalidException("source must be SAMPLES or REPLAY");
        }
        return switch (source.stringValue()) {
            case "SAMPLES" -> SimulationReportRequest.samples(samples(request.get("samples")));
            case "REPLAY" -> SimulationReportRequest.replay(instant(request, "from"), instant(request, "to"),
                    limit(request.get("limit")));
            default -> throw new InputInvalidException("source must be SAMPLES or REPLAY");
        };
    }

    private static List<DecisionInput> samples(JsonNode samples) {
        if (samples == null || !samples.isArray()) {
            throw new InputInvalidException("A SAMPLES report needs a samples array");
        }
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
        return inputs;
    }

    private static Integer limit(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new QueryInvalidException("limit must be a whole number");
        }
        return node.intValue();
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
     * {@code GET /rule-sets/{name}/versions/{v}/simulation-reports}: the reports of a version, newest first, without
     * their rows. Needs {@code rules:read}.
     *
     * @param name the rule set name
     * @param version the version
     * @return {@code {"reports": [...]}}
     */
    @GetMapping("/{name}/versions/{version:\\d+}/simulation-reports")
    public ResponseEntity<byte[]> list(@PathVariable("name") String name, @PathVariable("version") int version) {
        RestSupport.require(callers, "rules:read");
        ObjectNode body = EngineJson.NODES.objectNode();
        ArrayNode array = body.putArray("reports");
        reports.list(name, version).forEach(report -> array.add(SimulationReportJson.write(report)));
        return RestSupport.json(HttpStatus.OK, body);
    }

    /**
     * {@code GET /rule-sets/{name}/versions/{v}/simulation-reports/{id}}: one report with its rows. Needs
     * {@code rules:read}; the decision ids of replayed rows need {@code rules:log:read}.
     *
     * @param name the rule set name
     * @param version the version
     * @param reportId the report id
     * @return the report
     */
    @GetMapping("/{name}/versions/{version:\\d+}/simulation-reports/{reportId}")
    public ResponseEntity<byte[]> get(@PathVariable("name") String name, @PathVariable("version") int version,
            @PathVariable("reportId") String reportId) {
        RestSupport.require(callers, "rules:read");
        return RestSupport.json(HttpStatus.OK, SimulationReportJson.write(reports.get(name, version, reportId)));
    }
}
