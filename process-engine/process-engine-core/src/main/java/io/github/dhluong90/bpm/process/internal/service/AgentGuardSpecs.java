package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.internal.json.Values;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import io.github.dhluong90.bpm.process.internal.model.horus.AgentTaskSpec;
import io.github.dhluong90.bpm.process.internal.model.horus.GuardrailSpec;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the {@code guard} payload of an agent job (#52, DESIGN section 4.1) from the agent step of the definition
 * version being run: the version was validated at import, approved and hash-checked before it runs, so the spec the
 * result check reads is the one that was approved. Nothing of it comes from a request. A review is always four-eyes,
 * so the payload states {@code fourEyes: true} and the model has no way to say otherwise.
 */
final class AgentGuardSpecs {

    private AgentGuardSpecs() {
    }

    /**
     * @param node an agent step
     * @return the guard spec as the job payload carries it; a part the definition lacks is left out, so the result
     *     check refuses the answer with {@code GUARD_SPEC_INVALID} rather than guess
     */
    static Map<String, Object> payload(FlowNode node) {
        Map<String, Object> guard = new LinkedHashMap<>();
        AgentTaskSpec agent = node.extensions().first(AgentTaskSpec.class).orElse(null);
        if (agent != null && agent.outputSchema() != null) {
            guard.put("outputSchema", Values.readMap(agent.outputSchema()));
        }
        node.extensions().first(GuardrailSpec.class).ifPresent(g -> {
            guard.put("decision", g.decision());
            guard.put("onViolation", g.onViolation());
            if (g.reviewer() != null) {
                guard.put("reviewer", g.reviewer());
            }
        });
        guard.put("fourEyes", true);
        if (agent != null && agent.fieldTitles() != null) {
            // Labels for the review screen (#386); no check reads them.
            guard.put("fieldTitles", Values.readMap(agent.fieldTitles()));
        }
        return guard;
    }
}
