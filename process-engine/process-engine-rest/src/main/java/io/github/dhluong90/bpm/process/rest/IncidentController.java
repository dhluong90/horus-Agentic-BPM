package io.github.dhluong90.bpm.process.rest;

import io.github.dhluong90.bpm.process.api.Incident;
import io.github.dhluong90.bpm.process.api.IncidentQuery;
import io.github.dhluong90.bpm.process.api.IncidentService;
import io.github.dhluong90.bpm.process.api.InputInvalidException;
import io.github.dhluong90.bpm.process.api.PageRequest;
import io.github.dhluong90.bpm.process.api.PreconditionRequiredException;
import io.github.dhluong90.bpm.process.api.QueryInvalidException;
import io.github.dhluong90.bpm.process.internal.json.EngineJson;
import io.github.dhluong90.bpm.process.internal.service.EngineDefaults;
import io.github.dhluong90.bpm.process.internal.service.Views;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Incident endpoints under {@code /api/bpm/processes/v1/incidents} (#68): list, read, retry, and resolve with four
 * eyes (PD-126). Permissions, tenant and caller are checked in {@link IncidentService}, so the Java API and REST
 * behave the same; a tenant in a body is never read. Every command needs {@code If-Match} with the incident's
 * {@code lockVersion} and an {@code Idempotency-Key} ({@code 428} without either).
 */
@RestController
@RequestMapping(ProcessController.BASE)
public class IncidentController {

    private final IncidentService incidents;

    /** @param incidents the incident service */
    public IncidentController(IncidentService incidents) {
        this.incidents = Objects.requireNonNull(incidents, "incidents must not be null");
    }

    /** Lists incidents of one status, newest first ({@code processes:read}, PRE-080). */
    @GetMapping("/incidents")
    public ResponseEntity<byte[]> list(@RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", required = false) String limit,
            @RequestParam(value = "cursor", required = false) String cursor) {
        IncidentQuery query = new IncidentQuery(status == null ? null : status.strip(),
                new PageRequest(limitOf(limit), cursor));
        return json(HttpStatus.OK, Views.page(incidents.incidents(query), Views::incident), null);
    }

    /** Reads one incident ({@code processes:read}); the {@code ETag} is its lock version. */
    @GetMapping("/incidents/{id}")
    public ResponseEntity<byte[]> read(@PathVariable("id") String id) {
        Incident incident = incidents.incident(id);
        return json(HttpStatus.OK, Views.incident(incident), incident.lockVersion());
    }

    /** Runs the failed step again ({@code processes:incident:manage}, PRE-077). Body: {@code reason}, required. */
    @PostMapping("/incidents/{id}:retry")
    public ResponseEntity<byte[]> retry(@PathVariable("id") String id, HttpServletRequest http,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        long lock = preconditions(ifMatch, idempotencyKey);
        JsonNode body = body(http);
        Incident incident = incidents.retry(id, lock, text(body, "reason"), idempotencyKey);
        return json(HttpStatus.ACCEPTED, Views.incident(incident), incident.lockVersion());
    }

    /**
     * Proposes a resolution ({@code processes:incident:manage}, PRE-078). Body: {@code output}, the step's result as a
     * JSON object, and {@code reason}, both required. Under four eyes the incident waits for a second person.
     */
    @PostMapping("/incidents/{id}:resolve")
    public ResponseEntity<byte[]> resolve(@PathVariable("id") String id, HttpServletRequest http,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        long lock = preconditions(ifMatch, idempotencyKey);
        JsonNode body = body(http);
        JsonNode output = body.get("output");
        if (output == null || output.isNull()) {
            throw new InputInvalidException("output is required: the result the waiting step completes with");
        }
        if (!output.isObject()) {
            throw new InputInvalidException("output must be a JSON object");
        }
        Map<String, Object> values = Views.variables(output);
        Incident incident = incidents.resolve(id, lock, values, text(body, "reason"), idempotencyKey);
        return json(HttpStatus.OK, Views.incident(incident), incident.lockVersion());
    }

    /** Approves a proposed resolution as a second person ({@code processes:incident:manage}). Body: {@code comment}. */
    @PostMapping("/incidents/{id}:approve-resolution")
    public ResponseEntity<byte[]> approveResolution(@PathVariable("id") String id, HttpServletRequest http,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        long lock = preconditions(ifMatch, idempotencyKey);
        Incident incident = incidents.approveResolution(id, lock, text(body(http), "comment"), idempotencyKey);
        return json(HttpStatus.OK, Views.incident(incident), incident.lockVersion());
    }

    /** Rejects a proposed resolution as a second person ({@code processes:incident:manage}). Body: {@code reason}. */
    @PostMapping("/incidents/{id}:reject-resolution")
    public ResponseEntity<byte[]> rejectResolution(@PathVariable("id") String id, HttpServletRequest http,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        long lock = preconditions(ifMatch, idempotencyKey);
        Incident incident = incidents.rejectResolution(id, lock, text(body(http), "reason"), idempotencyKey);
        return json(HttpStatus.OK, Views.incident(incident), incident.lockVersion());
    }

    private static long preconditions(String ifMatch, String idempotencyKey) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new PreconditionRequiredException("Send If-Match with the incident's lockVersion (its ETag), for "
                    + "example \"0\"");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new PreconditionRequiredException("Send an Idempotency-Key so a repeated request changes nothing");
        }
        String value = ifMatch.strip();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        try {
            return Long.parseLong(value.replace("\"", ""));
        } catch (NumberFormatException e) {
            throw new InputInvalidException("If-Match must be a quoted lock version, for example \"0\"");
        }
    }

    private static JsonNode body(HttpServletRequest http) throws IOException {
        byte[] bytes = ProcessController.boundedBody(http, EngineDefaults.MAX_JSON_BODY_BYTES);
        if (bytes.length == 0) {
            return EngineJson.NODES.objectNode();
        }
        try {
            JsonNode node = EngineJson.JSON.readTree(bytes);
            if (node == null || !node.isObject()) {
                throw new InputInvalidException("The body must be a JSON object");
            }
            return node;
        } catch (JacksonException e) {
            throw new InputInvalidException("The body is not valid JSON");
        }
    }

    private static String text(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isString()) {
            throw new InputInvalidException(field + " must be a string");
        }
        return value.stringValue();
    }

    private static Integer limitOf(String limit) {
        if (limit == null) {
            return null;
        }
        try {
            return Integer.valueOf(limit.strip());
        } catch (NumberFormatException e) {
            throw new QueryInvalidException("limit must be a whole number, for example 50");
        }
    }

    private static ResponseEntity<byte[]> json(HttpStatus status, JsonNode body, Long etag) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON);
        if (etag != null) {
            builder.header(HttpHeaders.ETAG, "\"" + etag + "\"");
        }
        return builder.body(EngineJson.JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
    }
}
