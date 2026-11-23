package io.github.dhluong90.bpm.process.rest;

import io.github.dhluong90.bpm.process.api.InputInvalidException;
import io.github.dhluong90.bpm.process.api.MigrationExecution;
import io.github.dhluong90.bpm.process.api.MigrationExecutionService;
import io.github.dhluong90.bpm.process.api.MigrationOutcome;
import io.github.dhluong90.bpm.process.api.PreconditionRequiredException;
import io.github.dhluong90.bpm.process.api.QueryInvalidException;
import io.github.dhluong90.bpm.process.internal.json.EngineJson;
import io.github.dhluong90.bpm.process.internal.service.EngineDefaults;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Executing a migration plan under {@code /api/bpm/processes/v1} (#90, PRE-015, PRE-195 to PRE-198): propose with a
 * reason, approve or reject as a second person, read the progress and the outcome of each instance. The permission,
 * tenant and caller are checked in the service; a tenant in a body is never read.
 */
@RestController
@RequestMapping(ProcessController.BASE)
public class MigrationExecutionController {

    private static final Set<String> REASON_ONLY = Set.of("reason");

    private final MigrationExecutionService executions;

    /** @param executions the execution service */
    public MigrationExecutionController(MigrationExecutionService executions) {
        this.executions = Objects.requireNonNull(executions, "executions must not be null");
    }

    /** Proposes the execution of a plan; {@code 202}, nothing moves until a second person approves. */
    @PostMapping(value = "/migrations/{id}:execute", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> execute(@PathVariable("id") String id, HttpServletRequest http,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        MigrationExecution proposed = executions.propose(id, reason(body(http)), idempotencyKey);
        return json(HttpStatus.ACCEPTED, execution(proposed), proposed.lockVersion());
    }

    /** Approves a proposal as a second person; the runner then moves the instances. */
    @PostMapping("/migrations/{id}:approve")
    public ResponseEntity<byte[]> approve(@PathVariable("id") String id, HttpServletRequest http,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        // Optional body {"expectedPlanHash"}: the plan the approver reviewed (PD-139, #473).
        String expectedPlanHash = null;
        byte[] bytes = ProcessController.boundedBody(http, EngineDefaults.MAX_JSON_BODY_BYTES);
        if (bytes.length > 0) {
            JsonNode body = parse(bytes);
            for (String name : body.propertyNames()) {
                if (!"expectedPlanHash".equals(name)) {
                    throw new InputInvalidException("Unknown field " + (name.length() > 64 ? name.substring(0, 64) : name)
                            + "; the body takes expectedPlanHash only");
                }
            }
            JsonNode hash = body.get("expectedPlanHash");
            if (hash != null && !hash.isNull()) {
                if (!hash.isString()) {
                    throw new InputInvalidException("expectedPlanHash must be a string");
                }
                expectedPlanHash = hash.stringValue();
            }
        }
        long lock = lockVersion(ifMatch);
        MigrationExecution approved = executions.approve(id, lock, idempotencyKey, expectedPlanHash);
        return json(HttpStatus.OK, execution(approved), approved.lockVersion());
    }

    /** Rejects a proposal with a reason. */
    @PostMapping(value = "/migrations/{id}:reject", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> reject(@PathVariable("id") String id, HttpServletRequest http,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        long lock = lockVersion(ifMatch);
        MigrationExecution rejected = executions.reject(id, lock, reason(body(http)), idempotencyKey);
        return json(HttpStatus.OK, execution(rejected), rejected.lockVersion());
    }

    /** The proposal, the approval and the progress. */
    @GetMapping("/migrations/{id}/execution")
    public ResponseEntity<byte[]> progress(@PathVariable("id") String id) {
        MigrationExecution found = executions.execution(id);
        return json(HttpStatus.OK, execution(found), found.lockVersion());
    }

    /** The outcome of each instance, one bounded page. */
    @GetMapping("/migrations/{id}/instances")
    public ResponseEntity<byte[]> instances(@PathVariable("id") String id,
            @RequestParam(value = "outcome", required = false) String outcome,
            @RequestParam(value = "after", required = false) String after,
            @RequestParam(value = "limit", required = false) String limit) {
        int pageSize = 50;
        if (limit != null) {
            try {
                pageSize = Integer.parseInt(limit.strip());
            } catch (NumberFormatException e) {
                throw new QueryInvalidException("limit must be a whole number, for example 50");
            }
        }
        if (pageSize < 1 || pageSize > EngineDefaults.MAX_PAGE_SIZE) {
            throw new QueryInvalidException("limit must be 1 to " + EngineDefaults.MAX_PAGE_SIZE);
        }
        List<MigrationOutcome> found = executions.outcomes(id, outcome == null ? null : outcome.strip(), after,
                pageSize + 1);
        ObjectNode body = EngineJson.NODES.objectNode();
        ArrayNode items = body.putArray("items");
        found.stream().limit(pageSize).forEach(o -> {
            ObjectNode n = items.addObject();
            n.put("instanceId", o.instanceId());
            n.put("outcome", o.outcome());
            n.put("code", o.code());
            n.put("detail", o.detail());
            if (o.incidentId() == null) {
                n.putNull("incidentId");
            } else {
                n.put("incidentId", o.incidentId());
            }
            n.put("occurredAt", o.occurredAt().toString());
        });
        if (found.size() > pageSize) {
            body.put("nextAfter", found.get(pageSize - 1).instanceId());
        } else {
            body.putNull("nextAfter");
        }
        return json(HttpStatus.OK, body, null);
    }

    static ObjectNode execution(MigrationExecution x) {
        ObjectNode node = EngineJson.NODES.objectNode();
        node.put("migrationId", x.migrationId());
        node.put("status", x.status());
        MigrationController.approval(node.putObject("approval"), x.approval());
        node.put("lockVersion", x.lockVersion());
        nullable(node, "proposedBy", x.proposedBy());
        nullable(node, "proposedAt", x.proposedAt());
        nullable(node, "reason", x.reason());
        nullable(node, "approvedBy", x.approvedBy());
        nullable(node, "approvedAt", x.approvedAt());
        nullable(node, "rejectedBy", x.rejectedBy());
        nullable(node, "rejectedAt", x.rejectedAt());
        nullable(node, "rejectionReason", x.rejectionReason());
        ObjectNode progress = node.putObject("progress");
        progress.put("total", x.total());
        progress.put("migrated", x.migrated());
        progress.put("failed", x.failed());
        progress.put("skipped", x.skipped());
        nullable(node, "startedAt", x.startedAt());
        nullable(node, "finishedAt", x.finishedAt());
        return node;
    }

    private static void nullable(ObjectNode node, String name, Object value) {
        if (value == null) {
            node.putNull(name);
        } else {
            node.put(name, value.toString());
        }
    }

    private static long lockVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new PreconditionRequiredException("Send If-Match with the proposal's lockVersion, for example \"1\"");
        }
        String value = ifMatch.strip();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        try {
            return Long.parseLong(value.replace("\"", ""));
        } catch (NumberFormatException e) {
            throw new InputInvalidException("If-Match must be a quoted lock version, for example \"1\"");
        }
    }

    private static String reason(JsonNode body) {
        for (String name : body.propertyNames()) {
            if (!REASON_ONLY.contains(name)) {
                throw new InputInvalidException("Unknown field " + (name.length() > 64 ? name.substring(0, 64) : name)
                        + "; the body takes reason only");
            }
        }
        JsonNode reason = body.get("reason");
        if (reason == null || !reason.isString()) {
            throw new InputInvalidException("reason is required");
        }
        return reason.stringValue();
    }

    private static JsonNode body(HttpServletRequest http) throws IOException {
        return parse(ProcessController.boundedBody(http, EngineDefaults.MAX_JSON_BODY_BYTES));
    }

    private static JsonNode parse(byte[] bytes) {
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

    private static ResponseEntity<byte[]> json(HttpStatus status, JsonNode body, Long etag) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON);
        if (etag != null) {
            builder.header(HttpHeaders.ETAG, "\"" + etag + "\"");
        }
        return builder.body(EngineJson.JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
    }
}
