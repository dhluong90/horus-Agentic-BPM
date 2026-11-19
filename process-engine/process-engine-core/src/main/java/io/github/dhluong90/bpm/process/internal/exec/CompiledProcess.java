package io.github.dhluong90.bpm.process.internal.exec;

import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SequenceFlow;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A process ready to execute: lookups by id and the facets of every {@link CompileStep}. Immutable.
 */
public final class CompiledProcess {

    private final String definitionId;
    private final Process process;
    private final Map<String, FlowNode> nodes;
    private final Map<String, SequenceFlow> flows;
    private final Map<String, List<SequenceFlow>> outgoing;
    private final Map<FacetKey<?>, Object> facets;

    private CompiledProcess(Builder builder) {
        this.definitionId = builder.definitionId;
        this.process = builder.process;
        this.nodes = Collections.unmodifiableMap(builder.nodes);
        this.flows = Collections.unmodifiableMap(builder.flows);
        Map<String, List<SequenceFlow>> out = new HashMap<>();
        builder.outgoing.forEach((k, v) -> out.put(k, List.copyOf(v)));
        this.outgoing = Collections.unmodifiableMap(out);
        this.facets = Map.copyOf(builder.facets);
    }

    /**
     * @param definitionId the definition version id
     * @param process the process
     * @return a builder with the node and flow lookups filled
     */
    public static Builder builder(String definitionId, Process process) {
        return new Builder(definitionId, process);
    }

    /** @return the definition version id */
    public String definitionId() {
        return definitionId;
    }

    /** @return the process */
    public Process process() {
        return process;
    }

    /**
     * @param id node id
     * @return the node
     */
    public Optional<FlowNode> node(String id) {
        return Optional.ofNullable(nodes.get(id));
    }

    /**
     * @param id flow id
     * @return the flow
     */
    public Optional<SequenceFlow> flow(String id) {
        return Optional.ofNullable(flows.get(id));
    }

    /**
     * @param nodeId node id
     * @return outgoing flows in document order
     */
    public List<SequenceFlow> outgoing(String nodeId) {
        return outgoing.getOrDefault(nodeId, List.of());
    }

    /** @return every flow node, in document order */
    public List<FlowNode> nodes() {
        return List.copyOf(nodes.values());
    }

    /**
     * @param key facet key
     * @param <T> facet type
     * @return the facet, or null when no compile step set it
     */
    public <T> T facet(FacetKey<T> key) {
        return key.type().cast(facets.get(key));
    }

    /** Builds a {@link CompiledProcess}. */
    public static final class Builder {
        private final String definitionId;
        private final Process process;
        private final Map<String, FlowNode> nodes = new LinkedHashMap<>();
        private final Map<String, SequenceFlow> flows = new LinkedHashMap<>();
        private final Map<String, List<SequenceFlow>> outgoing = new HashMap<>();
        private final Map<FacetKey<?>, Object> facets = new HashMap<>();

        private Builder(String definitionId, Process process) {
            this.definitionId = Objects.requireNonNull(definitionId, "definitionId must not be null");
            this.process = Objects.requireNonNull(process, "process must not be null");
            process.flowElements().forEach(element -> {
                if (element instanceof FlowNode node) {
                    nodes.put(node.id(), node);
                } else if (element instanceof SequenceFlow flow) {
                    flows.put(flow.id(), flow);
                    outgoing.computeIfAbsent(flow.sourceRef(), k -> new ArrayList<>()).add(flow);
                }
            });
        }

        /** @return the process being compiled */
        public Process process() {
            return process;
        }

        /**
         * @param key facet key
         * @param value value
         * @param <T> type
         * @return this builder
         */
        public <T> Builder facet(FacetKey<T> key, T value) {
            facets.put(key, key.type().cast(value));
            return this;
        }

        /** @return the compiled process */
        public CompiledProcess build() {
            return new CompiledProcess(this);
        }
    }
}
