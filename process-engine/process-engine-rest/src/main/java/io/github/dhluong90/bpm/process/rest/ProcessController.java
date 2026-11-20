package io.github.dhluong90.bpm.process.rest;

import io.github.dhluong90.bpm.process.api.DefinitionExport;
import io.github.dhluong90.bpm.process.api.DefinitionService;
import io.github.dhluong90.bpm.process.api.DefinitionSummary;
import io.github.dhluong90.bpm.process.api.InstanceQuery;
import io.github.dhluong90.bpm.process.api.Page;
import io.github.dhluong90.bpm.process.api.PageRequest;
import io.github.dhluong90.bpm.process.api.ProcessQueryService;
import io.github.dhluong90.bpm.process.api.QueryInvalidException;
import io.github.dhluong90.bpm.process.api.DefinitionVersion;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.InputInvalidException;
import io.github.dhluong90.bpm.process.api.PayloadTooLargeException;
import io.github.dhluong90.bpm.process.api.PreconditionRequiredException;
import io.github.dhluong90.bpm.process.api.ProcessEngine;
import io.github.dhluong90.bpm.process.api.NodeCounts;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.ProcessQueryService;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.api.TaskService;
import io.github.dhluong90.bpm.process.api.TaskView;
import io.github.dhluong90.bpm.process.internal.json.EngineJson;
import io.github.dhluong90.bpm.process.internal.service.EngineDefaults;
import io.github.dhluong90.bpm.process.internal.service.ProcessServices;
import io.github.dhluong90.bpm.process.internal.service.Views;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.Set;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

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
    private final ProcessQueryService queries;
    private final int maxDefinitionBytes;

    /**
     * @param definitions definition service
     * @param engine process engine
     * @param tasks task service
     * @param queries list and count queries
     * @param maxDefinitionBytes largest BPMN document accepted ({@code definitions.max-bytes})
     */
    public ProcessController(DefinitionService definitions, ProcessEngine engine, TaskService tasks,
            ProcessQueryService queries, int maxDefinitionBytes) {
        this.maxDefinitionBytes = maxDefinitionBytes;
        this.definitions = definitions;
        this.engine = engine;
        this.tasks = tasks;
        this.queries = queries;
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

    /** Lists the definition keys of the tenant ({@code processes:read}). */
    @GetMapping("/definitions")
    public ResponseEntity<byte[]> definitions(@RequestParam(value = "limit", required = false) String limit,
            @RequestParam(value = "cursor", required = false) String cursor) {
        Page<DefinitionSummary> page = queries.definitions(new PageRequest(limitOf(limit), cursor));
        return json(HttpStatus.OK, Views.page(page, Views::definitionSummary), null, null);
    }

    /** Reads one version of a definition ({@code processes:read}); the {@code ETag} is its definition hash. */
    @GetMapping("/definitions/{key}/versions/{version}")
    public ResponseEntity<byte[]> definitionVersion(@PathVariable("key") String key,
            @PathVariable("version") String version) {
        int number;
        try {
            number = Integer.parseInt(version);
        } catch (NumberFormatException e) {
            throw new InputInvalidException("version must be a version number, for example 1");
        }
        DefinitionVersion found = definitions.version(key, number);
        return ResponseEntity.status(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ETAG, "\"" + found.definitionHash() + "\"")
                .body(bytes(Views.definition(found)));
    }

    /**
     * Lists instances, newest first ({@code processes:read}). {@code status} is one or more statuses, comma
     * separated, {@code RUNNING} when absent.
     */
    @GetMapping("/instances")
    public ResponseEntity<byte[]> instances(@RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "definitionKey", required = false) String definitionKey,
            @RequestParam(value = "startedFrom", required = false) String startedFrom,
            @RequestParam(value = "startedTo", required = false) String startedTo,
            @RequestParam(value = "limit", required = false) String limit,
            @RequestParam(value = "cursor", required = false) String cursor) {
        Set<String> statuses = new LinkedHashSet<>();
        if (status != null) {
            for (String part : status.split(",", -1)) {
                statuses.add(part.strip());
            }
        }
        InstanceQuery query = new InstanceQuery(statuses, definitionKey, instantOf("startedFrom", startedFrom),
                instantOf("startedTo", startedTo), new PageRequest(limitOf(limit), cursor));
        return json(HttpStatus.OK, Views.page(queries.instances(query), Views::instanceSummary), null, null);
    }

    /**
     * Exports a definition version of the caller's tenant ({@code processes:read}, PRE-034). {@code format=bpmn}
     * returns BPMN 2.0 XML that imports back to the same {@code definitionHash}, which is also the {@code ETag}.
     */
    @GetMapping("/definitions/{key}/versions/{version}/export")
    public ResponseEntity<byte[]> exportDefinition(@PathVariable("key") String key,
            @PathVariable("version") String version, @RequestParam(value = "format", required = false) String format) {
        if (!"bpmn".equals(format)) {
            throw new InputInvalidException("format must be bpmn");
        }
        int number;
        try {
            number = Integer.parseInt(version);
        } catch (NumberFormatException e) {
            throw new InputInvalidException("version must be a positive whole number");
        }
        if (number < 1) {
            throw new InputInvalidException("version must be a positive whole number");
        }
        DefinitionExport export = definitions.exportDefinition(key, number, SourceFormat.BPMN_XML);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/bpmn+xml"))
                .header(HttpHeaders.ETAG, "\"" + export.definitionHash() + "\"")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + export.key() + "-v"
                        + export.version() + ".bpmn\"")
                .body(export.document().getBytes(StandardCharsets.UTF_8));
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
     * Cancels a running instance ({@code processes:cancel}, PD-103). Body: the required {@code reason}. {@code If-Match} carries
     * the instance {@code lockVersion} (its {@code ETag}) and {@code Idempotency-Key} makes a retry safe; both are
     * required ({@code 428 PRECONDITION_REQUIRED}).
     */
    @PostMapping(value = "/instances/{id}:cancel")
    public ResponseEntity<byte[]> cancel(@PathVariable("id") String id, HttpServletRequest http,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new PreconditionRequiredException("Send If-Match with the instance's lockVersion (its ETag), for "
                    + "example \"2\"");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new PreconditionRequiredException("Send an Idempotency-Key so a retry cannot cancel twice");
        }
        byte[] body = boundedBody(http, EngineDefaults.MAX_JSON_BODY_BYTES);
        JsonNode request = body.length == 0 ? EngineJson.NODES.objectNode() : parse(body);
        JsonNode reason = request.get("reason");
        if (reason != null && !reason.isNull() && !reason.isString()) {
            throw new InputInvalidException("reason must be a string");
        }
        ProcessInstance instance = engine.cancel(id, lockVersion(ifMatch),
                reason == null || reason.isNull() ? null : reason.stringValue(), idempotencyKey);
        return json(HttpStatus.OK, Views.instance(instance), instance.lockVersion(), null);
    }

    /**
     * Reads a task with the caller's {@code allowedActions} ({@code processes:task:work}); the {@code ETag} is its
     * lock version.
     */
    @GetMapping("/tasks/{id}")
    public ResponseEntity<byte[]> task(@PathVariable("id") String id) {
        TaskView task = tasks.task(id);
        return json(HttpStatus.OK, Views.task(task), task.lockVersion(), null);
    }

    /**
     * Claims an open task for the caller ({@code processes:task:work}). {@code If-Match} carries the task's
     * {@code lockVersion} and {@code Idempotency-Key} makes a retry safe; both are required.
     */
    @PostMapping("/tasks/{id}:claim")
    public ResponseEntity<byte[]> claim(@PathVariable("id") String id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        requireTaskPreconditions(ifMatch, idempotencyKey, "claim");
        TaskView task = tasks.claim(id, lockVersion(ifMatch), idempotencyKey);
        return json(HttpStatus.OK, Views.task(task), task.lockVersion(), null);
    }

    /** Hands a claimed task back ({@code processes:task:work}); only the claimant may. Same headers as a claim. */
    @PostMapping("/tasks/{id}:unclaim")
    public ResponseEntity<byte[]> unclaim(@PathVariable("id") String id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        requireTaskPreconditions(ifMatch, idempotencyKey, "unclaim");
        TaskView task = tasks.unclaim(id, lockVersion(ifMatch), idempotencyKey);
        return json(HttpStatus.OK, Views.task(task), task.lockVersion(), null);
    }

    private static void requireTaskPreconditions(String ifMatch, String idempotencyKey, String action) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new PreconditionRequiredException("Send If-Match with the task's lockVersion (its ETag), for example "
                    + "\"0\"");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new PreconditionRequiredException("Send an Idempotency-Key so a retry cannot " + action + " twice");
        }
    }

    /**
     * Live tokens per node of one definition version, from the live token index ({@code processes:read}, PRE-028).
     * Only {@code mode=live} in this release; the window mode follows with the hourly rollup.
     */
    @GetMapping("/definitions/{key}/versions/{version}/node-stats")
    public ResponseEntity<byte[]> nodeStats(@PathVariable("key") String key, @PathVariable("version") String version,
            @RequestParam(value = "mode", defaultValue = "live") String mode) {
        if (!"live".equals(mode)) {
            throw new InputInvalidException("mode must be live in this release");
        }
        int number;
        try {
            number = Integer.parseInt(version);
        } catch (NumberFormatException e) {
            throw new InputInvalidException("version must be a positive integer");
        }
        if (number < 1) {
            throw new InputInvalidException("version must be a positive integer");
        }
        NodeCounts counts = queries.liveNodeCounts(key, number);
        ObjectNode body = EngineJson.NODES.objectNode();
        body.put("definitionKey", counts.definitionKey());
        body.put("version", counts.version());
        body.put("mode", "live");
        body.put("asOf", counts.asOf().toString());
        ObjectNode nodes = body.putObject("nodes");
        counts.nodes().forEach((node, byStatus) -> {
            ObjectNode statuses = nodes.putObject(node);
            byStatus.forEach(statuses::put);
        });
        return json(HttpStatus.OK, body, counts.asOf().toEpochMilli(), null);
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

    private static Instant instantOf(String name, String value) {
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.strip()).toInstant();
        } catch (DateTimeParseException e) {
            throw new QueryInvalidException(name + " must be an ISO 8601 time with an offset, for example "
                    + "2027-02-01T00:00:00Z");
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
        return builder.body(bytes(body));
    }

    private static byte[] bytes(JsonNode body) {
        return EngineJson.JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
    }
}
