package io.github.dhluong90.bpm.process.internal.exec;

import io.github.dhluong90.bpm.process.api.DefinitionProblem;
import io.github.dhluong90.bpm.process.api.ValidationFailedException;
import io.github.dhluong90.bpm.process.internal.expr.ConditionException;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EndEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ExclusiveGateway;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowElement;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SequenceFlow;
import io.github.dhluong90.bpm.process.internal.model.bpmn.StartEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.UserTask;
import io.github.dhluong90.bpm.process.internal.model.horus.ResultVariable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
 * outgoing flows of their gateway, conditions on every non-default flow of a diverging exclusive gateway, a
 * registered behaviour for every node and its event definition ({@code BPMN_NOT_EXECUTABLE}), a result variable on
 * every user task, and every compile step of the registry.
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
        Map<String, FlowNode> nodes = process.flowElements().stream().filter(FlowNode.class::isInstance)
                .map(FlowNode.class::cast).collect(Collectors.toMap(FlowNode::id, Function.identity(), (a, b) -> {
                    problems.add(DefinitionProblem.of("DUPLICATE_ID", "Duplicate id", a.id()));
                    return a;
                }));
        List<SequenceFlow> allFlows = process.flowElements().stream().filter(SequenceFlow.class::isInstance)
                .map(SequenceFlow.class::cast).toList();
        // Flows with a missing end are reported below and left out of the graph checks.
        List<SequenceFlow> flows = allFlows.stream().filter(f -> f.sourceRef() != null && f.targetRef() != null).toList();
        if (nodes.size() > maxNodes) {
            problems.add(DefinitionProblem.of("TOO_MANY_NODES", nodes.size() + " flow nodes; at most " + maxNodes, null));
        }
        for (FlowElement element : process.flowElements()) {
            if (element.id() == null || !NODE_ID.matcher(element.id()).matches()) {
                problems.add(DefinitionProblem.of("ID_INVALID", "An id must start with a letter or underscore and have at"
                        + " most 100 letters, digits, '_', '.' or '-'", element.id()));
            }
        }
        List<StartEvent> starts = nodes.values().stream().filter(StartEvent.class::isInstance)
                .map(StartEvent.class::cast).toList();
        if (starts.size() != 1) {
            problems.add(DefinitionProblem.of("START_EVENT_COUNT", "A process needs exactly one start event, found "
                    + starts.size(), process.id()));
        }
        if (nodes.values().stream().noneMatch(EndEvent.class::isInstance)) {
            problems.add(DefinitionProblem.of("NO_END_EVENT", "A process needs at least one end event", process.id()));
        }
        for (SequenceFlow flow : allFlows) {
            if (flow.sourceRef() == null || flow.targetRef() == null
                    || !nodes.containsKey(flow.sourceRef()) || !nodes.containsKey(flow.targetRef())) {
                problems.add(DefinitionProblem.of("FLOW_REF_INVALID", "Sequence flow refers to a missing node "
                        + flow.sourceRef() + " -> " + flow.targetRef(), flow.id()));
            }
            if (flow.conditionExpression() != null && flow.conditionExpression().text() != null) {
                try {
                    io.github.dhluong90.bpm.process.internal.expr.Condition.parse(flow.conditionExpression().text());
                } catch (ConditionException e) {
                    problems.add(DefinitionProblem.of("CONDITION_INVALID", e.getMessage(), flow.id()));
                }
            }
        }
        for (FlowNode node : nodes.values()) {
            List<SequenceFlow> out = flows.stream().filter(f -> f.sourceRef().equals(node.id())).toList();
            if (!(node instanceof EndEvent) && out.isEmpty()) {
                problems.add(DefinitionProblem.of("NO_OUTGOING_FLOW", "Only an end event may have no outgoing flow",
                        node.id()));
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
                        if (!f.id().equals(gateway.defaultFlow()) && f.conditionExpression() == null) {
                            problems.add(DefinitionProblem.of("CONDITION_MISSING", "Every non-default outgoing flow of"
                                    + " a diverging exclusive gateway needs a condition", f.id()));
                        }
                    }
                }
            }
            if (node instanceof UserTask task && task.extensions().first(ResultVariable.class).isEmpty()) {
                problems.add(DefinitionProblem.of("RESULT_VARIABLE_REQUIRED", "A userTask needs horus:resultVariable, so"
                        + " a completion result can never overwrite other process variables", node.id()));
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
        if (starts.size() == 1 && problems.isEmpty()) {
            reachability(starts.get(0), nodes, flows, problems);
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

    private static void reachability(StartEvent start, Map<String, FlowNode> nodes, List<SequenceFlow> flows,
            List<DefinitionProblem> problems) {
        Set<String> forward = walk(start.id(), flows, true);
        Set<String> ends = nodes.values().stream().filter(EndEvent.class::isInstance).map(FlowNode::id)
                .collect(Collectors.toSet());
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
