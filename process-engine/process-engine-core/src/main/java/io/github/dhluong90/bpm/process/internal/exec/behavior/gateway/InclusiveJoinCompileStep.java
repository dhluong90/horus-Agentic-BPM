package io.github.dhluong90.bpm.process.internal.exec.behavior.gateway;

import io.github.dhluong90.bpm.process.internal.exec.CompileStep;
import io.github.dhluong90.bpm.process.internal.exec.CompiledProcess;
import io.github.dhluong90.bpm.process.internal.exec.FacetKey;
import io.github.dhluong90.bpm.process.internal.model.bpmn.BoundaryEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowElement;
import io.github.dhluong90.bpm.process.internal.model.bpmn.InclusiveGateway;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SequenceFlow;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * For every inclusive gateway with several incoming flows, once per definition version: the ids of the nodes from
 * which a token can still reach it without passing through it. A node reaches the gateway along sequence flows, or
 * through a boundary event attached to it. Flows never cross a scope boundary, so the set of a join stays inside its
 * own scope; a subprocess or block that reaches the join stands in for everything inside it.
 */
final class InclusiveJoinCompileStep implements CompileStep {

    /** Nodes that reach each inclusive join, by join id. */
    record JoinSources(Map<String, Set<String>> sources) {
        JoinSources {
            Map<String, Set<String>> copy = new HashMap<>();
            sources.forEach((k, v) -> copy.put(k, Set.copyOf(v)));
            sources = Map.copyOf(copy);
        }

        /**
         * @param gatewayId an inclusive gateway
         * @return whether it joins, that is, has several incoming flows
         */
        boolean isJoin(String gatewayId) {
            return sources.containsKey(gatewayId);
        }

        /**
         * @param gatewayId an inclusive join
         * @return the nodes that can still send it a token; empty for a gateway that does not join
         */
        Set<String> reaching(String gatewayId) {
            return sources.getOrDefault(gatewayId, Set.of());
        }
    }

    static final FacetKey<JoinSources> JOIN_SOURCES = new FacetKey<>("gateway.inclusive-join-sources",
            JoinSources.class);

    @Override
    public void compile(Process process, CompiledProcess.Builder builder) {
        Map<String, List<String>> predecessors = new HashMap<>();
        Map<String, Integer> incoming = new HashMap<>();
        List<String> inclusive = new ArrayList<>();
        for (FlowElement element : process.allFlowElements()) {
            if (element instanceof SequenceFlow flow) {
                predecessors.computeIfAbsent(flow.targetRef(), k -> new ArrayList<>()).add(flow.sourceRef());
                incoming.merge(flow.targetRef(), 1, Integer::sum);
            } else if (element instanceof BoundaryEvent boundary && boundary.attachedToRef() != null) {
                predecessors.computeIfAbsent(boundary.id(), k -> new ArrayList<>()).add(boundary.attachedToRef());
            } else if (element instanceof InclusiveGateway gateway) {
                inclusive.add(gateway.id());
            }
        }
        Map<String, Set<String>> sources = new HashMap<>();
        for (String join : inclusive) {
            if (incoming.getOrDefault(join, 0) > 1) {
                sources.put(join, reaching(join, predecessors));
            }
        }
        builder.facet(JOIN_SOURCES, new JoinSources(sources));
    }

    /** Walks the flows backwards from the join and never expands the join itself, so a loop through it adds nothing. */
    private static Set<String> reaching(String join, Map<String, List<String>> predecessors) {
        Set<String> seen = new HashSet<>();
        Deque<String> open = new ArrayDeque<>(predecessors.getOrDefault(join, List.of()));
        while (!open.isEmpty()) {
            String node = open.poll();
            if (node.equals(join) || !seen.add(node)) {
                continue;
            }
            open.addAll(predecessors.getOrDefault(node, List.of()));
        }
        return seen;
    }
}
