package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.DefinitionSummary;
import io.github.dhluong90.bpm.process.api.DefinitionVersion;
import io.github.dhluong90.bpm.process.api.InstanceSummary;
import io.github.dhluong90.bpm.process.api.Page;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.TaskView;
import io.github.dhluong90.bpm.process.internal.json.EngineJson;
import io.github.dhluong90.bpm.process.internal.json.Values;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * JSON forms of the API results, written with the engine's own mapper (ADR-0021): REST bodies and recorded
 * idempotent answers use the same form, so a replay returns exactly the original body.
 */
public final class Views {

    private Views() {
    }

    /** @param v version @return JSON tree */
    public static ObjectNode definition(DefinitionVersion v) {
        ObjectNode o = EngineJson.NODES.objectNode();
        o.put("id", v.id()).put("key", v.key()).put("version", v.version()).put("status", v.status());
        putNullable(o, "name", v.name());
        o.put("definitionHash", v.definitionHash()).put("sourceFormat", v.sourceFormat().name())
                .put("sourceHash", v.sourceHash()).put("nodeCount", v.nodeCount());
        putNullable(o, "approvedBy", v.approvedBy());
        putNullable(o, "approvedAt", v.approvedAt() == null ? null : v.approvedAt().toString());
        putNullable(o, "effectiveFrom", v.effectiveFrom() == null ? null : v.effectiveFrom().toString());
        o.put("createdBy", v.createdBy()).put("createdAt", v.createdAt().toString());
        putNullable(o, "source", v.source());
        o.put("lockVersion", v.lockVersion());
        putNullable(o, "requestedBy", v.requestedBy());
        putNullable(o, "requestedAt", v.requestedAt() == null ? null : v.requestedAt().toString());
        putNullable(o, "decisionComment", v.decisionComment());
        putNullable(o, "retiredBy", v.retiredBy());
        putNullable(o, "retiredAt", v.retiredAt() == null ? null : v.retiredAt().toString());
        if (v.supersededBy() == null) {
            o.putNull("supersededBy");
        } else {
            o.put("supersededBy", v.supersededBy().intValue());
        }
        if (!v.warnings().isEmpty()) {
            o.set("warnings", problems(v.warnings()));
        }
        if (!v.alsoImported().isEmpty()) {
            ArrayNode also = o.putArray("alsoImported");
            v.alsoImported().forEach(r -> also.addObject().put("key", r.key()).put("version", r.version()));
        }
        return o;
    }

    /** @param entries lifecycle history @return JSON array */
    public static ArrayNode definitionHistory(List<io.github.dhluong90.bpm.process.api.DefinitionHistoryEntry> entries) {
        ArrayNode out = EngineJson.NODES.arrayNode();
        for (var e : entries) {
            ObjectNode o = out.addObject();
            o.put("action", e.action());
            putNullable(o, "fromStatus", e.fromStatus());
            o.put("toStatus", e.toStatus()).put("actor", e.actor());
            putNullable(o, "comment", e.comment());
            o.put("occurredAt", e.occurredAt().toString());
        }
        return out;
    }

    static String definitionToJson(DefinitionVersion v) {
        return EngineJson.JSON.writeValueAsString(definition(v));
    }

    static DefinitionVersion definitionFromJson(String json) {
        JsonNode n = EngineJson.JSON.readTree(json);
        return new DefinitionVersion(n.get("id").stringValue(), n.get("key").stringValue(), n.get("version").intValue(),
                n.get("status").stringValue(), text(n, "name"), n.get("definitionHash").stringValue(),
                SourceFormat.valueOf(n.get("sourceFormat").stringValue()), n.get("sourceHash").stringValue(),
                n.get("nodeCount").intValue(), text(n, "approvedBy"), n.get("createdBy").stringValue(),
                Instant.parse(n.get("createdAt").stringValue()), text(n, "source"),
                n.has("lockVersion") ? n.get("lockVersion").longValue() : 0, instant(n, "effectiveFrom"),
                text(n, "requestedBy"), instant(n, "requestedAt"), instant(n, "approvedAt"), text(n, "decisionComment"),
                text(n, "retiredBy"), instant(n, "retiredAt"),
                n.has("supersededBy") && !n.get("supersededBy").isNull() ? n.get("supersededBy").intValue() : null,
                warningsFromJson(n.get("warnings")), alsoFromJson(n.get("alsoImported")));
    }

    private static List<io.github.dhluong90.bpm.process.api.DefinitionRef> alsoFromJson(JsonNode array) {
        List<io.github.dhluong90.bpm.process.api.DefinitionRef> out = new java.util.ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode r : array) {
                out.add(new io.github.dhluong90.bpm.process.api.DefinitionRef(r.get("key").stringValue(),
                        r.get("version").intValue()));
            }
        }
        return out;
    }

    private static List<DefinitionProblem> warningsFromJson(JsonNode array) {
        List<DefinitionProblem> out = new java.util.ArrayList<>();
        if (array != null && array.isArray()) {
            for (JsonNode w : array) {
                out.add(new DefinitionProblem(w.get("code").stringValue(), w.get("message").stringValue(),
                        text(w, "element"), text(w, "elementId"), w.has("line") ? w.get("line").intValue() : 0,
                        text(w, "stage")));
            }
        }
        return out;
    }

    /** @param i instance @return JSON tree */
    public static ObjectNode instance(ProcessInstance i) {
        ObjectNode o = EngineJson.NODES.objectNode();
        o.put("id", i.id()).put("definitionKey", i.definitionKey()).put("definitionVersion", i.definitionVersion());
        putNullable(o, "businessKey", i.businessKey());
        o.put("status", i.status());
        o.set("variables", Values.toJson(i.variables()));
        ArrayNode tokens = o.putArray("tokens");
        for (ProcessInstance.Token t : i.tokens()) {
            ObjectNode tn = tokens.addObject().put("id", t.id()).put("kind", t.kind());
            putNullable(tn, "scopeTokenId", t.scopeTokenId());
            tn.put("nodeId", t.nodeId()).put("status", t.status());
        }
        ArrayNode tasks = o.putArray("tasks");
        for (ProcessInstance.Task t : i.tasks()) {
            ObjectNode tn = tasks.addObject().put("id", t.id()).put("nodeId", t.nodeId());
            putNullable(tn, "name", t.name());
            tn.put("assignee", t.assignee()).put("status", t.status()).put("lockVersion", t.lockVersion());
            putNullable(tn, "claimedBy", t.claimedBy());
            putNullable(tn, "claimedAt", t.claimedAt() == null ? null : t.claimedAt().toString());
            putNullable(tn, "completedBy", t.completedBy());
        }
        o.put("lockVersion", i.lockVersion());
        putNullable(o, "startedBy", i.startedBy());
        o.put("startedAt", i.startedAt().toString());
        putNullable(o, "endedAt", i.endedAt() == null ? null : i.endedAt().toString());
        return o;
    }

    static String instanceToJson(ProcessInstance i) {
        return EngineJson.JSON.writeValueAsString(instance(i));
    }

    static ProcessInstance instanceFromJson(String json) {
        JsonNode n = EngineJson.JSON.readTree(json);
        List<ProcessInstance.Token> tokens = new ArrayList<>();
        n.get("tokens").forEach(t -> tokens.add(new ProcessInstance.Token(t.get("id").stringValue(),
                t.get("kind").stringValue(), text(t, "scopeTokenId"), t.get("nodeId").stringValue(),
                t.get("status").stringValue())));
        List<ProcessInstance.Task> tasks = new ArrayList<>();
        n.get("tasks").forEach(t -> tasks.add(new ProcessInstance.Task(t.get("id").stringValue(),
                t.get("nodeId").stringValue(), text(t, "name"), t.get("assignee").stringValue(),
                t.get("status").stringValue(), t.get("lockVersion").longValue(), text(t, "claimedBy"),
                instant(t, "claimedAt"), text(t, "completedBy"))));
        @SuppressWarnings("unchecked")
        Map<String, Object> variables = (Map<String, Object>) Values.fromJson(n.get("variables"));
        String ended = text(n, "endedAt");
        return new ProcessInstance(n.get("id").stringValue(), n.get("definitionKey").stringValue(),
                n.get("definitionVersion").intValue(), text(n, "businessKey"), n.get("status").stringValue(), variables,
                tokens, tasks, n.get("lockVersion").longValue(), text(n, "startedBy"),
                Instant.parse(n.get("startedAt").stringValue()), ended == null ? null : Instant.parse(ended));
    }

    static String startedToJson(ProcessServices.Started started) {
        ObjectNode o = EngineJson.NODES.objectNode().put("created", started.created())
                .put("summaryOnly", started.summaryOnly());
        o.set("instance", instance(started.instance()));
        return EngineJson.JSON.writeValueAsString(o);
    }

    static ProcessServices.Started startedFromJson(String json) {
        JsonNode n = EngineJson.JSON.readTree(json);
        return new ProcessServices.Started(instanceFromJson(EngineJson.JSON.writeValueAsString(n.get("instance"))),
                n.get("created").booleanValue(), n.has("summaryOnly") && n.get("summaryOnly").booleanValue());
    }

    /**
     * The refusal of a standard (PD-141): one item shape in both engines.
     *
     * @param found the unsupported elements, in document order
     * @return JSON array of {@code element}, {@code elementId}, {@code line}, {@code stage}, {@code message}
     */
    public static ArrayNode unsupportedElements(List<DefinitionProblem> found) {
        ArrayNode a = EngineJson.NODES.arrayNode();
        for (DefinitionProblem p : found) {
            ObjectNode o = a.addObject();
            putNullable(o, "element", p.element());
            putNullable(o, "elementId", p.elementId());
            o.put("line", Math.max(p.line(), 1));
            o.put("stage", p.stage() == null ? DefinitionProblem.NOT_PLANNED : p.stage());
            o.put("message", p.message());
        }
        return a;
    }

    /** @param problems problems @return JSON array */
    public static ArrayNode problems(List<DefinitionProblem> problems) {
        ArrayNode a = EngineJson.NODES.arrayNode();
        for (DefinitionProblem p : problems) {
            ObjectNode o = a.addObject().put("code", p.code()).put("message", p.message());
            putNullable(o, "element", p.element());
            putNullable(o, "elementId", p.elementId());
            if (p.line() > 0) {
                o.put("line", p.line());
            }
            if (p.stage() != null) {
                o.put("stage", p.stage());
            }
        }
        return a;
    }

    /** @param node JSON object @return engine variables */
    public static Map<String, Object> variables(JsonNode node) {
        Object value = Values.fromJson(node);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?>)) {
            throw new io.github.dhluong90.bpm.process.api.InputInvalidException("variables must be a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) value;
        return map;
    }

    private static void putNullable(ObjectNode o, String name, String value) {
        if (value == null) {
            o.putNull(name);
        } else {
            o.put(name, value);
        }
    }

    /** @param t task @return JSON tree, with {@code allowedActions} */
    public static ObjectNode task(TaskView t) {
        ObjectNode o = EngineJson.NODES.objectNode();
        o.put("id", t.id()).put("instanceId", t.instanceId()).put("definitionKey", t.definitionKey())
                .put("definitionVersion", t.definitionVersion());
        putNullable(o, "businessKey", t.businessKey());
        o.put("nodeId", t.nodeId());
        putNullable(o, "name", t.name());
        o.put("assignee", t.assignee()).put("fourEyes", t.fourEyes()).put("status", t.status())
                .put("lockVersion", t.lockVersion());
        putNullable(o, "claimedBy", t.claimedBy());
        putNullable(o, "claimedAt", t.claimedAt() == null ? null : t.claimedAt().toString());
        o.put("createdAt", t.createdAt().toString());
        putNullable(o, "dueAt", t.dueAt() == null ? null : t.dueAt().toString());
        putNullable(o, "completedBy", t.completedBy());
        putNullable(o, "completedAt", t.completedAt() == null ? null : t.completedAt().toString());
        if (t.review() != null) {
            o.set("review", Values.toJson(t.review()));
        }
        ArrayNode actions = o.putArray("allowedActions");
        for (String action : List.of("claim", "unclaim", "complete", "reassign", "delegate")) {
            if (t.allowedActions().contains(action)) {
                actions.add(action);
            }
        }
        return o;
    }

    /**
     * @param f a resolved task form
     * @return JSON tree of {@code task-form.v1.schema.json}: {@code schemaVersion}, {@code taskId},
     *     {@code lockVersion}, then {@code form} (schema, hints, data) or {@code link}
     */
    public static ObjectNode taskForm(io.github.dhluong90.bpm.process.api.TaskForm f) {
        ObjectNode o = EngineJson.NODES.objectNode();
        o.put("schemaVersion", 1).put("taskId", f.taskId()).put("lockVersion", f.lockVersion());
        if (f.isLink()) {
            o.put("link", f.link());
        } else {
            ObjectNode form = o.putObject("form");
            form.set("schema", Values.toJson(f.schema()));
            form.set("hints", Values.toJson(f.hints()));
            form.set("data", Values.toJson(f.data()));
        }
        return o;
    }

    /** The stored answer of a claim or unclaim: the task without the caller's actions. */
    static String taskToJson(TaskView t) {
        ObjectNode o = task(t);
        o.remove("allowedActions");
        return EngineJson.JSON.writeValueAsString(o);
    }

    static TaskView taskFromJson(String json) {
        JsonNode n = EngineJson.JSON.readTree(json);
        return new TaskView(n.get("id").stringValue(), n.get("instanceId").stringValue(),
                n.get("definitionKey").stringValue(), n.get("definitionVersion").intValue(), text(n, "businessKey"),
                n.get("nodeId").stringValue(), text(n, "name"), n.get("assignee").stringValue(),
                n.get("fourEyes").booleanValue(), n.get("status").stringValue(), n.get("lockVersion").longValue(),
                text(n, "claimedBy"), instant(n, "claimedAt"), Instant.parse(n.get("createdAt").stringValue()),
                text(n, "completedBy"), instant(n, "completedAt"), java.util.Set.of(),
                n.has("review") && n.get("review").isObject() ? Values.readMap(n.get("review").toString()) : null,
                instant(n, "dueAt"));
    }

    /** @param i incident @return JSON tree, with {@code allowedActions} (#68) */
    public static ObjectNode incident(io.github.dhluong90.bpm.process.api.Incident i) {
        ObjectNode o = EngineJson.NODES.objectNode();
        o.put("id", i.id()).put("instanceId", i.instanceId()).put("nodeId", i.nodeId());
        putNullable(o, "tokenId", i.tokenId());
        o.put("errorCode", i.errorCode()).put("causeSummary", i.causeSummary()).put("retryCount", i.retryCount())
                .put("status", i.status()).put("lockVersion", i.lockVersion()).put("retrying", i.retrying())
                .put("createdAt", i.createdAt().toString());
        putNullable(o, "resolvedAt", i.resolvedAt() == null ? null : i.resolvedAt().toString());
        putNullable(o, "resolution", i.resolution());
        putNullable(o, "resolvedBy", i.resolvedBy());
        io.github.dhluong90.bpm.process.api.PendingResolution p = i.pendingResolution();
        if (p == null) {
            o.putNull("pendingResolution");
        } else {
            ObjectNode proposal = o.putObject("pendingResolution");
            proposal.put("proposedBy", p.proposedBy()).put("proposedAt", p.proposedAt().toString());
            putNullable(proposal, "reason", p.reason());
            proposal.set("output", Values.toJson(p.output()));
            putNullable(proposal, "approvalMode", p.approvalMode());
        }
        ArrayNode actions = o.putArray("allowedActions");
        i.allowedActions().forEach(actions::add);
        return o;
    }

    /** The stored answer of an incident command: the incident without the caller's actions. */
    static String incidentToJson(io.github.dhluong90.bpm.process.api.Incident i) {
        ObjectNode o = incident(i);
        o.remove("allowedActions");
        return EngineJson.JSON.writeValueAsString(o);
    }

    static io.github.dhluong90.bpm.process.api.Incident incidentFromJson(String json) {
        JsonNode n = EngineJson.JSON.readTree(json);
        JsonNode p = n.get("pendingResolution");
        io.github.dhluong90.bpm.process.api.PendingResolution pending = p == null || p.isNull() ? null
                : new io.github.dhluong90.bpm.process.api.PendingResolution(p.get("proposedBy").stringValue(),
                        Instant.parse(p.get("proposedAt").stringValue()), text(p, "reason"),
                        p.has("output") && p.get("output").isObject() ? Values.readMap(p.get("output").toString())
                                : Map.of(), text(p, "approvalMode"));
        return new io.github.dhluong90.bpm.process.api.Incident(n.get("id").stringValue(),
                n.get("instanceId").stringValue(), n.get("nodeId").stringValue(), text(n, "tokenId"),
                n.get("errorCode").stringValue(), n.get("causeSummary").stringValue(), n.get("retryCount").intValue(),
                n.get("status").stringValue(), n.get("lockVersion").longValue(),
                Instant.parse(n.get("createdAt").stringValue()), instant(n, "resolvedAt"), text(n, "resolution"),
                text(n, "resolvedBy"), pending, n.get("retrying").booleanValue(), List.of());
    }

    /** @param d summary @return JSON tree */
    public static ObjectNode definitionSummary(DefinitionSummary d) {
        ObjectNode o = EngineJson.NODES.objectNode().put("key", d.key());
        putNullable(o, "name", d.name());
        o.put("latestVersion", d.latestVersion()).put("latestStatus", d.latestStatus());
        if (d.effectiveVersion() == null) {
            o.putNull("effectiveVersion");
        } else {
            o.put("effectiveVersion", d.effectiveVersion());
        }
        o.put("lastChangedAt", d.lastChangedAt().toString());
        return o;
    }

    /** @param a pending approval @return JSON tree: hashes and identities, never the definition (#31) */
    public static ObjectNode pendingApproval(io.github.dhluong90.bpm.process.api.PendingApproval a) {
        ObjectNode o = EngineJson.NODES.objectNode().put("kind", a.kind());
        putNullable(o, "id", a.id());
        o.put("key", a.key());
        putNullable(o, "name", a.name());
        o.put("version", a.version());
        if (a.sourceVersion() == null) {
            o.putNull("sourceVersion");
        } else {
            o.put("sourceVersion", a.sourceVersion());
        }
        putNullable(o, "definitionHash", a.definitionHash());
        o.put("contentHash", a.contentHash()).put("lockVersion", a.lockVersion()).put("requestedBy", a.requestedBy())
                .put("requestedAt", a.requestedAt().toString());
        putNullable(o, "reason", a.reason());
        // #442: the requester as the screen shows them; the id when the host knows no name
        putNullable(o, "requestedByName", a.requestedByName());
        ArrayNode actions = o.putArray("allowedActions");
        a.allowedActions().forEach(actions::add);
        ObjectNode denied = o.putObject("deniedActions");
        a.deniedActions().forEach(denied::put);
        return o;
    }

    /** @param i summary @return JSON tree */
    public static ObjectNode instanceSummary(InstanceSummary i) {
        ObjectNode o = EngineJson.NODES.objectNode();
        o.put("id", i.id()).put("definitionKey", i.definitionKey()).put("definitionVersion", i.definitionVersion());
        putNullable(o, "businessKey", i.businessKey());
        o.put("status", i.status()).put("lockVersion", i.lockVersion());
        putNullable(o, "startedBy", i.startedBy());
        o.put("startedAt", i.startedAt().toString());
        putNullable(o, "endedAt", i.endedAt() == null ? null : i.endedAt().toString());
        return o;
    }

    /** @param t a task list item @return JSON tree; no variables and no review context */
    public static ObjectNode taskSummary(io.github.dhluong90.bpm.process.api.TaskSummary t) {
        ObjectNode o = EngineJson.NODES.objectNode();
        o.put("id", t.id()).put("instanceId", t.instanceId()).put("definitionKey", t.definitionKey())
                .put("definitionVersion", t.definitionVersion());
        putNullable(o, "businessKey", t.businessKey());
        o.put("nodeId", t.nodeId());
        putNullable(o, "name", t.name());
        o.put("assignee", t.assignee()).put("status", t.status()).put("lockVersion", t.lockVersion());
        putNullable(o, "claimedBy", t.claimedBy());
        o.put("createdAt", t.createdAt().toString());
        putNullable(o, "dueAt", t.dueAt() == null ? null : t.dueAt().toString());
        o.put("slaState", t.slaState());
        putNullable(o, "overdueBy", t.overdueBy() == null ? null : t.overdueBy().toString());
        return o;
    }

    /**
     * @param page a page
     * @param item item writer
     * @param <T> item type
     * @return {@code {data, nextCursor, hasNext}}
     */
    public static <T> ObjectNode page(Page<T> page, java.util.function.Function<T, ObjectNode> item) {
        ObjectNode o = EngineJson.NODES.objectNode();
        ArrayNode data = o.putArray("data");
        page.items().forEach(t -> data.add(item.apply(t)));
        putNullable(o, "nextCursor", page.nextCursor());
        o.put("hasNext", page.hasNext());
        return o;
    }

    private static Instant instant(JsonNode n, String name) {
        String value = text(n, name);
        return value == null ? null : Instant.parse(value);
    }

    private static String text(JsonNode n, String name) {
        JsonNode v = n.get(name);
        return v == null || v.isNull() ? null : v.stringValue();
    }
}
