package io.github.dhluong90.bpm.process.internal.exec;

import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.ValidationFailedException;
import io.github.dhluong90.bpm.process.internal.expr.ConditionException;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Activity;
import io.github.dhluong90.bpm.process.internal.model.bpmn.AdHocSubProcess;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Association;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ComplexGateway;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ConditionalEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SignalEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.CancelEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ServiceTask;
import io.github.dhluong90.bpm.process.internal.model.horus.CompensateHandlerRef;
import io.github.dhluong90.bpm.process.internal.model.bpmn.BoundaryEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.CompensateEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ErrorEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.IntermediateCatchEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EndEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EventBasedGateway;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EventBasedGatewayType;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ExclusiveGateway;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowElement;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowElementsContainer;
import io.github.dhluong90.bpm.process.internal.model.bpmn.IntermediateThrowEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ThrowEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import io.github.dhluong90.bpm.process.internal.model.bpmn.CallActivity;
import io.github.dhluong90.bpm.process.internal.model.horus.CorrelationKey;
import io.github.dhluong90.bpm.process.internal.model.horus.HandlerRef;
import io.github.dhluong90.bpm.process.internal.model.bpmn.TimerEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.MessageEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EscalationEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SequenceFlow;
import io.github.dhluong90.bpm.process.internal.model.bpmn.StartEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SubProcess;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Transaction;
import io.github.dhluong90.bpm.process.internal.model.bpmn.UserTask;
import io.github.dhluong90.bpm.process.internal.model.horus.ResultVariable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Validates a process as an executable graph and compiles it: one none start event, at least one end event, flows
 * between existing nodes, every node reachable from the start and able to reach an end, default flows that are
 * outgoing flows of their gateway, conditions on every non-default flow of a diverging exclusive gateway and on no
 * outgoing flow of a parallel gateway, a registered behaviour for every node and its event definition
 * ({@code BPMN_NOT_EXECUTABLE}), a result variable on every user task, and every compile step of the registry.
 */
public final class ProcessCompiler {

    private static final Pattern NODE_ID = Pattern.compile("^[A-Za-z_][A-Za-z0-9_.-]{0,99}$");

    private final BehaviorRegistry behaviors;
    private final int maxNodes;

    /**
     * @param behaviors registered behaviours and compile steps
     * @param maxNodes most flow nodes accepted ({@code definitions.max-nodes})
     */
    public ProcessCompiler(BehaviorRegistry behaviors, int maxNodes) {
        this.behaviors = behaviors;
        this.maxNodes = maxNodes;
    }

    /**
     * @param definitionId the definition version id the result is bound to
     * @param process the process
     * @return the compiled process
     * @throws ValidationFailedException listing every problem
     */
    public CompiledProcess compile(String definitionId, Process process) {
        List<DefinitionProblem> problems = new ArrayList<>();
        // Every node and flow, those of saga blocks included (#67), and the container each stands in.
        Map<String, String> containerOf = new HashMap<>();
        List<FlowElement> all = new ArrayList<>();
        collect(process.id(), process.flowElements(), all, containerOf);
        Map<String, FlowNode> nodes = all.stream().filter(FlowNode.class::isInstance)
                .map(FlowNode.class::cast).collect(Collectors.toMap(FlowNode::id, Function.identity(), (a, b) -> {
                    problems.add(DefinitionProblem.of("DUPLICATE_ID", "Duplicate id", a.id()));
                    return a;
                }, HashMap::new));
        List<SequenceFlow> allFlows = all.stream().filter(SequenceFlow.class::isInstance)
                .map(SequenceFlow.class::cast).toList();
        // Flows with a missing end are reported below and left out of the graph checks.
        List<SequenceFlow> flows = allFlows.stream().filter(f -> f.sourceRef() != null && f.targetRef() != null).toList();
        if (nodes.size() > maxNodes) {
            problems.add(DefinitionProblem.of("TOO_MANY_NODES", nodes.size() + " flow nodes; at most " + maxNodes, null));
        }
        for (FlowElement element : all) {
            if (element.id() == null || !NODE_ID.matcher(element.id()).matches()) {
                problems.add(DefinitionProblem.of("ID_INVALID", "An id must start with a letter or underscore and have at"
                        + " most 100 letters, digits, '_', '.' or '-'", element.id()));
            }
        }
        List<String> containerIds = new ArrayList<>();
        containerIds.add(process.id());
        nodes.values().stream().filter(n -> n instanceof Transaction || n instanceof SubProcess)
                .map(FlowNode::id).forEach(containerIds::add);
        Map<String, FlowNode> startOf = new HashMap<>();
        for (String container : containerIds) {
            boolean root = container.equals(process.id());
            // #499: the root may start on an instantiating event-based gateway, which stands for its start event.
            List<FlowNode> starts = nodes.values().stream().filter(n -> n instanceof StartEvent
                            || root && n instanceof EventBasedGateway g && g.instantiate())
                    .filter(n -> container.equals(containerOf.get(n.id()))).toList();
            if (starts.size() != 1) {
                problems.add(DefinitionProblem.of("START_EVENT_COUNT", kind(root, nodes.get(container))
                        + " needs exactly one start event, found " + starts.size(), container));
            } else {
                startOf.put(container, starts.get(0));
            }
            if (nodes.values().stream().noneMatch(n -> n instanceof EndEvent
                    && container.equals(containerOf.get(n.id())))) {
                problems.add(DefinitionProblem.of("NO_END_EVENT", kind(root, nodes.get(container))
                        + " needs at least one end event", container));
            }
        }
        for (SequenceFlow flow : allFlows) {
            if (flow.sourceRef() == null || flow.targetRef() == null
                    || !nodes.containsKey(flow.sourceRef()) || !nodes.containsKey(flow.targetRef())) {
                problems.add(DefinitionProblem.of("FLOW_REF_INVALID", "Sequence flow refers to a missing node "
                        + flow.sourceRef() + " -> " + flow.targetRef(), flow.id()));
            } else if (!containerOf.get(flow.id()).equals(containerOf.get(flow.sourceRef()))
                    || !containerOf.get(flow.id()).equals(containerOf.get(flow.targetRef()))) {
                // A flow never crosses a scope boundary: a block is entered and left through its own node.
                problems.add(DefinitionProblem.of("FLOW_CROSSES_SAGA", "Sequence flow " + flow.sourceRef() + " -> "
                        + flow.targetRef() + " crosses the boundary of a saga block or sub-process; connect the block"
                        + " itself",
                        flow.id()));
            }
            if (flow.conditionExpression() != null && flow.conditionExpression().text() != null) {
                try {
                    io.github.dhluong90.bpm.process.internal.expr.Condition.parse(flow.conditionExpression().text(),
                            flow.conditionExpression().language());
                } catch (ConditionException e) {
                    problems.add(DefinitionProblem.of("CONDITION_INVALID", e.getMessage(), flow.id()));
                }
            }
        }
        List<Association> associations = process.allArtifacts().stream().filter(Association.class::isInstance)
                .map(Association.class::cast).toList();
        for (FlowNode node : nodes.values()) {
            List<SequenceFlow> out = flows.stream().filter(f -> f.sourceRef().equals(node.id())).toList();
            compensationProblems(node, out, nodes, containerOf, flows, associations, problems);
            if (!(node instanceof EndEvent) && !throwsCompensation(node) && !isLinkThrow(node) && out.isEmpty()
                    && !(nodes.get(containerOf.get(node.id())) instanceof AdHocSubProcess)
                    && !isCompensationBoundary(node) && !isForCompensation(node) && !isEventSubProcess(node)) {
                problems.add(DefinitionProblem.of("NO_OUTGOING_FLOW", "Only an end event may have no outgoing flow",
                        node.id()));
            }
            if (throwsCompensation(node) && out.isEmpty()
                    && !(nodes.get(containerOf.get(node.id())) instanceof Transaction)) {
                problems.add(DefinitionProblem.of("THROW_COMPENSATION_OUTSIDE_SAGA", "throwCompensation undoes the"
                        + " steps of its saga block; put it inside a transaction", node.id()));
            }
            if (node instanceof BoundaryEvent boundary) {
                FlowNode host = boundary.attachedToRef() == null ? null : nodes.get(boundary.attachedToRef());
                if (host != null && isEventSubProcess(host)) {
                    problems.add(DefinitionProblem.of("BOUNDARY_ATTACHED_INVALID", "A boundary event is not attached to an"
                            + " event sub-process; it has no flow in", node.id()));
                } else if (!(host instanceof Activity)) {
                    problems.add(DefinitionProblem.of("BOUNDARY_ATTACHED_INVALID", "A boundary event is attached to a task,"
                            + " a call activity or a sub-process of its own scope; " + boundary.attachedToRef()
                            + " is not one", node.id()));
                } else if (!containerOf.get(host.id()).equals(containerOf.get(boundary.id()))) {
                    problems.add(DefinitionProblem.of("BOUNDARY_ATTACHED_INVALID", "A boundary event stands in the same"
                            + " scope as the activity it is attached to", node.id()));
                }
                if (flows.stream().anyMatch(f -> f.targetRef().equals(boundary.id()))) {
                    problems.add(DefinitionProblem.of("BOUNDARY_HAS_INCOMING", "A boundary event is entered by its"
                            + " activity, not by a sequence flow", node.id()));
                }
                if (!boundary.cancelActivity() && boundary.eventDefinitions().size() == 1
                        && boundary.eventDefinitions().get(0) instanceof ErrorEventDefinition) {
                    problems.add(DefinitionProblem.of("BOUNDARY_ERROR_NOT_INTERRUPTING", "An error boundary event always"
                            + " cancels its activity; remove cancelActivity=\"false\"", node.id()));
                }
            }
            if (node instanceof EventBasedGateway gateway) {
                // #495: a gateway waits on two or more catch events; each is entered by this gateway alone, and no flow has a condition.
                if (gateway.eventGatewayType() == EventBasedGatewayType.PARALLEL && !gateway.instantiate()) {
                    problems.add(DefinitionProblem.of("EVENT_GATEWAY_PARALLEL_INSTANTIATE", "A parallel event-based"
                            + " gateway starts the process; set instantiate=\"true\"", node.id()));
                }
                if (gateway.instantiate()) {
                    if (!containerOf.get(node.id()).equals(process.id())) {
                        problems.add(DefinitionProblem.of("EVENT_GATEWAY_INSTANTIATE_SCOPE", "An instantiating event-based"
                                + " gateway starts the process; it stands in the process, not in a sub-process or"
                                + " transaction", node.id()));
                    }
                    if (flows.stream().anyMatch(f -> f.targetRef().equals(node.id()))) {
                        problems.add(DefinitionProblem.of("EVENT_GATEWAY_INSTANTIATE_INCOMING", "An instantiating"
                                + " event-based gateway starts the process and has no incoming flow", node.id()));
                    }
                    for (SequenceFlow f : out) {
                        FlowNode target = nodes.get(f.targetRef());
                        if (target != null && waitsForEvent(target) && !startsInstance(target)) {
                            problems.add(DefinitionProblem.of("EVENT_GATEWAY_INSTANTIATE_TARGET", "An instantiating"
                                    + " event-based gateway leads to message or signal catch events or receive tasks; a"
                                    + " timer or conditional catch cannot create an instance; " + target.id() + " is one",
                                    f.id()));
                        }
                    }
                }
                if (out.size() < 2) {
                    problems.add(DefinitionProblem.of("EVENT_GATEWAY_BRANCHES", "An event-based gateway waits on at"
                            + " least two catch events; it has " + out.size(), node.id()));
                }
                for (SequenceFlow f : out) {
                    FlowNode target = nodes.get(f.targetRef());
                    if (f.conditionExpression() != null) {
                        problems.add(DefinitionProblem.of("EVENT_GATEWAY_FLOW_CONDITION", "A flow of an event-based"
                                + " gateway has no condition; the event decides", f.id()));
                    }
                    if (target != null && !waitsForEvent(target)) {
                        problems.add(DefinitionProblem.of("EVENT_GATEWAY_TARGET_INVALID", "An event-based gateway leads"
                                + " to a message, signal, timer or conditional catch event or a receive task; "
                                + target.id() + " is not one", f.id()));
                    } else if (target != null && flows.stream().filter(x -> x.targetRef().equals(target.id())).count() > 1) {
                        problems.add(DefinitionProblem.of("EVENT_GATEWAY_TARGET_SHARED", "A catch event after an"
                                + " event-based gateway has no other incoming flow", target.id()));
                    }
                }
            }
            if (isLinkThrow(node)) {
                String name = CompiledProcess.linkName(node).orElse(null);
                if (name == null || name.isBlank()) {
                    problems.add(DefinitionProblem.of("LINK_NAME_REQUIRED", "A link event needs a name", node.id()));
                } else if (nodes.values().stream().noneMatch(n -> n instanceof IntermediateCatchEvent
                        && CompiledProcess.linkName(n).filter(name::equals).isPresent()
                        && containerOf.get(n.id()).equals(containerOf.get(node.id())))) {
                    problems.add(DefinitionProblem.of("LINK_CATCH_MISSING", "The link throw '" + name + "' has no link"
                            + " catch of the same name in its scope", node.id()));
                }
                if (!out.isEmpty()) {
                    problems.add(DefinitionProblem.of("LINK_THROW_HAS_OUTGOING", "A link throw leaves by its link, not by a"
                            + " sequence flow", node.id()));
                }
            }
            if (node instanceof IntermediateCatchEvent && CompiledProcess.linkName(node).isPresent()) {
                String name = CompiledProcess.linkName(node).get();
                boolean duplicate = nodes.values().stream().anyMatch(n -> n != node
                        && n instanceof IntermediateCatchEvent && CompiledProcess.linkName(n).filter(name::equals).isPresent()
                        && containerOf.get(n.id()).equals(containerOf.get(node.id()))
                        && n.id().compareTo(node.id()) < 0);
                if (duplicate) {
                    problems.add(DefinitionProblem.of("LINK_CATCH_DUPLICATE", "Two link catches named '" + name
                            + "' stand in one scope; a link name has one catch", node.id()));
                }
            }
            if (node instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.InclusiveGateway inclusive) {
                // Unconditional flows of an inclusive split are always taken (BPMN); only the default is checked.
                out.stream().filter(f -> f.id().equals(inclusive.defaultFlow()) && f.conditionExpression() != null)
                        .forEach(f -> problems.add(DefinitionProblem.of("DEFAULT_FLOW_HAS_CONDITION",
                                "A default flow has no condition in BPMN; remove it or the default", f.id())));
                if (inclusive.defaultFlow() != null
                        && out.stream().noneMatch(f -> f.id().equals(inclusive.defaultFlow()))) {
                    problems.add(DefinitionProblem.of("DEFAULT_FLOW_INVALID", "The default flow "
                            + inclusive.defaultFlow() + " is not an outgoing flow of the gateway", node.id()));
                }
            }
            if (node instanceof ExclusiveGateway gateway) {
                out.stream().filter(f -> f.id().equals(gateway.defaultFlow()) && f.conditionExpression() != null)
                        .forEach(f -> problems.add(DefinitionProblem.of("DEFAULT_FLOW_HAS_CONDITION",
                                "A default flow has no condition in BPMN; remove it or the default", f.id())));
                if (gateway.defaultFlow() != null && out.stream().noneMatch(f -> f.id().equals(gateway.defaultFlow()))) {
                    problems.add(DefinitionProblem.of("DEFAULT_FLOW_INVALID", "The default flow " + gateway.defaultFlow()
                            + " is not an outgoing flow of the gateway", node.id()));
                }
                if (out.size() > 1) {
                    for (SequenceFlow f : out) {
                        // A condition with no text is no condition: the kernel must never evaluate an empty one.
                        if (!f.id().equals(gateway.defaultFlow()) && (f.conditionExpression() == null
                                || f.conditionExpression().text() == null)) {
                            problems.add(DefinitionProblem.of("CONDITION_MISSING", "Every non-default outgoing flow of"
                                    + " a diverging exclusive gateway needs a condition", f.id()));
                        }
                    }
                }
            }
            LoopChecks.check(node, problems);
            eventSubProcessProblems(node, flows, nodes, containerOf, process, problems);
            complexAndMultipleProblems(node, out, nodes, containerOf, flows, process, problems);
            if (node instanceof UserTask task && task.extensions().first(ResultVariable.class).isEmpty()) {
                problems.add(DefinitionProblem.of("RESULT_VARIABLE_REQUIRED", "A userTask needs horus:resultVariable, so"
                        + " a completion result can never overwrite other process variables", node.id()));
            }
            if (node instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.ParallelGateway) {
                // BPMN ignores conditions on a parallel gateway's outgoing flows; refusing them avoids a silent surprise.
                out.stream().filter(f -> f.conditionExpression() != null)
                        .forEach(f -> problems.add(DefinitionProblem.of("CONDITION_ON_PARALLEL_FLOW", "A parallel"
                                + " gateway takes every outgoing flow; remove the condition or use an exclusive gateway",
                                f.id())));
            }
            if (node instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.Event event) {
                // A process carries no shared definitions, so every reference is unresolved here.
                for (String ref : event.eventDefinitionRefs()) {
                    problems.add(DefinitionProblem.of("EVENT_DEFINITION_REF_UNRESOLVED", "eventDefinitionRef '" + ref
                            + "' does not resolve to an event definition", node.id()));
                }
            }
            if (behaviors.behaviorFor(node).isEmpty()) {
                problems.add(new DefinitionProblem("BPMN_NOT_EXECUTABLE", "has no behaviour in this release",
                        node.getClass().getSimpleName(), node.id(), 0));
            }
        }
        if (problems.isEmpty()) {
            for (String container : containerIds) {
                Map<String, FlowNode> inside = new java.util.LinkedHashMap<>();
                nodes.values().stream().filter(n -> container.equals(containerOf.get(n.id())))
                        .forEach(n -> inside.put(n.id(), n));
                List<SequenceFlow> edges = new ArrayList<>(flows.stream()
                        .filter(f -> container.equals(containerOf.get(f.id()))).toList());
                // A boundary event is reached from its activity and a link catch from its link throw (#489, #493).
                // A compensation boundary and its compensation activity are reached by no token, only by the walk (#488).
                // An event sub-process is started by its trigger, not by a flow (#487).
                inside.values().removeIf(n -> isCompensationBoundary(n) || isForCompensation(n)
                        || isEventSubProcess(n));
                for (FlowNode n : List.copyOf(inside.values())) {
                    if (n instanceof BoundaryEvent b && b.attachedToRef() != null) {
                        edges.add(new SequenceFlow("boundary:" + b.id(), null, b.attachedToRef(), b.id(), null, null, null));
                    } else if (isLinkThrow(n)) {
                        CompiledProcess.linkName(n).flatMap(name -> inside.values().stream().filter(c ->
                                c instanceof IntermediateCatchEvent && CompiledProcess.linkName(c)
                                        .filter(name::equals).isPresent()).findFirst())
                                .ifPresent(c -> edges.add(new SequenceFlow("link:" + n.id(), null, n.id(), c.id(), null,
                                        null, null)));
                    }
                }
                reachability(startOf.get(container), inside, edges, problems);
            }
        }
        if (!problems.isEmpty()) {
            throw new ValidationFailedException(problems);
        }
        CompiledProcess.Builder builder = CompiledProcess.builder(definitionId, process);
        for (CompileStep step : behaviors.compileSteps()) {
            step.compile(process, builder);
        }
        return builder.build();
    }

    /**
     * The rules of #500. A complex gateway has a valid default flow and activation condition. An ad-hoc sub-process
     * holds activities and nothing else, with no sequence flow between them. A multiple event has two or more
     * definitions of the kinds that can wait or be thrown to it, and a parallel multiple one only where all of them can
     * be waited for.
     */
    private static void complexAndMultipleProblems(FlowNode node, List<SequenceFlow> out, Map<String, FlowNode> nodes,
            Map<String, String> containerOf, List<SequenceFlow> flows, Process process, List<DefinitionProblem> problems) {
        if (node instanceof ComplexGateway gateway) {
            if (gateway.defaultFlow() != null && out.stream().noneMatch(f -> f.id().equals(gateway.defaultFlow()))) {
                problems.add(DefinitionProblem.of("DEFAULT_FLOW_INVALID", "The default flow " + gateway.defaultFlow()
                        + " is not an outgoing flow of the gateway", node.id()));
            }
            out.stream().filter(f -> f.id().equals(gateway.defaultFlow()) && f.conditionExpression() != null)
                    .forEach(f -> problems.add(DefinitionProblem.of("DEFAULT_FLOW_HAS_CONDITION",
                            "A default flow has no condition in BPMN; remove it or the default", f.id())));
            if (gateway.activationCondition() != null && gateway.activationCondition().text() != null) {
                try {
                    io.github.dhluong90.bpm.process.internal.expr.Condition.parse(gateway.activationCondition().text(),
                            gateway.activationCondition().language());
                } catch (ConditionException e) {
                    problems.add(DefinitionProblem.of("CONDITION_INVALID", e.getMessage(), node.id()));
                }
            }
        }
        if (node instanceof AdHocSubProcess adHoc) {
            if (adHoc.completionCondition() != null && adHoc.completionCondition().text() != null) {
                try {
                    io.github.dhluong90.bpm.process.internal.expr.Condition.parse(adHoc.completionCondition().text(),
                            adHoc.completionCondition().language());
                } catch (ConditionException e) {
                    problems.add(DefinitionProblem.of("CONDITION_INVALID", e.getMessage(), node.id()));
                }
            }
            if (adHoc.flowElements().stream().noneMatch(e -> e instanceof Activity)) {
                problems.add(DefinitionProblem.of("AD_HOC_EMPTY", "An ad-hoc sub-process holds at least one activity",
                        node.id()));
            }
            for (FlowElement e : adHoc.flowElements()) {
                if (e instanceof SequenceFlow) {
                    problems.add(DefinitionProblem.of("AD_HOC_HAS_FLOW", "The activities of an ad-hoc sub-process have no"
                            + " sequence flow between them; the host starts each one", e.id()));
                } else if (!(e instanceof Activity) || isEventSubProcess((FlowNode) e)) {
                    problems.add(DefinitionProblem.of("AD_HOC_CONTENT_INVALID", "An ad-hoc sub-process holds activities"
                            + " only: no events, gateways or event sub-processes", e.id()));
                }
            }
        }
        if (node instanceof BoundaryEvent boundary && nodes.get(boundary.attachedToRef()) != null
                && nodes.get(containerOf.get(boundary.attachedToRef())) instanceof AdHocSubProcess) {
            problems.add(DefinitionProblem.of("AD_HOC_CONTENT_INVALID", "A boundary event is not placed on an activity of an"
                    + " ad-hoc sub-process; put it on the ad-hoc sub-process", node.id()));
        }
        if (node instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.Event event) {
            List<EventDefinition> defs = event.eventDefinitions();
            boolean parallel = node instanceof StartEvent s ? s.parallelMultiple()
                    : node instanceof IntermediateCatchEvent c ? c.parallelMultiple()
                    : node instanceof BoundaryEvent b && b.parallelMultiple();
            if (defs.size() < 2) {
                if (parallel) {
                    problems.add(DefinitionProblem.of("PARALLEL_MULTIPLE_SINGLE", "parallelMultiple applies to an event"
                            + " with several event definitions", node.id()));
                }
                for (EventDefinition d : defs) {
                    if (d instanceof TimerEventDefinition t && t.timeCycle() != null && !(node instanceof StartEvent s
                            && isEventSubProcess(nodes.get(containerOf.get(s.id()))))) {
                        problems.add(DefinitionProblem.of("TIMER_CYCLE_MISPLACED", "A timeCycle starts an event"
                                + " sub-process; a timer catch or boundary event uses timeDuration or timeDate",
                                node.id()));
                    }
                }
                return;
            }
            if (node instanceof ThrowEvent) {
                for (EventDefinition d : defs) {
                    boolean fits = d instanceof MessageEventDefinition || d instanceof SignalEventDefinition
                            || d instanceof EscalationEventDefinition
                            || d instanceof CompensateEventDefinition c && !c.waitForCompletion();
                    if (!fits) {
                        problems.add(DefinitionProblem.of("MULTIPLE_THROW_DEFINITION_INVALID", "A throw or end event with"
                                + " several definitions throws each of them and goes on: message, signal, escalation and"
                                + " a compensation with waitForCompletion=\"false\" only (an error, terminate, cancel,"
                                + " link or waiting compensation changes the flow); found " + d.getClass().getSimpleName(),
                                node.id()));
                        break;
                    }
                }
                return;
            }
            boolean catches = node instanceof StartEvent || node instanceof IntermediateCatchEvent;
            boolean inEvent = node instanceof StartEvent s && isEventSubProcess(nodes.get(containerOf.get(s.id())));
            boolean root = node instanceof StartEvent s && containerOf.get(s.id()).equals(process.id());
            long messages = defs.stream().filter(MessageEventDefinition.class::isInstance).count();
            for (EventDefinition d : defs) {
                boolean fits;
                if (node instanceof BoundaryEvent) {
                    fits = d instanceof TimerEventDefinition || !parallel && (d instanceof ErrorEventDefinition
                            || d instanceof EscalationEventDefinition);
                } else if (root) {
                    fits = d instanceof MessageEventDefinition || d instanceof SignalEventDefinition;
                } else if (catches) {
                    fits = d instanceof TimerEventDefinition || d instanceof MessageEventDefinition
                            || d instanceof SignalEventDefinition || d instanceof ConditionalEventDefinition;
                } else {
                    fits = false;
                }
                if (d instanceof TimerEventDefinition t && t.timeCycle() != null) {
                    fits = false;
                }
                if (!fits) {
                    problems.add(DefinitionProblem.of("MULTIPLE_EVENT_DEFINITION_INVALID", (root
                            ? "A process start event with several triggers holds message and signal definitions only"
                            + " (with parallelMultiple the instance starts when all have arrived)"
                            : "A multiple event holds timer, message, signal or conditional definitions (a start event of"
                            + " a process, message and signal only; a boundary event, timer, error and escalation, and"
                            + " with parallelMultiple timers only), and no timeCycle") + "; found "
                            + d.getClass().getSimpleName(), node.id()));
                    break;
                }
            }
            if (messages > 1) {
                problems.add(DefinitionProblem.of("MULTIPLE_EVENT_MESSAGE_ONCE", "A multiple event waits for one message:"
                        + " its correlation key and outputs belong to the event", node.id()));
            }
            if (inEvent || catches && !root) {
                // Conditions and timers are fine; nothing more to check here.
                return;
            }
            if (node instanceof BoundaryEvent b && !b.cancelActivity() && defs.stream()
                    .anyMatch(ErrorEventDefinition.class::isInstance)) {
                problems.add(DefinitionProblem.of("BOUNDARY_ERROR_NOT_INTERRUPTING", "An error boundary event always"
                        + " cancels its activity; remove cancelActivity=\"false\"", node.id()));
            }
        }
    }

    private static boolean isEventSubProcess(FlowNode node) {
        return node instanceof SubProcess s && s.triggeredByEvent();
    }

    /**
     * The rules of #487 for event sub-processes and for the start events of every scope. An event sub-process has no
     * flow in or out and one start event with exactly one trigger: timer, message, signal, conditional, error,
     * escalation or compensation. A message trigger names its correlation key, as a message catch does; an error
     * trigger interrupts; a compensation trigger stands in a sub-process or a transaction. A trigger on any other start
     * event is refused: a process starts on a none, message, signal or conditional start event, and a sub-process on a
     * none one.
     */
    private static void eventSubProcessProblems(FlowNode node, List<SequenceFlow> flows, Map<String, FlowNode> nodes,
            Map<String, String> containerOf, Process process, List<DefinitionProblem> problems) {
        if (isEventSubProcess(node)) {
            if (flows.stream().anyMatch(f -> f.sourceRef().equals(node.id()) || f.targetRef().equals(node.id()))) {
                problems.add(DefinitionProblem.of("EVENT_SUBPROCESS_HAS_FLOW", "An event sub-process is started by its"
                        + " trigger; no sequence flow enters or leaves it", node.id()));
            }
            return;
        }
        if (!(node instanceof StartEvent start)) {
            return;
        }
        String container = containerOf.get(start.id());
        boolean root = container.equals(process.id());
        FlowNode owner = nodes.get(container);
        boolean inEvent = owner != null && isEventSubProcess(owner);
        EventDefinition trigger = start.eventDefinitions().size() == 1 ? start.eventDefinitions().get(0) : null;
        boolean hasMessage = start.eventDefinitions().stream().anyMatch(MessageEventDefinition.class::isInstance);
        boolean eventOnly = trigger instanceof TimerEventDefinition || trigger instanceof ErrorEventDefinition
                || trigger instanceof EscalationEventDefinition || trigger instanceof CompensateEventDefinition;
        if (inEvent) {
            if (start.eventDefinitions().isEmpty()) {
                problems.add(DefinitionProblem.of("EVENT_SUBPROCESS_TRIGGER_REQUIRED", "The start event of an event"
                        + " sub-process carries its trigger: a timer, message, signal, conditional, error, escalation"
                        + " or compensation", start.id()));
            }
            if (hasMessage && start.extensions().first(CorrelationKey.class).isEmpty()) {
                problems.add(DefinitionProblem.of("CORRELATION_KEY_REQUIRED", "A message start event of an event"
                        + " sub-process needs horus:correlationKey, the variable whose value the message must carry",
                        start.id()));
            }
            if (trigger instanceof ErrorEventDefinition && !start.isInterrupting()) {
                problems.add(DefinitionProblem.of("EVENT_SUBPROCESS_ERROR_NOT_INTERRUPTING", "An error event"
                        + " sub-process always interrupts its scope; remove isInterrupting=\"false\"", start.id()));
            }
            if (trigger instanceof CompensateEventDefinition) {
                FlowNode scope = nodes.get(containerOf.get(owner.id()));
                if (!(scope instanceof Transaction) && !(scope instanceof SubProcess s && !s.triggeredByEvent())) {
                    problems.add(DefinitionProblem.of("COMPENSATION_HANDLER_SCOPE", "A compensation event sub-process"
                            + " is the undo of the sub-process or transaction it stands in", start.id()));
                } else if (nodes.values().stream().anyMatch(n -> isCompensationBoundary(n)
                        && scope.id().equals(((BoundaryEvent) n).attachedToRef()))
                        || nodes.values().stream().anyMatch(n -> n != owner && isEventSubProcess(n)
                                && containerOf.get(n.id()).equals(containerOf.get(owner.id()))
                                && n.id().compareTo(owner.id()) < 0
                                && nodes.values().stream().anyMatch(m -> m instanceof StartEvent t
                                        && containerOf.get(m.id()).equals(n.id()) && t.eventDefinitions().size() == 1
                                        && t.eventDefinitions().get(0) instanceof CompensateEventDefinition))) {
                    problems.add(DefinitionProblem.of("COMPENSATION_DUPLICATE", "Scope " + scope.id() + " has one undo:"
                            + " a compensation boundary event or one compensation event sub-process", start.id()));
                }
                if (!undoIsALine(owner, start, nodes, containerOf, flows)) {
                    problems.add(DefinitionProblem.of("COMPENSATION_EVENT_SUBPROCESS_SHAPE", "A compensation event"
                            + " sub-process holds one or more service tasks with horus:handler in a line, the undo,"
                            + " between its start and end events", owner.id()));
                }
            }
            return;
        }
        if (eventOnly) {
            problems.add(DefinitionProblem.of("START_TRIGGER_MISPLACED", "A timer, error, escalation or compensation"
                    + " start event starts an event sub-process; a process starts on a none, message, signal or"
                    + " conditional start event", start.id()));
        } else if (!root && !start.eventDefinitions().isEmpty()) {
            problems.add(DefinitionProblem.of("START_TRIGGER_MISPLACED", "A sub-process starts on a none start event;"
                    + " a message, signal or conditional start event starts a process or an event sub-process",
                    start.id()));
        }
        if (!start.isInterrupting()) {
            problems.add(DefinitionProblem.of("START_TRIGGER_MISPLACED", "isInterrupting=\"false\" applies to the start"
                    + " event of an event sub-process", start.id()));
        }
        if (hasMessage && start.extensions().first(CorrelationKey.class).isPresent()) {
            problems.add(DefinitionProblem.of("CORRELATION_KEY_UNEXPECTED", "A message start event has no instance"
                    + " yet; the message's key becomes the business key", start.id()));
        }
    }

    private static boolean isCompensationBoundary(FlowNode node) {
        return node instanceof BoundaryEvent b && b.eventDefinitions().size() == 1
                && b.eventDefinitions().get(0) instanceof CompensateEventDefinition;
    }

    private static boolean isForCompensation(FlowNode node) {
        return node instanceof Activity a && a.activity().isForCompensation();
    }

    /**
     * The rules of #488 for the compensation elements of one node: a compensation boundary event sits on a service
     * task and names its compensation activity by an association; a compensation activity has no flows and belongs to
     * exactly one boundary; a cancel end event stands in a transaction that has a cancel boundary event; a throw
     * that names an activity names one of its own scope.
     */
    private static void compensationProblems(FlowNode node, List<SequenceFlow> out, Map<String, FlowNode> nodes,
            Map<String, String> containerOf, List<SequenceFlow> flows, List<Association> associations,
            List<DefinitionProblem> problems) {
        String id = node.id();
        if (isForCompensation(node)) {
            if (!(node instanceof ServiceTask)) {
                problems.add(DefinitionProblem.of("COMPENSATION_ACTIVITY_INVALID", "isForCompensation applies to a"
                        + " service task: the undo of a step is a handler", id));
            }
            if (!out.isEmpty() || flows.stream().anyMatch(f -> f.targetRef().equals(id))) {
                problems.add(DefinitionProblem.of("COMPENSATION_ACTIVITY_HAS_FLOW", "A compensation activity is run by"
                        + " compensation, not by a sequence flow; remove its flows", id));
            }
            long owners = associations.stream().filter(a -> id.equals(a.targetRef())
                    && isCompensationBoundary(nodes.get(a.sourceRef()))).count();
            if (owners != 1) {
                problems.add(DefinitionProblem.of("COMPENSATION_ACTIVITY_UNATTACHED", "A compensation activity is the"
                        + " target of the association of exactly one compensation boundary event, found " + owners,
                        id));
            }
        }
        if (isCompensationBoundary(node)) {
            BoundaryEvent boundary = (BoundaryEvent) node;
            FlowNode host = nodes.get(boundary.attachedToRef());
            boolean hostable = host instanceof ServiceTask || host instanceof CallActivity
                    || host instanceof Transaction || host instanceof SubProcess s && !s.triggeredByEvent();
            if (!hostable) {
                problems.add(DefinitionProblem.of("COMPENSATION_BOUNDARY_ATTACHED_INVALID", "A compensation boundary"
                        + " event is attached to a service task, agent task, call activity, sub-process or transaction,"
                        + " whose undo it names", id));
            } else if (host.extensions().first(CompensateHandlerRef.class).isPresent()) {
                problems.add(DefinitionProblem.of("COMPENSATION_DUPLICATE", "Step " + host.id() + " has horus:compensate"
                        + " and a compensation boundary event; give it one undo", id));
            }
            if (!out.isEmpty()) {
                problems.add(DefinitionProblem.of("COMPENSATION_BOUNDARY_HAS_FLOW", "A compensation boundary event has"
                        + " an association to its compensation activity and no sequence flow", id));
            }
            List<Association> mine = associations.stream().filter(a -> id.equals(a.sourceRef())).toList();
            FlowNode target = mine.size() == 1 ? nodes.get(mine.get(0).targetRef()) : null;
            if (!(target instanceof ServiceTask) || !isForCompensation(target)
                    || !containerOf.get(target.id()).equals(containerOf.get(id))) {
                problems.add(DefinitionProblem.of("COMPENSATION_ACTIVITY_MISSING", "A compensation boundary event has"
                        + " one association to a service task marked isForCompensation in the same scope", id));
            }
            if (host != null && nodes.values().stream().anyMatch(n -> n != node && isCompensationBoundary(n)
                    && host.id().equals(((BoundaryEvent) n).attachedToRef()) && n.id().compareTo(id) < 0)) {
                problems.add(DefinitionProblem.of("COMPENSATION_DUPLICATE", "A step has one compensation boundary event",
                        id));
            }
        }
        if (node instanceof BoundaryEvent b && b.eventDefinitions().size() == 1
                && b.eventDefinitions().get(0) instanceof CancelEventDefinition) {
            if (!(nodes.get(b.attachedToRef()) instanceof Transaction)) {
                problems.add(DefinitionProblem.of("CANCEL_BOUNDARY_NOT_ON_TRANSACTION", "A cancel boundary event is"
                        + " attached to a transaction", id));
            }
            if (!b.cancelActivity()) {
                problems.add(DefinitionProblem.of("CANCEL_BOUNDARY_NOT_INTERRUPTING", "A cancel boundary event always"
                        + " leaves the transaction; remove cancelActivity=\"false\"", id));
            }
        }
        if (node instanceof EndEvent end && end.eventDefinitions().size() == 1
                && end.eventDefinitions().get(0) instanceof CancelEventDefinition) {
            FlowNode container = nodes.get(containerOf.get(id));
            if (!(container instanceof Transaction)) {
                problems.add(DefinitionProblem.of("CANCEL_END_OUTSIDE_TRANSACTION", "A cancel end event ends a"
                        + " transaction; put it directly inside one", id));
            } else if (nodes.values().stream().noneMatch(n -> n instanceof BoundaryEvent b
                    && container.id().equals(b.attachedToRef()) && b.eventDefinitions().size() == 1
                    && b.eventDefinitions().get(0) instanceof CancelEventDefinition)) {
                problems.add(DefinitionProblem.of("CANCEL_BOUNDARY_REQUIRED", "Transaction " + container.id()
                        + " has a cancel end event and no cancel boundary event for the flow to leave by", id));
            }
        }
        CompensateEventDefinition thrown = node instanceof ThrowEvent event ? event.eventDefinitions().stream()
                .filter(CompensateEventDefinition.class::isInstance).map(CompensateEventDefinition.class::cast)
                .findFirst().orElse(null) : null;
        String target = thrown == null ? null : thrown.activityRef();
        if (thrown != null && !thrown.waitForCompletion() && node instanceof IntermediateThrowEvent && out.isEmpty()) {
            problems.add(DefinitionProblem.of("COMPENSATION_BACKGROUND_NEEDS_FLOW", "A throw that does not wait for"
                    + " the undo (waitForCompletion=\"false\") lets the flow go on; give it an outgoing flow", id));
        }
        if (target != null) {
            FlowNode named = nodes.get(target);
            if (!(named instanceof Activity) || isForCompensation(named) || !containerOf.get(target)
                    .equals(containerOf.get(id))) {
                problems.add(DefinitionProblem.of("COMPENSATION_TARGET_INVALID", "activityRef '" + target + "' names an"
                        + " activity of the same scope as the throw", id));
            }
            if (node instanceof IntermediateThrowEvent && out.isEmpty()) {
                problems.add(DefinitionProblem.of("COMPENSATION_TARGET_NEEDS_FLOW", "A throw that names an activity"
                        + " goes on after the undo; give it an outgoing flow", id));
            }
        }
    }

    /**
     * #499: the undo of a compensation event sub-process is a line of service tasks, each with a handler, from its start
     * event to its end event: no gateway, no branch, no other node. The tasks run in that order.
     */
    private static boolean undoIsALine(FlowNode owner, StartEvent start, Map<String, FlowNode> nodes,
            Map<String, String> containerOf, List<SequenceFlow> flows) {
        long inside = nodes.values().stream().filter(n -> containerOf.get(n.id()).equals(owner.id())).count();
        String at = start.id();
        int tasks = 0;
        for (int guard = 0; guard <= inside; guard++) {
            String from = at;
            List<SequenceFlow> next = flows.stream().filter(f -> f.sourceRef().equals(from)).toList();
            if (next.size() != 1) {
                return false;
            }
            FlowNode target = nodes.get(next.get(0).targetRef());
            if (target instanceof EndEvent) {
                // The line ends here; every node of the sub-process must be on it: start, the tasks, one end.
                return tasks >= 1 && inside == tasks + 2L;
            }
            if (!(target instanceof ServiceTask task) || task.extensions().first(HandlerRef.class).isEmpty()) {
                return false;
            }
            tasks++;
            at = target.id();
        }
        return false;
    }

    private static void reachability(FlowNode start, Map<String, FlowNode> nodes, List<SequenceFlow> flows,
            List<DefinitionProblem> problems) {
        Set<String> forward = walk(start.id(), flows, true);
        // A throwCompensation ends its path too: the instance does not go on after it (#67).
        Set<String> ends = nodes.values().stream().filter(n -> n instanceof EndEvent || throwsCompensation(n))
                .map(FlowNode::id).collect(Collectors.toSet());
        Set<String> backward = new HashSet<>();
        ends.forEach(e -> backward.addAll(walk(e, flows, false)));
        for (String id : nodes.keySet()) {
            if (!forward.contains(id)) {
                problems.add(DefinitionProblem.of("UNREACHABLE", "Not reachable from the start event", id));
            } else if (!backward.contains(id)) {
                problems.add(DefinitionProblem.of("NO_END_REACHABLE", "No path from here reaches an end event", id));
            }
        }
    }

    /** Adds the elements of a container and of the saga blocks in it, with the container of each. */
    private static void collect(String container, List<FlowElement> elements, List<FlowElement> all,
            Map<String, String> containerOf) {
        for (FlowElement element : elements) {
            all.add(element);
            if (element.id() != null) {
                containerOf.putIfAbsent(element.id(), container);
            }
            if (element instanceof FlowElementsContainer nested) {
                collect(element.id(), nested.flowElements(), all, containerOf);
            }
        }
    }

    /** The words for a container in a problem text: the process, a saga block or an embedded sub-process. */
    private static String kind(boolean root, FlowNode container) {
        return root ? "A process" : container instanceof SubProcess ? "A sub-process" : "A saga block";
    }

    /** @return whether the node is a {@code throwCompensation}: a throw event with one compensate definition */
    /** A link throw: an intermediate throw event with one link event definition (#493). */
    static boolean isLinkThrow(FlowNode node) {
        return node instanceof IntermediateThrowEvent t && t.eventDefinitions().size() == 1
                && t.eventDefinitions().get(0) instanceof io.github.dhluong90.bpm.process.internal.model.bpmn
                        .LinkEventDefinition;
    }

    /** The catch events and tasks an event-based gateway may lead to (#495). */
    static boolean waitsForEvent(FlowNode node) {
        if (node instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.ReceiveTask) {
            return true;
        }
        return node instanceof IntermediateCatchEvent c && c.eventDefinitions().size() == 1
                && (c.eventDefinitions().get(0) instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.MessageEventDefinition
                || c.eventDefinitions().get(0) instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.SignalEventDefinition
                || c.eventDefinitions().get(0) instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.TimerEventDefinition
                || c.eventDefinitions().get(0) instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.ConditionalEventDefinition);
    }

    /** A catch that may create an instance after an instantiating event-based gateway: a message or signal one (#499). */
    static boolean startsInstance(FlowNode node) {
        return node instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.ReceiveTask
                || node instanceof IntermediateCatchEvent c && c.eventDefinitions().size() == 1
                && (c.eventDefinitions().get(0) instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.MessageEventDefinition
                || c.eventDefinitions().get(0) instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.SignalEventDefinition);
    }

    static boolean throwsCompensation(FlowNode node) {
        return node instanceof IntermediateThrowEvent t && t.eventDefinitions().size() == 1
                && t.eventDefinitions().get(0) instanceof CompensateEventDefinition;
    }

    private static Set<String> walk(String from, List<SequenceFlow> flows, boolean forward) {
        Set<String> seen = new HashSet<>();
        Deque<String> todo = new ArrayDeque<>();
        todo.add(from);
        while (!todo.isEmpty()) {
            String id = todo.poll();
            if (!seen.add(id)) {
                continue;
            }
            for (SequenceFlow f : flows) {
                if (forward && f.sourceRef().equals(id)) {
                    todo.add(f.targetRef());
                } else if (!forward && f.targetRef().equals(id)) {
                    todo.add(f.sourceRef());
                }
            }
        }
        return seen;
    }
}
