package io.github.dhluong90.bpm.process.internal.exec.behavior.core;

import io.github.dhluong90.bpm.process.internal.exec.CompileStep;
import io.github.dhluong90.bpm.process.internal.exec.CompiledProcess;
import io.github.dhluong90.bpm.process.internal.exec.FacetKey;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowElement;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ParallelGateway;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SequenceFlow;
import java.util.HashMap;
import java.util.Map;

/**
 * Counts the incoming sequence flows of every parallel gateway once per definition version, from the flows
 * themselves rather than the gateway's {@code incoming} list, so a join knows how many arrivals complete it.
 */
final class ParallelJoinCompileStep implements CompileStep {

    /** Incoming-flow count per parallel gateway id. */
    record JoinArity(Map<String, Integer> incoming) {
        JoinArity {
            incoming = Map.copyOf(incoming);
        }

        /**
         * @param gatewayId the gateway
         * @return its incoming-flow count; 0 when it has none
         */
        int of(String gatewayId) {
            return incoming.getOrDefault(gatewayId, 0);
        }
    }

    static final FacetKey<JoinArity> JOIN_ARITY = new FacetKey<>("core.parallel-join-arity", JoinArity.class);

    @Override
    public void compile(Process process, CompiledProcess.Builder builder) {
        Map<String, Integer> gateways = new HashMap<>();
        for (FlowElement element : process.allFlowElements()) {
            if (element instanceof ParallelGateway gateway) {
                gateways.put(gateway.id(), 0);
            }
        }
        for (FlowElement element : process.allFlowElements()) {
            if (element instanceof SequenceFlow flow && gateways.containsKey(flow.targetRef())) {
                gateways.merge(flow.targetRef(), 1, Integer::sum);
            }
        }
        builder.facet(JOIN_ARITY, new JoinArity(gateways));
    }
}
