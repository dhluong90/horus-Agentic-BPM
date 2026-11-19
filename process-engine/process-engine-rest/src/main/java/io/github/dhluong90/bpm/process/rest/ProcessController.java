package io.github.dhluong90.bpm.process.rest;

import io.github.dhluong90.bpm.process.api.DefinitionService;
import io.github.dhluong90.bpm.process.api.DefinitionVersion;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.InputInvalidException;
import io.github.dhluong90.bpm.process.api.ProcessEngine;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.api.TaskService;
import io.github.dhluong90.bpm.process.internal.json.EngineJson;
import io.github.dhluong90.bpm.process.internal.service.Views;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    /**
     * @param definitions definition service
     * @param engine process engine
     * @param tasks task service
     */
    public ProcessController(DefinitionService definitions, ProcessEngine engine, TaskService tasks) {
        this.definitions = definitions;
        this.engine = engine;
        this.tasks = tasks;
    }

    /** Imports a BPMN 2.0 XML document ({@code processes:deploy}). */
    @PostMapping(value = "/definitions", consumes = {"application/xml", "text/xml", "application/bpmn+xml"})
    public ResponseEntity<byte[]> importDefinition(@RequestBody byte[] body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        DefinitionVersion version = definitions.importDefinition(
                new ImportRequest(SourceFormat.BPMN_XML, body, idempotencyKey));
        return json(HttpStatus.CREATED, Views.definition(version), null,
                BASE + "/definitions/" + version.key() + "/versions/" + version.version());
    }

    /** Starts an instance ({@code processes:start}). Body: {@code definitionKey}, {@code businessKey}, {@code variables}. */
    @PostMapping(value = "/instances", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> start(@RequestBody byte[] body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        JsonNode request = parse(body);
        JsonNode key = request.get("definitionKey");
        if (key == null || !key.isString()) {
            throw new InputInvalidException("definitionKey is required");
        }
        JsonNode businessKey = request.get("businessKey");
        Map<String, Object> variables = Views.variables(request.get("variables"));
        ProcessInstance instance = engine.start(new StartRequest(key.stringValue(),
                businessKey == null || businessKey.isNull() ? null : businessKey.stringValue(), variables, idempotencyKey));
        return json(HttpStatus.CREATED, Views.instance(instance), instance.lockVersion(),
                BASE + "/instances/" + instance.id());
    }

    /** Reads an instance with its tokens and tasks ({@code processes:read}). */
    @GetMapping("/instances/{id}")
    public ResponseEntity<byte[]> instance(@PathVariable("id") String id) {
        ProcessInstance instance = engine.instance(id);
        return json(HttpStatus.OK, Views.instance(instance), instance.lockVersion(), null);
    }

    /** Completes a user task ({@code processes:task:work}). Body: {@code result}. {@code If-Match} carries the task lock version. */
    @PostMapping(value = "/tasks/{id}:complete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> complete(@PathVariable("id") String id, @RequestBody(required = false) byte[] body,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        JsonNode request = body == null || body.length == 0 ? EngineJson.NODES.objectNode() : parse(body);
        Map<String, Object> result = Views.variables(request.get("result"));
        ProcessInstance instance = tasks.complete(id, result, lockVersion(ifMatch), idempotencyKey);
        return json(HttpStatus.OK, Views.instance(instance), instance.lockVersion(), null);
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

    private static Long lockVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return null;
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
