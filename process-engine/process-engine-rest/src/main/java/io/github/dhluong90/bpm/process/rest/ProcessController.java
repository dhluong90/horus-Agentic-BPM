package io.github.dhluong90.bpm.process.rest;

import io.github.dhluong90.bpm.process.api.AsyncStepService;
import io.github.dhluong90.bpm.process.api.MessageService;
import io.github.dhluong90.bpm.process.api.StepCompletion;
import io.github.dhluong90.bpm.process.spi.AdapterResult;

import io.github.dhluong90.bpm.process.api.DefinitionExport;
import io.github.dhluong90.bpm.process.api.DefinitionHistoryEntry;
import io.github.dhluong90.bpm.process.api.DefinitionRef;
import io.github.dhluong90.bpm.process.api.DefinitionService;
import io.github.dhluong90.bpm.process.api.DefinitionSummary;
import io.github.dhluong90.bpm.process.api.InstanceQuery;
import io.github.dhluong90.bpm.process.api.TaskQuery;
import io.github.dhluong90.bpm.process.api.Page;
import io.github.dhluong90.bpm.process.api.PageRequest;
import io.github.dhluong90.bpm.process.api.ProcessQueryService;
import io.github.dhluong90.bpm.process.api.QueryInvalidException;
import io.github.dhluong90.bpm.process.api.DefinitionVersion;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.InstanceActivities;
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
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
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
        DefinitionVersion requested = definitions.requestApproval(DefinitionRef.of(key, version), lock, effectiveFrom,
                idempotencyKey);
        return json(HttpStatus.OK, withWarnings(Views.definition(requested), requested), requested.lockVersion(), null);
    }

    /**
     * Adds {@code warnings} to a version body (PD-146, #438): one {@code UNCLASSIFIED_FIELD} entry per agent step field
     * that declares no {@code mask}. A reminder only; empty when every field is classified or the reminder is off.
     */
    private tools.jackson.databind.node.ObjectNode withWarnings(tools.jackson.databind.node.ObjectNode body,
            DefinitionVersion v) {
        // Keeps the import warnings of the version (FOREIGN_EXTENSION_IGNORED, PD-141) and appends the reminders
        tools.jackson.databind.node.ArrayNode warnings = body.get("warnings") instanceof
                tools.jackson.databind.node.ArrayNode existing ? existing : body.putArray("warnings");
        for (io.github.dhluong90.bpm.process.api.UnclassifiedField field
                : definitions.unclassifiedFields(DefinitionRef.of(v.key(), v.version()))) {
            warnings.addObject().put("code", io.github.dhluong90.bpm.process.api.UnclassifiedField.CODE)
                    .put("step", field.step()).put("field", field.field()).put("direction", field.direction())
                    .put("message", field.message());
        }
        return body;
    }

    /**
     * Approves a pending version as a second person ({@code processes:approve}). Body: {@code expectedDefinitionHash},
     * the hash of the content reviewed (required while {@code approval.require-content-hash} is on, PRE-177), and an
     * optional {@code comment}.
     */
    @PostMapping("/definitions/{key}/versions/{version}:approve")
    public ResponseEntity<byte[]> approve(@PathVariable("key") String key, @PathVariable("version") int version,
            HttpServletRequest http, @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        long lock = requiredLockVersion(ifMatch);
        JsonNode body = optionalBody(http);
        return version(definitions.approve(DefinitionRef.of(key, version), lock, text(body, "expectedDefinitionHash"),
                text(body, "expectedChangeSetHash"), text(body, "comment"), idempotencyKey));
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

    /** Lists the definition keys of the tenant ({@code processes:read}). */
    @GetMapping("/definitions")
    public ResponseEntity<byte[]> definitions(@RequestParam(value = "limit", required = false) String limit,
            @RequestParam(value = "cursor", required = false) String cursor) {
        Page<DefinitionSummary> page = queries.definitions(new PageRequest(limitOf(limit), cursor));
        return json(HttpStatus.OK, Views.page(page, Views::definitionSummary), null, null);
    }

    /**
     * Lists the definition versions waiting for a second person, oldest request first ({@code processes:read}, #31,
     * PRE-190). Each item has the {@code definitionHash} a reviewer sends back with the approval and the caller's
     * {@code allowedActions}; never the definition.
     */
    @GetMapping("/approvals")
    public ResponseEntity<byte[]> approvals(@RequestParam(value = "limit", required = false) String limit,
            @RequestParam(value = "cursor", required = false) String cursor) {
        return json(HttpStatus.OK, Views.page(queries.pendingApprovals(new PageRequest(limitOf(limit), cursor)),
                Views::pendingApproval), null, null);
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
                .body(bytes(withWarnings(Views.definition(found), found)));
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
     * Lists tasks by assignment, status, role, group, due date and missed deadline ({@code processes:task:work};
     * {@code assignment=any} needs {@code processes:task:manage}; PRE-030). Ordered by {@code dueAt}, then
     * {@code id}; tasks without a due time follow. Items carry no variables and no review context.
     */
    @GetMapping("/tasks")
    public ResponseEntity<byte[]> taskList(@RequestParam(value = "assignment", required = false) String assignment,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "role", required = false) String role,
            @RequestParam(value = "group", required = false) String group,
            @RequestParam(value = "dueBefore", required = false) String dueBefore,
            @RequestParam(value = "slaBreached", required = false) String slaBreached,
            @RequestParam(value = "limit", required = false) String limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        boolean breached;
        if (slaBreached == null || slaBreached.strip().equals("false")) {
            breached = false;
        } else if (slaBreached.strip().equals("true")) {
            breached = true;
        } else {
            throw new QueryInvalidException("slaBreached must be true or false");
        }
        TaskQuery query = new TaskQuery(csv(assignment), csv(status), role, group, instantOf("dueBefore", dueBefore),
                breached, new PageRequest(limitOf(limit), cursor));
        return conditional(Views.page(queries.tasks(query), Views::taskSummary), ifNoneMatch);
    }

    private static Set<String> csv(String value) {
        Set<String> parts = new LinkedHashSet<>();
        if (value != null) {
            for (String part : value.split(",", -1)) {
                parts.add(part.strip());
            }
        }
        return parts;
    }

    /**
     * Compares two stored versions of one key ({@code processes:read}, PRE-025, PRE-176): steps and links added,
     * removed and changed, by BPMN id and name, never layout or XML. {@code base} defaults to the effective version.
     * The {@code ETag} is derived from both definition hashes, so {@code If-None-Match} with it answers {@code 304}.
     */
    @GetMapping("/definitions/{key}/versions/{version}/diff")
    public ResponseEntity<byte[]> diff(@PathVariable("key") String key, @PathVariable("version") String version,
            @RequestParam(value = "base", required = false) String base,
            @RequestParam(value = "limit", required = false) String limit,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        int target = versionNumber("version", version);
        if (limit != null || cursor != null) {
            return pagedDiff(key, target, base, limitOf(limit), cursor, ifNoneMatch);
        }
        io.github.dhluong90.bpm.process.api.GraphDiff diff = base == null
                ? definitions.diffFromEffective(DefinitionRef.of(key, target))
                : definitions.diff(DefinitionRef.of(key, versionNumber("base", base)), DefinitionRef.of(key, target));
        String etag = "\"" + io.github.dhluong90.bpm.process.internal.json.Hashes.sha256Parts("graph-diff.v1",
                diff.base().definitionHash(), diff.target().definitionHash()) + "\"";
        if (etag.equals(ifNoneMatch == null ? null : ifNoneMatch.strip())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).header(HttpHeaders.ETAG, etag).build();
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.ETAG, etag)
                .body(bytes(graphDiff(diff)));
    }

    /** The paged read of the complete change set (PRE-199, #426); the unpaged read above keeps its 1,000-value cap. */
    private ResponseEntity<byte[]> pagedDiff(String key, int target, String base, Integer limit, String cursor,
            String ifNoneMatch) {
        io.github.dhluong90.bpm.process.api.GraphDiffPage page = base == null
                ? definitions.diffPageFromEffective(DefinitionRef.of(key, target), limit, cursor)
                : definitions.diffPage(DefinitionRef.of(key, versionNumber("base", base)),
                        DefinitionRef.of(key, target), limit, cursor);
        io.github.dhluong90.bpm.process.api.GraphDiff d = page.slice();
        String etag = "\"" + io.github.dhluong90.bpm.process.internal.json.Hashes.sha256Parts("graph-diff-page.v1",
                d.base().definitionHash(), d.target().definitionHash(), Integer.toString(page.offset()),
                limit == null ? "" : limit.toString()) + "\"";
        if (etag.equals(ifNoneMatch == null ? null : ifNoneMatch.strip())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).header(HttpHeaders.ETAG, etag).build();
        }
        // The hashes of both sides are named on the last page only: an approver reads every page to get them.
        ObjectNode node = graphDiff(d, page.last());
        node.put("identical", page.total() == 0);
        node.remove("approvableHash");
        putNullable(node, "approvableHash", page.approvableHash());
        putNullable(node, "changeSetHash", page.changeSetHash());
        ObjectNode paging = node.putObject("page");
        paging.put("offset", page.offset());
        paging.put("total", page.total());
        putNullable(paging, "nextCursor", page.nextCursor());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.ETAG, etag)
                .body(bytes(node));
    }

    private static int versionNumber(String name, String value) {
        int number;
        try {
            number = Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw new InputInvalidException(name + " must be a version number, for example 1");
        }
        if (number < 1) {
            throw new InputInvalidException(name + " must be a version number, for example 1");
        }
        return number;
    }

    static ObjectNode graphDiff(io.github.dhluong90.bpm.process.api.GraphDiff d) {
        return graphDiff(d, !d.truncated());
    }

    private static ObjectNode graphDiff(io.github.dhluong90.bpm.process.api.GraphDiff d, boolean withHashes) {
        ObjectNode node = EngineJson.NODES.objectNode();
        node.put("definitionKey", d.definitionKey());
        // PRE-177 (PD-139): a cut-off change list carries no hash that may be approved, on either side.
        side(node.putObject("base"), d.base(), withHashes);
        side(node.putObject("target"), d.target(), withHashes);
        putNullable(node, "approvableHash", d.approvableHash());
        node.put("identical", d.identical());
        tools.jackson.databind.node.ArrayNode added = node.putArray("nodesAdded");
        d.nodesAdded().forEach(n -> step(added.addObject(), n));
        tools.jackson.databind.node.ArrayNode removed = node.putArray("nodesRemoved");
        d.nodesRemoved().forEach(n -> step(removed.addObject(), n));
        tools.jackson.databind.node.ArrayNode changed = node.putArray("nodesChanged");
        for (io.github.dhluong90.bpm.process.api.GraphDiff.NodeChange c : d.nodesChanged()) {
            ObjectNode change = changed.addObject();
            change.put("id", c.id());
            putNullable(change, "name", c.name());
            putNullable(change, "type", c.type());
            change.put("path", c.path());
            change.set("from", io.github.dhluong90.bpm.process.internal.json.Values.toJson(c.from()));
            change.set("to", io.github.dhluong90.bpm.process.internal.json.Values.toJson(c.to()));
        }
        tools.jackson.databind.node.ArrayNode edgesAdded = node.putArray("edgesAdded");
        d.edgesAdded().forEach(e -> edge(edgesAdded.addObject(), e));
        tools.jackson.databind.node.ArrayNode edgesRemoved = node.putArray("edgesRemoved");
        d.edgesRemoved().forEach(e -> edge(edgesRemoved.addObject(), e));
        node.put("truncated", d.truncated());
        return node;
    }

    private static void side(ObjectNode node, io.github.dhluong90.bpm.process.api.GraphDiff.Side side,
            boolean withHash) {
        node.put("version", side.version());
        node.put("status", side.status());
        if (withHash) {
            node.put("definitionHash", side.definitionHash());
        }
    }

    private static void step(ObjectNode node, io.github.dhluong90.bpm.process.api.GraphDiff.Node step) {
        node.put("id", step.id());
        putNullable(node, "name", step.name());
        node.put("type", step.type());
    }

    private static void edge(ObjectNode node, io.github.dhluong90.bpm.process.api.GraphDiff.Edge edge) {
        putNullable(node, "id", edge.id());
        node.put("from", edge.from());
        putNullable(node, "fromName", edge.fromName());
        node.put("to", edge.to());
        putNullable(node, "toName", edge.toName());
        node.put("kind", edge.kind());
    }

    private static void putIfPresent(ObjectNode node, String name, String value) {
        if (value != null) {
            node.put(name, value);
        }
    }

    private static void putNullable(ObjectNode node, String name, String value) {
        if (value == null) {
            node.putNull(name);
        } else {
            node.put(name, value);
        }
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
     * What happened at each step of one instance and its history entries in time order ({@code processes:read},
     * PRE-027), read only from the history partitions of the instance's lifetime. The {@code ETag} is a hash of the
     * body, so a matching {@code If-None-Match} gets {@code 304} with no body.
     */
    @GetMapping("/instances/{id}/activities")
    public ResponseEntity<byte[]> activities(@PathVariable("id") String id,
            @RequestParam(value = "limit", required = false) String limit,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {
        int size = ACTIVITY_LIMIT;
        if (limit != null) {
            try {
                size = Integer.parseInt(limit.strip());
            } catch (NumberFormatException e) {
                throw new QueryInvalidException("limit must be a whole number");
            }
        }
        InstanceActivities activities = queries.activities(id, size);
        ObjectNode body = EngineJson.NODES.objectNode();
        body.put("instanceId", activities.instanceId());
        body.put("startedAt", activities.startedAt().toString());
        if (activities.endedAt() == null) {
            body.putNull("endedAt");
        } else {
            body.put("endedAt", activities.endedAt().toString());
        }
        tools.jackson.databind.node.ArrayNode nodes = body.putArray("nodes");
        for (InstanceActivities.Node node : activities.nodes()) {
            ObjectNode item = nodes.addObject();
            item.put("nodeId", node.nodeId());
            item.put("state", node.state().name());
            putTime(item, "enteredAt", node.enteredAt());
            putTime(item, "completedAt", node.completedAt());
            if (node.duration() == null) {
                item.putNull("durationMs");
            } else {
                item.put("durationMs", node.duration().toMillis());
            }
            item.put("attempts", node.attempts());
            if (node.actor() == null) {
                item.putNull("actor");
            } else {
                item.put("actor", node.actor());
            }
            if (node.incidentCode() == null) {
                item.putNull("incident");
            } else {
                ObjectNode incident = item.putObject("incident");
                incident.put("code", node.incidentCode());
                putTime(incident, "openedAt", node.incidentAt());
            }
        }
        tools.jackson.databind.node.ArrayNode entries = body.putArray("entries");
        for (InstanceActivities.Entry entry : activities.entries()) {
            ObjectNode item = entries.addObject();
            item.put("id", entry.id());
            item.put("type", entry.type());
            if (entry.nodeId() == null) {
                item.putNull("nodeId");
            } else {
                item.put("nodeId", entry.nodeId());
            }
            if (entry.actor() == null) {
                item.putNull("actor");
            } else {
                item.put("actor", entry.actor());
            }
            item.put("occurredAt", entry.occurredAt().toString());
            // #80: the kind and, for an undo, what it undoes and in which scope, so the cockpit never infers them.
            item.put("kind", entry.kind());
            putIfPresent(item, "compensates", entry.compensates());
            putIfPresent(item, "attemptId", entry.attemptId());
            putIfPresent(item, "scopeTokenId", entry.scopeTokenId());
            putIfPresent(item, "scope", entry.scope());
            if (entry.attempt() != null) {
                item.put("attempt", entry.attempt());
            }
            putIfPresent(item, "errorCode", entry.errorCode());
            putIfPresent(item, "handler", entry.handler());
            putIfPresent(item, "cause", entry.cause());
            if (entry.message() != null) {
                ObjectNode message = item.putObject("message");
                entry.message().forEach(message::put);
            }
        }
        tools.jackson.databind.node.ArrayNode compensations = body.putArray("compensations");
        for (InstanceActivities.Compensation c : activities.compensations()) {
            ObjectNode item = compensations.addObject();
            item.put("scopeTokenId", c.scopeTokenId());
            putNullable(item, "scope", c.scope());
            item.put("state", c.state().name());
            item.put("startedAt", c.startedAt().toString());
            putTime(item, "endedAt", c.endedAt());
            putNullable(item, "cause", c.cause());
            putNullable(item, "failingStep", c.failingStep());
            item.put("stepsUndone", c.stepsUndone());
            putNullable(item, "blockedStep", c.blockedStep());
            putNullable(item, "incidentId", c.incidentId());
            putNullable(item, "incidentCode", c.incidentCode());
        }
        body.put("hasMore", activities.hasMore());
        byte[] bytes = bytes(body);
        String etag = "\"" + contentHash(bytes) + "\"";
        if (ifNoneMatch != null && matches(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).header(HttpHeaders.ETAG, etag).build();
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.ETAG, etag).body(bytes);
    }

    /** Default number of history entries of {@link #activities}; {@code ?limit=} may ask for 1 to the page limit. */
    static final int ACTIVITY_LIMIT = 200;

    private static void putTime(ObjectNode node, String name, Instant time) {
        if (time == null) {
            node.putNull(name);
        } else {
            node.put(name, time.toString());
        }
    }

    /**
     * Answers a list body with an {@code ETag} that is a hash of the body, or {@code 304} when {@code If-None-Match}
     * names it. The body is what the caller's own tenant, permissions and page produced, so the tag changes with the
     * content, the page and the caller's view, and says nothing about any other tenant.
     */
    static ResponseEntity<byte[]> conditional(JsonNode body, String ifNoneMatch) {
        byte[] bytes = bytes(body);
        String etag = "\"" + contentHash(bytes) + "\"";
        if (ifNoneMatch != null && matches(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).header(HttpHeaders.ETAG, etag).build();
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.ETAG, etag).body(bytes);
    }

    private static String contentHash(byte[] bytes) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            return java.util.HexFormat.of().formatHex(digest, 0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java runtime", e);
        }
    }

    private static boolean matches(String ifNoneMatch, String etag) {
        for (String candidate : ifNoneMatch.split(",")) {
            String value = candidate.strip();
            if (value.startsWith("W/")) {
                value = value.substring(2);
            }
            if (value.equals("*") || value.equals(etag)) {
                return true;
            }
        }
        return false;
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
     * Completes an attempt of a remote step whose adapter handed the call off ({@code processes:step:complete}),
     * for example a credit bureau's callback. The caller also needs a completer role of the step's adapter
     * ({@code adapters.completer-roles}). Body: {@code attemptId}, {@code completionToken}, {@code status}
     * ({@code COMPLETED}, {@code FAILED} or {@code BUSINESS_ERROR}), and {@code output} for a completed attempt or
     * {@code errorCode} (and {@code retryable} for a failure). A repeat answers {@code DUPLICATE} and writes nothing;
     * a result after the lease ended answers {@code DISCARDED} (ADR-0008); a result its output-schema or guardrail
     * check refuses answers {@code REFUSED} (#363).
     */
    @PostMapping(value = "/async-steps/{jobId}:complete", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> completeAsyncStep(@PathVariable("jobId") String jobId, HttpServletRequest http)
            throws IOException {
        if (!(engine instanceof AsyncStepService steps)) {
            throw new InputInvalidException("This engine does not run remote steps");
        }
        JsonNode request = parse(boundedBody(http, EngineDefaults.MAX_JSON_BODY_BYTES));
        String attemptId = text(request, "attemptId");
        String completionToken = text(request, "completionToken");
        String status = text(request, "status");
        if (attemptId == null || completionToken == null || status == null) {
            throw new InputInvalidException("attemptId, completionToken and status are required");
        }
        AdapterResult result = switch (status) {
            case "COMPLETED" -> AdapterResult.completed(Views.variables(request.get("output")));
            case "FAILED", "BUSINESS_ERROR" -> {
                String code = text(request, "errorCode");
                if (code == null || code.isBlank() || code.length() > 100) {
                    throw new InputInvalidException("errorCode is required for " + status + ", at most 100 characters");
                }
                JsonNode retryable = request.get("retryable");
                yield "FAILED".equals(status)
                        ? new AdapterResult.Failed(code, "reported by the completion API",
                                retryable == null || retryable.asBoolean(true))
                        : new AdapterResult.BusinessError(code, "reported by the completion API");
            }
            default -> throw new InputInvalidException("status must be COMPLETED, FAILED or BUSINESS_ERROR");
        };
        StepCompletion completion = steps.complete(jobId, attemptId, completionToken, result);
        tools.jackson.databind.node.ObjectNode body = EngineJson.NODES.objectNode();
        body.put("jobId", completion.jobId());
        body.put("attemptId", completion.attemptId());
        body.put("outcome", completion.outcome().name());
        return json(HttpStatus.OK, body, null, null);
    }

    /**
     * Delivers a message to the instance that waits for it, or starts the process whose message start event has its
     * name ({@code processes:message:correlate}, #491). Body: {@code messageName}, {@code correlationKey} (optional
     * only for a message that starts a process) and {@code payload}. {@code Idempotency-Key} is required; a replay
     * returns the first answer. {@code 422 MESSAGE_NOT_CORRELATED} when nothing waits and nothing starts, and
     * {@code 409 MESSAGE_CORRELATION_AMBIGUOUS} when two instances match: in both nothing is stored or run.
     */
    @PostMapping(value = "/messages:correlate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> correlateMessage(HttpServletRequest http,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        if (!(engine instanceof MessageService messages)) {
            throw new InputInvalidException("This engine does not correlate messages");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new PreconditionRequiredException("Send an Idempotency-Key so a retry cannot deliver twice");
        }
        JsonNode request = parse(boundedBody(http, EngineDefaults.MAX_JSON_BODY_BYTES));
        String name = text(request, "messageName");
        if (name == null) {
            throw new InputInvalidException("messageName is required");
        }
        JsonNode payload = request.get("payload");
        if (payload != null && !payload.isNull() && !payload.isObject()) {
            throw new InputInvalidException("payload must be an object");
        }
        MessageService.MessageDelivery delivery = messages.correlate(name, text(request, "correlationKey"),
                Views.variables(payload), idempotencyKey);
        tools.jackson.databind.node.ObjectNode body = EngineJson.NODES.objectNode();
        body.put("outcome", delivery.outcome().name());
        body.put("instanceId", delivery.instanceId());
        if (delivery.nodeId() != null) {
            body.put("nodeId", delivery.nodeId());
        }
        return json(HttpStatus.OK, body, null, null);
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

    /**
     * Assigns an open task to a named user ({@code processes:task:manage}, PD-110). Body: {@code assignee} and the
     * required {@code reason}. {@code If-Match} carries the task's {@code lockVersion} and {@code Idempotency-Key}
     * makes a retry safe; both are required.
     */
    @PostMapping("/tasks/{id}:reassign")
    public ResponseEntity<byte[]> reassign(@PathVariable("id") String id, HttpServletRequest http,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        requireTaskPreconditions(ifMatch, idempotencyKey, "reassign");
        JsonNode request = handOverBody(http);
        TaskView task = tasks.reassign(id, text(request, "assignee"), text(request, "reason"), lockVersion(ifMatch),
                idempotencyKey);
        return json(HttpStatus.OK, Views.task(task), task.lockVersion(), null);
    }

    /** Hands a claimed task to another user ({@code processes:task:manage}, PD-110). Same body and headers as a reassign. */
    @PostMapping("/tasks/{id}:delegate")
    public ResponseEntity<byte[]> delegate(@PathVariable("id") String id, HttpServletRequest http,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        requireTaskPreconditions(ifMatch, idempotencyKey, "delegate");
        JsonNode request = handOverBody(http);
        TaskView task = tasks.delegate(id, text(request, "assignee"), text(request, "reason"), lockVersion(ifMatch),
                idempotencyKey);
        return json(HttpStatus.OK, Views.task(task), task.lockVersion(), null);
    }

    private static JsonNode handOverBody(HttpServletRequest http) throws IOException {
        byte[] body = boundedBody(http, EngineDefaults.MAX_JSON_BODY_BYTES);
        if (body.length == 0) {
            throw new InputInvalidException("Send a JSON body with assignee and reason");
        }
        return parse(body);
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
     * Per node of one definition version: {@code mode=live} the live tokens from the live token index (PRE-028),
     * {@code mode=window} the counts and typical durations of whole hours from the hourly rollup (PRE-029). Needs
     * {@code processes:read}; both read the caller's tenant only.
     */
    @GetMapping("/definitions/{key}/versions/{version}/node-stats")
    public ResponseEntity<byte[]> nodeStats(@PathVariable("key") String key, @PathVariable("version") String version,
            @RequestParam(value = "mode", defaultValue = "live") String mode,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to) {
        if (!"live".equals(mode) && !"window".equals(mode)) {
            throw new InputInvalidException("mode must be live or window");
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
        if ("window".equals(mode)) {
            return windowStats(key, number, instant("from", from), instant("to", to));
        }
        if (from != null || to != null) {
            throw new InputInvalidException("from and to apply to mode=window only");
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

    private ResponseEntity<byte[]> windowStats(String key, int version, java.time.Instant from, java.time.Instant to) {
        io.github.dhluong90.bpm.process.api.NodeWindowStats stats = queries.nodeWindowStats(key, version, from, to);
        ObjectNode body = EngineJson.NODES.objectNode();
        body.put("definitionKey", stats.definitionKey());
        body.put("version", stats.version());
        body.put("mode", "window");
        body.put("from", stats.from().toString());
        body.put("to", stats.to().toString());
        if (stats.asOf() == null) {
            body.putNull("asOf");
            body.putNull("lagSeconds");
        } else {
            body.put("asOf", stats.asOf().toString());
            body.put("lagSeconds", stats.lag().toSeconds());
        }
        ObjectNode nodes = body.putObject("nodes");
        stats.nodes().forEach((node, figures) -> {
            ObjectNode n = nodes.putObject(node);
            n.put("entered", figures.entered());
            n.put("completed", figures.completed());
            n.put("failed", figures.failed());
            n.put("incidents", figures.incidents());
            n.put("slaBreaches", figures.slaBreaches());
            putOrNull(n, "meanMs", figures.meanMs());
            putOrNull(n, "p50Ms", figures.p50Ms());
            putOrNull(n, "p95Ms", figures.p95Ms());
        });
        // The answer changes as the rollup advances, so it carries no ETag.
        return json(HttpStatus.OK, body, null, null);
    }

    private static void putOrNull(ObjectNode node, String name, Long value) {
        if (value == null) {
            node.putNull(name);
        } else {
            node.put(name, value);
        }
    }

    private static java.time.Instant instant(String name, String value) {
        if (value == null) {
            return null;
        }
        try {
            return java.time.Instant.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            throw new InputInvalidException(name + " must be a UTC time such as 2026-11-20T10:00:00Z");
        }
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
        JsonNode reason = request.get("reason");
        if (reason != null && !reason.isNull() && !reason.isString()) {
            throw new InputInvalidException("reason must be a string");
        }
        // The reason of a reviewer's answer to a refused agent answer, recorded with the override (#59).
        ProcessInstance instance = reason == null || reason.isNull()
                ? tasks.complete(id, result, lockVersion(ifMatch), idempotencyKey)
                : tasks.complete(id, result, reason.stringValue(), lockVersion(ifMatch), idempotencyKey);
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
