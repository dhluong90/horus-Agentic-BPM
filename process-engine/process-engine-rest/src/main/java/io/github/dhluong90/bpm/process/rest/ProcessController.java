package io.github.dhluong90.bpm.process.rest;

import io.github.dhluong90.bpm.process.api.DefinitionHistoryEntry;
import io.github.dhluong90.bpm.process.api.DefinitionNotFoundException;
import io.github.dhluong90.bpm.process.api.DefinitionRef;
import io.github.dhluong90.bpm.process.api.DefinitionService;
import io.github.dhluong90.bpm.process.api.DefinitionVersion;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.InputInvalidException;
import io.github.dhluong90.bpm.process.api.PayloadTooLargeException;
import io.github.dhluong90.bpm.process.api.PreconditionRequiredException;
import io.github.dhluong90.bpm.process.api.ProcessEngine;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.api.TaskService;
import io.github.dhluong90.bpm.process.internal.json.EngineJson;
import io.github.dhluong90.bpm.process.internal.service.EngineDefaults;
import io.github.dhluong90.bpm.process.internal.service.ProcessServices;
import io.github.dhluong90.bpm.process.internal.service.Views;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Process Engine REST endpoints under {@code /api/bpm/processes/v1}. Bodies are read and written with the
 * engine's own mapper, not the host's message converters (ADR-0021). Permissions, tenant and caller are checked
 * in the services, so the Java API and REST behave the same; a tenant in a body is never read.
 */
@RestController
@RequestMapping(ProcessController.BASE)
public class ProcessController {

    /** Base path. */
    public static final String BASE = "/api/bpm/processes/v1";

    private final DefinitionService definitions;
    private final ProcessEngine engine;
    private final TaskService tasks;
    private final int maxDefinitionBytes;

    /**
     * @param definitions definition service
     * @param engine process engine
     * @param tasks task service
     * @param maxDefinitionBytes largest BPMN document accepted ({@code definitions.max-bytes})
     */
    public ProcessController(DefinitionService definitions, ProcessEngine engine, TaskService tasks,
            int maxDefinitionBytes) {
        this.maxDefinitionBytes = maxDefinitionBytes;
        this.definitions = definitions;
        this.engine = engine;
        this.tasks = tasks;
    }

    /** Imports a BPMN 2.0 XML document ({@code processes:deploy}). */
    @PostMapping(value = "/definitions", consumes = {"application/xml", "text/xml", "application/bpmn+xml"})
    public ResponseEntity<byte[]> importDefinition(HttpServletRequest http,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        byte[] body = boundedBody(http, maxDefinitionBytes);
        DefinitionVersion version = definitions.importDefinition(
                new ImportRequest(SourceFormat.BPMN_XML, body, idempotencyKey));
        return json(HttpStatus.CREATED, Views.definition(version), null,
                BASE + "/definitions/" + version.key() + "/versions/" + version.version());
    }

    /** Reads a definition version ({@code processes:read}); another tenant's version is {@code 404}. */
    @GetMapping("/definitions/{key}/versions/{version}")
    public ResponseEntity<byte[]> definitionVersion(@PathVariable("key") String key, @PathVariable("version") int version) {
        DefinitionVersion v = definitions.find(DefinitionRef.of(key, version))
                .orElseThrow(() -> new DefinitionNotFoundException("No version " + version + " of " + key));
        return json(HttpStatus.OK, Views.definition(v), v.lockVersion(), null);
    }

    /** Lifecycle history of a version, oldest first ({@code processes:read}). */
    @GetMapping("/definitions/{key}/versions/{version}/history")
    public ResponseEntity<byte[]> definitionHistory(@PathVariable("key") String key,
            @PathVariable("version") int version) {
        List<DefinitionHistoryEntry> history = definitions.history(DefinitionRef.of(key, version));
        tools.jackson.databind.node.ObjectNode body = EngineJson.NODES.objectNode();
        body.set("items", Views.definitionHistory(history));
        return json(HttpStatus.OK, body, null, null);
    }

    /**
     * Requests approval of a draft ({@code processes:author} or {@code processes:deploy}). Body, optional:
     * {@code effectiveFrom} (RFC 3339, not in the past). {@code If-Match} is required.
     */
    @PostMapping("/definitions/{key}/versions/{version}:request-approval")
    public ResponseEntity<byte[]> requestApproval(@PathVariable("key") String key, @PathVariable("version") int version,
            HttpServletRequest http, @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        long lock = requiredLockVersion(ifMatch);
        JsonNode body = optionalBody(http);
        Instant effectiveFrom = null;
        String from = text(body, "effectiveFrom");
        if (from != null) {
            try {
                effectiveFrom = OffsetDateTime.parse(from).toInstant();
            } catch (DateTimeException e) {
                throw new InputInvalidException("effectiveFrom must be an RFC 3339 date-time with an offset");
            }
        }
        return version(definitions.requestApproval(DefinitionRef.of(key, version), lock, effectiveFrom, idempotencyKey));
    }

    /** Approves a pending version as a second person ({@code processes:approve}). Body, optional: {@code comment}. */
    @PostMapping("/definitions/{key}/versions/{version}:approve")
    public ResponseEntity<byte[]> approve(@PathVariable("key") String key, @PathVariable("version") int version,
            HttpServletRequest http, @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        long lock = requiredLockVersion(ifMatch);
        return version(definitions.approve(DefinitionRef.of(key, version), lock, text(optionalBody(http), "comment"),
                idempotencyKey));
    }

    /** Sends a pending version back to draft ({@code processes:approve}). Body: {@code reason}, required. */
    @PostMapping("/definitions/{key}/versions/{version}:reject")
    public ResponseEntity<byte[]> reject(@PathVariable("key") String key, @PathVariable("version") int version,
            HttpServletRequest http, @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        long lock = requiredLockVersion(ifMatch);
        return version(definitions.reject(DefinitionRef.of(key, version), lock, text(optionalBody(http), "reason"),
                idempotencyKey));
    }

    /**
     * Retires an active version ({@code processes:approve}) or withdraws a draft ({@code processes:author}). Body:
     * {@code reason} (required for an active version) and {@code confirmNoVersionInEffect} (PD-101).
     */
    @PostMapping("/definitions/{key}/versions/{version}:retire")
    public ResponseEntity<byte[]> retire(@PathVariable("key") String key, @PathVariable("version") int version,
            HttpServletRequest http, @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        long lock = requiredLockVersion(ifMatch);
        JsonNode body = optionalBody(http);
        JsonNode confirm = body.get("confirmNoVersionInEffect");
        if (confirm != null && !confirm.isNull() && !confirm.isBoolean()) {
            throw new InputInvalidException("confirmNoVersionInEffect must be true or false");
        }
        return version(definitions.retire(DefinitionRef.of(key, version), lock, text(body, "reason"),
                confirm != null && confirm.isBoolean() && confirm.booleanValue(), idempotencyKey));
    }

    private ResponseEntity<byte[]> version(DefinitionVersion v) {
        return json(HttpStatus.OK, Views.definition(v), v.lockVersion(), null);
    }

    private static long requiredLockVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new PreconditionRequiredException("Send If-Match with the version's lockVersion, for example \"0\"");
        }
        return lockVersion(ifMatch);
    }

    private static JsonNode optionalBody(HttpServletRequest http) throws IOException {
        byte[] body = boundedBody(http, EngineDefaults.MAX_JSON_BODY_BYTES);
        return body.length == 0 ? EngineJson.NODES.objectNode() : parse(body);
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

    /**
     * Starts an instance ({@code processes:start}). Body: {@code definitionKey}, {@code businessKey}, {@code variables}.
     * A request without a {@code businessKey} needs an {@code Idempotency-Key} ({@code 428 PRECONDITION_REQUIRED}).
     */
    @PostMapping(value = "/instances", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> start(HttpServletRequest http,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        JsonNode request = parse(boundedBody(http, EngineDefaults.MAX_JSON_BODY_BYTES));
        JsonNode key = request.get("definitionKey");
        if (key == null || !key.isString()) {
            throw new InputInvalidException("definitionKey is required");
        }
        JsonNode businessKey = request.get("businessKey");
        boolean hasBusinessKey = businessKey != null && businessKey.isString() && !businessKey.stringValue().isBlank();
        if (!hasBusinessKey && (idempotencyKey == null || idempotencyKey.isBlank())) {
            // Without either, a client retry after a timeout would start a second instance and run its steps twice.
            throw new PreconditionRequiredException("Send an Idempotency-Key, or a businessKey, so a retry cannot start "
                    + "a second instance");
        }
        Map<String, Object> variables = Views.variables(request.get("variables"));
        StartRequest start = new StartRequest(key.stringValue(),
                businessKey == null || businessKey.isNull() ? null : businessKey.stringValue(), variables, idempotencyKey);
        // 201 when this request created the instance, 200 when one with the business key existed (PRE-002).
        boolean created = true;
        ProcessInstance instance;
        if (engine instanceof ProcessServices services) {
            ProcessServices.Started started = services.startDetailed(start);
            instance = started.instance();
            created = started.created();
        } else {
            instance = engine.start(start);
        }
        return json(created ? HttpStatus.CREATED : HttpStatus.OK, Views.instance(instance), instance.lockVersion(),
                BASE + "/instances/" + instance.id());
    }

    /** Reads an instance with its tokens and tasks ({@code processes:read}). */
    @GetMapping("/instances/{id}")
    public ResponseEntity<byte[]> instance(@PathVariable("id") String id) {
        ProcessInstance instance = engine.instance(id);
        return json(HttpStatus.OK, Views.instance(instance), instance.lockVersion(), null);
    }

    /**
     * Completes a user task ({@code processes:task:work}). Body: {@code result}. {@code If-Match} carries the task's
     * {@code lockVersion} (from the task in the instance view, not the instance {@code ETag}) and
     * {@code Idempotency-Key} makes a retry safe; both are required ({@code 428 PRECONDITION_REQUIRED}).
     */
    @PostMapping(value = "/tasks/{id}:complete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> complete(@PathVariable("id") String id, HttpServletRequest http,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new PreconditionRequiredException("Send If-Match with the task's lockVersion, for example \"0\"");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new PreconditionRequiredException("Send an Idempotency-Key so a retry cannot complete the task twice");
        }
        byte[] body = boundedBody(http, EngineDefaults.MAX_JSON_BODY_BYTES);
        JsonNode request = body.length == 0 ? EngineJson.NODES.objectNode() : parse(body);
        Map<String, Object> result = Views.variables(request.get("result"));
        ProcessInstance instance = tasks.complete(id, result, lockVersion(ifMatch), idempotencyKey);
        return json(HttpStatus.OK, Views.instance(instance), instance.lockVersion(), null);
    }

    /**
     * Reads a body without ever holding more than the limit: a larger {@code Content-Length} is refused before
     * reading, otherwise at most one byte more than the limit is read ({@code 413 PAYLOAD_TOO_LARGE}).
     */
    static byte[] boundedBody(HttpServletRequest http, int limit) throws IOException {
        long declared = http.getContentLengthLong();
        if (declared > limit) {
            throw new PayloadTooLargeException("The body is larger than " + limit + " bytes");
        }
        byte[] bytes = http.getInputStream().readNBytes(limit + 1);
        if (bytes.length > limit) {
            throw new PayloadTooLargeException("The body is larger than " + limit + " bytes");
        }
        return bytes;
    }

    private static JsonNode parse(byte[] body) {
        try {
            JsonNode node = EngineJson.JSON.readTree(body);
            if (node == null || !node.isObject()) {
                throw new InputInvalidException("The body must be a JSON object");
            }
            return node;
        } catch (JacksonException e) {
            throw new InputInvalidException("The body is not valid JSON");
        }
    }

    private static long lockVersion(String ifMatch) {
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

    private static ResponseEntity<byte[]> json(HttpStatus status, JsonNode body, Long etag, String location) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON);
        if (etag != null) {
            builder.header(HttpHeaders.ETAG, "\"" + etag + "\"");
        }
        if (location != null) {
            builder.header(HttpHeaders.LOCATION, location);
        }
        return builder.body(EngineJson.JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
    }
}
