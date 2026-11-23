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
    private final Map<String, String> containers;
    private final Map<String, List<io.github.dhluong90.bpm.process.internal.model.bpmn.BoundaryEvent>> boundaries;
    private final Map<String, String> compensationActivities;

    private CompiledProcess(Builder builder) {
        this.compensationActivities = Map.copyOf(builder.compensationActivities);
        this.containers = Map.copyOf(builder.containers);
        Map<String, List<io.github.dhluong90.bpm.process.internal.model.bpmn.BoundaryEvent>> attached = new HashMap<>();
        builder.boundaries.forEach((k, v) -> attached.put(k, List.copyOf(v)));
        this.boundaries = Collections.unmodifiableMap(attached);
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

    /**
     * The container a node stands in (#67): the id of its {@code saga} block, or the process id for a node of the
     * process itself.
     *
     * @param nodeId node id
     * @return the container id
     */
    public String containerOf(String nodeId) {
        return containers.getOrDefault(nodeId, process.id());
    }

    /**
     * @param containerId a {@code saga} block id, or the process id
     * @return the none start event of that container, if it has one
     */
    public Optional<FlowNode> startOf(String containerId) {
        return nodes.values().stream()
                .filter(n -> n instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.StartEvent s
                        && s.eventDefinitions().isEmpty() && containerOf(n.id()).equals(containerId))
                .findFirst();
    }

    /** @return every flow node, in document order, those of {@code saga} blocks included */
    /**
     * @param activityId the id of an activity
     * @return the boundary events attached to it (#489), in document order; empty for most activities
     */
    public List<io.github.dhluong90.bpm.process.internal.model.bpmn.BoundaryEvent> boundariesOf(String activityId) {
        return boundaries.getOrDefault(activityId, List.of());
    }

    /**
     * The compensation activity of a step (#488): the {@code isForCompensation} task that a compensation boundary event
     * attached to the step points at by an association. Its handler is the step's undo.
     *
     * @param activityId the id of the step that has the boundary
     * @return the compensation activity, if the step has a compensation boundary event
     */
    public Optional<FlowNode> compensationActivityOf(String activityId) {
        return Optional.ofNullable(compensationActivities.get(activityId)).map(nodes::get);
    }

    /**
     * @param node a node
     * @return the name of its link event definition when it is a link throw or catch event (#493), else empty
     */
    public static Optional<String> linkName(FlowNode node) {
        if (node instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.Event event
                && event.eventDefinitions().size() == 1
                && event.eventDefinitions().get(0) instanceof
                        io.github.dhluong90.bpm.process.internal.model.bpmn.LinkEventDefinition link) {
            return Optional.ofNullable(link.name());
        }
        return Optional.empty();
    }

    /**
     * @param name a link name
     * @param containerId the scope the link throw stands in
     * @return the link catch event of that name in that scope (#493)
     */
    public Optional<FlowNode> linkCatch(String name, String containerId) {
        return nodes.values().stream()
                .filter(n -> n instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.IntermediateCatchEvent
                        && linkName(n).filter(name::equals).isPresent() && containerOf(n.id()).equals(containerId))
                .findFirst();
    }

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
        private final Map<String, String> containers = new HashMap<>();
        private final Map<String, List<io.github.dhluong90.bpm.process.internal.model.bpmn.BoundaryEvent>> boundaries =
                new HashMap<>();
        private final Map<String, String> compensationActivities = new HashMap<>();

        private Builder(String definitionId, Process process) {
            this.definitionId = Objects.requireNonNull(definitionId, "definitionId must not be null");
            this.process = Objects.requireNonNull(process, "process must not be null");
            index(process.id(), process.flowElements());
            indexCompensation();
        }

        /** Ties each compensation boundary event to the activity its association points at (#488). */
        private void indexCompensation() {
            for (io.github.dhluong90.bpm.process.internal.model.bpmn.Artifact artifact : process.allArtifacts()) {
                if (artifact instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.Association a
                        && nodes.get(a.sourceRef()) instanceof io.github.dhluong90.bpm.process.internal.model.bpmn
                                .BoundaryEvent b
                        && b.eventDefinitions().size() == 1
                        && b.eventDefinitions().get(0) instanceof
                                io.github.dhluong90.bpm.process.internal.model.bpmn.CompensateEventDefinition
                        && b.attachedToRef() != null && nodes.containsKey(a.targetRef())) {
                    compensationActivities.putIfAbsent(b.attachedToRef(), a.targetRef());
                }
            }
        }

        private void index(String containerId, List<io.github.dhluong90.bpm.process.internal.model.bpmn.FlowElement>
                elements) {
            elements.forEach(element -> {
                if (element instanceof FlowNode node) {
                    nodes.put(node.id(), node);
                    containers.put(node.id(), containerId);
                    if (node instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.BoundaryEvent boundary
                            && boundary.attachedToRef() != null) {
                        boundaries.computeIfAbsent(boundary.attachedToRef(), k -> new ArrayList<>()).add(boundary);
                    }
                    if (node instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.FlowElementsContainer c) {
                        index(node.id(), c.flowElements());
                    }
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
