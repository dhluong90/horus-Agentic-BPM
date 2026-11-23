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
    private final Map<String, List<String>> compensationChains;
    private final Map<String, List<io.github.dhluong90.bpm.process.internal.model.bpmn.SubProcess>> eventSubProcesses;
    private final Map<String, io.github.dhluong90.bpm.process.internal.model.bpmn.EventBasedGateway> gatewayOfCatch;

    private CompiledProcess(Builder builder) {
        this.compensationActivities = Map.copyOf(builder.compensationActivities);
        this.compensationChains = Map.copyOf(builder.compensationChains);
        Map<String, List<io.github.dhluong90.bpm.process.internal.model.bpmn.SubProcess>> events = new HashMap<>();
        builder.eventSubProcesses.forEach((k, v) -> events.put(k, List.copyOf(v)));
        this.eventSubProcesses = Collections.unmodifiableMap(events);
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
        Map<String, io.github.dhluong90.bpm.process.internal.model.bpmn.EventBasedGateway> byCatch = new HashMap<>();
        builder.nodes.values().forEach(n -> {
            if (n instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.EventBasedGateway gateway) {
                out.getOrDefault(gateway.id(), List.of()).forEach(f -> byCatch.put(f.targetRef(), gateway));
            }
        });
        this.gatewayOfCatch = Map.copyOf(byCatch);
    }

    /**
     * @param nodeId a node
     * @return the event-based gateway that has a flow to the node (#495), when it is one of the catches the gateway
     *     waits on; a catch event of a gateway has no other incoming flow
     */
    public Optional<io.github.dhluong90.bpm.process.internal.model.bpmn.EventBasedGateway> eventGatewayOf(String nodeId) {
        return Optional.ofNullable(gatewayOfCatch.get(nodeId));
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
     * The undo tasks of a sub-process or transaction in the order they run (#499): the service tasks of its
     * compensation event sub-process, in flow order from the start event; or the one compensation activity of a
     * compensation boundary event.
     *
     * @param activityId the id of the sub-process, transaction or step
     * @return the undo tasks, empty when it has none
     */
    public List<FlowNode> compensationChainOf(String activityId) {
        List<String> chain = compensationChains.get(activityId);
        if (chain == null) {
            return compensationActivityOf(activityId).map(List::of).orElse(List.of());
        }
        return chain.stream().map(nodes::get).toList();
    }

    /**
     * @param containerId the process id, or the id of a sub-process or transaction
     * @return the event sub-processes (#487) that stand directly in that container, in document order
     */
    public List<io.github.dhluong90.bpm.process.internal.model.bpmn.SubProcess> eventSubProcessesOf(String containerId) {
        return eventSubProcesses.getOrDefault(containerId, List.of());
    }

    /**
     * @param nodeId a node id
     * @return whether it is an event sub-process (#487)
     */
    public boolean isEventSubProcess(String nodeId) {
        return nodes.get(nodeId) instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.SubProcess s
                && s.triggeredByEvent();
    }

    /**
     * @param nodeId a node id
     * @return the ad-hoc sub-process (#500) with that id, if it is one
     */
    public Optional<io.github.dhluong90.bpm.process.internal.model.bpmn.AdHocSubProcess> adHoc(String nodeId) {
        return nodeId != null && nodes.get(nodeId) instanceof io.github.dhluong90.bpm.process.internal.model.bpmn
                .AdHocSubProcess a ? Optional.of(a) : Optional.empty();
    }

    /**
     * @param eventSubProcessId the id of an event sub-process
     * @return its start event, the one that carries the trigger
     */
    public Optional<io.github.dhluong90.bpm.process.internal.model.bpmn.StartEvent> triggerOf(String eventSubProcessId) {
        return nodes.values().stream()
                .filter(n -> n instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.StartEvent
                        && containerOf(n.id()).equals(eventSubProcessId))
                .map(n -> (io.github.dhluong90.bpm.process.internal.model.bpmn.StartEvent) n).findFirst();
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
        private final Map<String, List<io.github.dhluong90.bpm.process.internal.model.bpmn.SubProcess>> eventSubProcesses =
                new HashMap<>();
        private final Map<String, String> compensationActivities = new HashMap<>();
        private final Map<String, List<String>> compensationChains = new HashMap<>();

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
            // #487: a compensation event sub-process names the service task inside it as the undo of its sub-process.
            for (io.github.dhluong90.bpm.process.internal.model.bpmn.SubProcess esp
                    : eventSubProcesses.values().stream().flatMap(List::stream).toList()) {
                boolean compensates = nodes.values().stream().anyMatch(n -> n instanceof io.github.dhluong90.bpm.process
                        .internal.model.bpmn.StartEvent s && containers.get(n.id()).equals(esp.id())
                        && s.eventDefinitions().size() == 1 && s.eventDefinitions().get(0) instanceof
                                io.github.dhluong90.bpm.process.internal.model.bpmn.CompensateEventDefinition);
                if (compensates) {
                    // #499: the undo is the service tasks in flow order from the start event; a validated one is a line.
                    List<String> chain = new ArrayList<>();
                    nodes.values().stream().filter(n -> n instanceof io.github.dhluong90.bpm.process.internal.model.bpmn
                            .StartEvent && containers.get(n.id()).equals(esp.id())).findFirst().ifPresent(start -> {
                        String at = start.id();
                        while (chain.size() < nodes.size()) {
                            List<SequenceFlow> next = outgoing.getOrDefault(at, List.of());
                            if (next.size() != 1 || !(nodes.get(next.get(0).targetRef()) instanceof
                                    io.github.dhluong90.bpm.process.internal.model.bpmn.ServiceTask)) {
                                break;
                            }
                            at = next.get(0).targetRef();
                            chain.add(at);
                        }
                    });
                    if (!chain.isEmpty()) {
                        compensationChains.putIfAbsent(containers.get(esp.id()), List.copyOf(chain));
                        compensationActivities.putIfAbsent(containers.get(esp.id()), chain.get(0));
                    }
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
                    if (node instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.SubProcess sub
                            && sub.triggeredByEvent()) {
                        eventSubProcesses.computeIfAbsent(containerId, k -> new ArrayList<>()).add(sub);
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
