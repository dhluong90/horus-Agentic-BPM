package io.github.dhluong90.bpm.process.rest;

import io.github.dhluong90.bpm.process.api.InputInvalidException;
import io.github.dhluong90.bpm.process.api.MigrationDryRun;
import io.github.dhluong90.bpm.process.api.MigrationPlan;
import io.github.dhluong90.bpm.process.api.MigrationPlanRequest;
import io.github.dhluong90.bpm.process.api.MigrationService;
import io.github.dhluong90.bpm.process.api.PayloadTooLargeException;
import io.github.dhluong90.bpm.process.internal.json.EngineJson;
import io.github.dhluong90.bpm.process.internal.service.EngineDefaults;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Migration plans and their dry run (#74, PRE-014, PRE-174, PRE-175) under {@code /api/bpm/processes/v1}. The
 * permission, tenant and caller are checked in the service; a tenant in a body is never read. The dry run only
 * reads, so it takes no idempotency key.
 */
@RestController
@RequestMapping(ProcessController.BASE)
public class MigrationController {

    private static final Set<String> PLAN_FIELDS = Set.of("definitionKey", "sourceVersion", "targetVersion");

    private final MigrationService migrations;

    /** @param migrations the migration service */
    public MigrationController(MigrationService migrations) {
        this.migrations = migrations;
    }

    /** Creates a plan: {@code 201} with the plan; a repeat with the same Idempotency-Key returns it. */
    @PostMapping(value = "/migrations", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> plan(HttpServletRequest http,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) throws IOException {
        JsonNode body = parse(bounded(http));
        for (String name : body.propertyNames()) {
            if (!PLAN_FIELDS.contains(name)) {
                throw new InputInvalidException("Unknown field " + (name.length() > 64 ? name.substring(0, 64) : name)
                        + "; a plan takes definitionKey, sourceVersion and targetVersion (step mappings are not "
                        + "supported in this release)");
            }
        }
        JsonNode key = body.get("definitionKey");
        if (key == null || !key.isString()) {
            throw new InputInvalidException("definitionKey is required");
        }
        MigrationPlan plan = migrations.plan(new MigrationPlanRequest(key.asString(), version(body, "sourceVersion"),
                version(body, "targetVersion"), idempotencyKey));
        return ResponseEntity.status(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON)
                .header("Location", ProcessController.BASE + "/migrations/" + plan.id()).body(bytes(plan(plan)));
    }

    /** Reads a plan. */
    @GetMapping("/migrations/{id}")
    public ResponseEntity<byte[]> find(@PathVariable("id") String id) {
        return ok(plan(migrations.find(id)));
    }

    /** Runs a dry run of the plan: reads only, changes nothing, needs no approval. */
    @PostMapping("/migrations/{id}:validate")
    public ResponseEntity<byte[]> validate(@PathVariable("id") String id) {
        return ok(dryRun(migrations.validate(id)));
    }

    static ObjectNode plan(MigrationPlan p) {
        ObjectNode node = EngineJson.NODES.objectNode();
        node.put("id", p.id());
        node.put("definitionKey", p.definitionKey());
        node.put("sourceVersion", p.sourceVersion());
        node.put("targetVersion", p.targetVersion());
        node.put("status", p.status());
        node.put("planHash", p.planHash());
        approval(node.putObject("approval"), p.approval());
        node.put("createdBy", p.createdBy());
        node.put("createdAt", p.createdAt().toString());
        return node;
    }

    static ObjectNode dryRun(MigrationDryRun r) {
        ObjectNode node = EngineJson.NODES.objectNode();
        node.put("migrationId", r.migrationId());
        node.put("planHash", r.planHash());
        node.put("definitionKey", r.definitionKey());
        node.put("sourceVersion", r.sourceVersion());
        node.put("targetVersion", r.targetVersion());
        node.put("valid", r.valid());
        node.put("checkedAt", r.checkedAt().toString());
        node.put("validUntil", r.validUntil().toString());
        approval(node.putObject("approval"), r.approval());
        ObjectNode instances = node.putObject("instances");
        instances.put("running", r.instances().running());
        instances.put("movable", r.instances().movable());
        instances.put("blocked", r.instances().blocked());
        ArrayNode nodes = node.putArray("nodes");
        for (MigrationDryRun.NodeMapping m : r.nodes()) {
            ObjectNode n = nodes.addObject();
            n.put("nodeId", m.nodeId());
            putNullable(n, "name", m.name());
            putNullable(n, "type", m.type());
            n.put("liveTokens", m.liveTokens());
            n.put("mapping", m.mapping());
            putNullable(n, "targetNodeId", m.targetNodeId());
            putNullable(n, "targetType", m.targetType());
        }
        ArrayNode conflicts = node.putArray("conflicts");
        for (MigrationDryRun.Conflict c : r.conflicts()) {
            ObjectNode n = conflicts.addObject();
            n.put("code", c.code());
            putNullable(n, "nodeId", c.nodeId());
            putNullable(n, "name", c.name());
            sample(n.putObject("instances"), c.affected());
        }
        sample(node.putObject("movable"), r.movable());
        return node;
    }

    private static void approval(ObjectNode node, MigrationPlan.Approval approval) {
        node.put("mode", approval.value());
        node.put("secondPersonRequired", approval.needsSecondPerson());
    }

    private static void sample(ObjectNode node, MigrationDryRun.InstanceSample sample) {
        node.put("count", sample.count());
        ArrayNode ids = node.putArray("instanceIds");
        List<String> list = sample.instanceIds();
        list.forEach(ids::add);
        node.put("truncated", sample.truncated());
    }

    private static void putNullable(ObjectNode node, String name, String value) {
        if (value == null) {
            node.putNull(name);
        } else {
            node.put(name, value);
        }
    }

    private static int version(JsonNode body, String name) {
        JsonNode value = body.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 1) {
            throw new InputInvalidException(name + " must be a version number, for example 1");
        }
        return value.asInt();
    }

    private static byte[] bounded(HttpServletRequest http) throws IOException {
        int limit = EngineDefaults.MAX_JSON_BODY_BYTES;
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

    private static ResponseEntity<byte[]> ok(JsonNode body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(bytes(body));
    }

    private static byte[] bytes(JsonNode body) {
        return EngineJson.JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
    }
}
