package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.api.UnclassifiedField;
import io.github.dhluong90.bpm.process.internal.json.EngineJson;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowElement;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ServiceTask;
import io.github.dhluong90.bpm.process.internal.model.horus.AgentTaskSpec;
import io.github.dhluong90.bpm.process.internal.model.horus.InputMapping;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * The fields of a definition that declare no classification (PD-146, #438). Masking applies to agent steps only: their
 * {@code horus:input} entries and the properties of their output schema. A field is classified when it declares
 * {@code mask} as true or false; the name and values are never looked at. Pure: no I/O and no clock.
 */
final class UnclassifiedFields {

    /** Deepest nesting of output schema properties read; deeper properties are not listed. */
    static final int MAX_DEPTH = 8;

    private UnclassifiedFields() {
    }

    /**
     * @param process a parsed definition
     * @return the unclassified fields, step by step in document order: inputs first, then output properties
     */
    static List<UnclassifiedField> of(Process process) {
        List<UnclassifiedField> fields = new ArrayList<>();
        for (FlowElement element : process.allFlowElements()) {
            if (!(element instanceof ServiceTask task)) {
                continue;
            }
            AgentTaskSpec agent = task.extensions().first(AgentTaskSpec.class).orElse(null);
            if (agent == null) {
                continue;
            }
            for (InputMapping input : task.extensions().all(InputMapping.class)) {
                if (!input.maskDeclared()) {
                    fields.add(new UnclassifiedField(task.id(), input.name(), UnclassifiedField.INPUT));
                }
            }
            if (agent.outputSchema() != null) {
                outputs(task.id(), "", EngineJson.JSON.readTree(agent.outputSchema()), 0, fields);
            }
        }
        return List.copyOf(fields);
    }

    private static void outputs(String step, String prefix, JsonNode schema, int depth, List<UnclassifiedField> out) {
        JsonNode properties = schema.get("properties");
        if (properties == null || !properties.isObject() || depth >= MAX_DEPTH) {
            return;
        }
        for (Map.Entry<String, JsonNode> property : properties.properties()) {
            String path = prefix + property.getKey();
            JsonNode value = property.getValue();
            JsonNode nested = value.get("properties");
            JsonNode items = value.get("items");
            if (nested != null && nested.isObject() && !nested.isEmpty()) {
                outputs(step, path + ".", value, depth + 1, out);
            } else if (items != null && items.has("properties")) {
                outputs(step, path + "[].", items, depth + 1, out);
            } else if (!(value.get("mask") != null && value.get("mask").isBoolean())) {
                out.add(new UnclassifiedField(step, path, UnclassifiedField.OUTPUT));
            }
        }
    }
}
