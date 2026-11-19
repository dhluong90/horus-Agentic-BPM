package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.DefinitionVersion;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SourceFormat;
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
        o.put("createdBy", v.createdBy()).put("createdAt", v.createdAt().toString());
        return o;
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
                Instant.parse(n.get("createdAt").stringValue()));
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
                t.get("status").stringValue(), t.get("lockVersion").longValue(), text(t, "completedBy"))));
        @SuppressWarnings("unchecked")
        Map<String, Object> variables = (Map<String, Object>) Values.fromJson(n.get("variables"));
        String ended = text(n, "endedAt");
        return new ProcessInstance(n.get("id").stringValue(), n.get("definitionKey").stringValue(),
                n.get("definitionVersion").intValue(), text(n, "businessKey"), n.get("status").stringValue(), variables,
                tokens, tasks, n.get("lockVersion").longValue(), text(n, "startedBy"),
                Instant.parse(n.get("startedAt").stringValue()), ended == null ? null : Instant.parse(ended));
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

    private static String text(JsonNode n, String name) {
        JsonNode v = n.get(name);
        return v == null || v.isNull() ? null : v.stringValue();
    }
}
