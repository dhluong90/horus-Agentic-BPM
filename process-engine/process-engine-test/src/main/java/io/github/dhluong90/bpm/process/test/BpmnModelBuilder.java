package io.github.dhluong90.bpm.process.test;

import io.github.dhluong90.bpm.process.internal.model.bpmn.ActivityAttributes;
import io.github.dhluong90.bpm.process.internal.model.bpmn.BusinessRuleTask;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Definitions;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ElementInfo;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EndEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EventData;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ExclusiveGateway;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Expression;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ExtensionBag;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowElement;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import io.github.dhluong90.bpm.process.internal.model.bpmn.GatewayDirection;
import io.github.dhluong90.bpm.process.internal.model.bpmn.IntermediateCatchEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.TimerEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ParallelGateway;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ProcessType;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SequenceFlow;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ServiceTask;
import io.github.dhluong90.bpm.process.internal.model.bpmn.StartEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.UserTask;
import io.github.dhluong90.bpm.process.internal.model.horus.DecisionRef;
import io.github.dhluong90.bpm.process.internal.model.horus.HandlerRef;
import io.github.dhluong90.bpm.process.internal.model.horus.HorusExtension;
import io.github.dhluong90.bpm.process.internal.model.horus.InputMapping;
import io.github.dhluong90.bpm.process.internal.model.horus.ResultVariable;
import io.github.dhluong90.bpm.process.internal.model.horus.TaskAssignment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a {@link Process} in code, so behaviours can be tested before an XML reader exists. Nodes and flows are
 * added in order; {@link #build()} fills {@code incoming} and {@code outgoing} from the flows.
 *
 * <pre>{@code
 * Process p = BpmnModelBuilder.process("card-fee-review")
 *     .startEvent("start")
 *     .serviceTask("load-profile", "cardProfiles.load", Map.of("customerId", "${instance.customerId}"), "profile")
 *     .endEvent("end")
 *     .chain("start", "load-profile", "end")
 *     .build();
 * }</pre>
 */
public final class BpmnModelBuilder {

    /** The HORUS expression language id. */
    public static final String EXPR_V1 = "urn:horus-bpm:expr:v1";

    private final String id;
    private final List<FlowNode> nodes = new ArrayList<>();
    private final List<SequenceFlow> flows = new ArrayList<>();

    private BpmnModelBuilder(String id) {
        this.id = id;
    }

    /**
     * @param id process id
     * @return a builder
     */
    public static BpmnModelBuilder process(String id) {
        return new BpmnModelBuilder(id);
    }

    /** @param id node id @return this */
    public BpmnModelBuilder startEvent(String id) {
        return node(new StartEvent(id, null, null, null, null, null, null, false, true, null));
    }

    /** @param id node id @return this */
    public BpmnModelBuilder endEvent(String id) {
        return node(new EndEvent(id, null, null, null, null, null, null, null));
    }

    /**
     * @param id node id
     * @param handler registered handler name
     * @param inputs input name to reference or literal
     * @param resultVariable result variable, or null
     * @return this
     */
    public BpmnModelBuilder serviceTask(String id, String handler, Map<String, String> inputs, String resultVariable) {
        List<HorusExtension> ext = new ArrayList<>();
        ext.add(new HandlerRef(handler, null, null));
        inputs(ext, inputs);
        if (resultVariable != null) {
            ext.add(new ResultVariable(resultVariable));
        }
        return node(new ServiceTask(id, null, null, null, null, null, null, info(ext)));
    }

    /**
     * @param id node id
     * @param ruleSet rule set key
     * @param inputs input name to reference or literal
     * @param resultVariable result variable
     * @return this
     */
    public BpmnModelBuilder businessRuleTask(String id, String ruleSet, Map<String, String> inputs,
            String resultVariable) {
        List<HorusExtension> ext = new ArrayList<>();
        ext.add(new DecisionRef(ruleSet));
        inputs(ext, inputs);
        if (resultVariable != null) {
            ext.add(new ResultVariable(resultVariable));
        }
        return node(new BusinessRuleTask(id, null, null, null, null, null, info(ext)));
    }

    /**
     * @param id node id
     * @param assignee {@code user:}, {@code role:} or {@code group:} assignee
     * @param resultVariable result variable, or null
     * @return this
     */
    public BpmnModelBuilder userTask(String id, String assignee, String resultVariable) {
        List<HorusExtension> ext = new ArrayList<>();
        ext.add(new TaskAssignment(assignee, false));
        if (resultVariable != null) {
            ext.add(new ResultVariable(resultVariable));
        }
        return node(new UserTask(id, null, null, null, null, null, info(ext)));
    }

    /**
     * @param id node id
     * @param defaultFlow default flow id, or null
     * @return this
     */
    public BpmnModelBuilder exclusiveGateway(String id, String defaultFlow) {
        return node(new ExclusiveGateway(id, null, null, null, GatewayDirection.UNSPECIFIED, defaultFlow, null));
    }

    /**
     * An intermediate timer catch event that waits for an ISO 8601 duration.
     *
     * @param id node id
     * @param duration for example {@code PT10M}
     * @return this
     */
    public BpmnModelBuilder timerCatch(String id, String duration) {
        return node(new IntermediateCatchEvent(id, null, null, null, List.of(new TimerEventDefinition(id + "-timer", null,
                new Expression(duration, null, null), null, null)), null, null, false, null));
    }

    /** @param id node id @return this */
    public BpmnModelBuilder parallelGateway(String id) {
        return node(new ParallelGateway(id, null, null, null, GatewayDirection.UNSPECIFIED, null));
    }

    /**
     * Adds any flow node, for elements this builder has no method for.
     *
     * @param node the node
     * @return this
     */
    public BpmnModelBuilder node(FlowNode node) {
        nodes.add(node);
        return this;
    }

    /**
     * @param flowId flow id
     * @param source source node id
     * @param target target node id
     * @return this
     */
    public BpmnModelBuilder flow(String flowId, String source, String target) {
        flows.add(new SequenceFlow(flowId, null, source, target, null, null, null));
        return this;
    }

    /**
     * @param flowId flow id
     * @param source source node id
     * @param target target node id
     * @param condition condition in {@code urn:horus-bpm:expr:v1}
     * @return this
     */
    public BpmnModelBuilder flow(String flowId, String source, String target, String condition) {
        flows.add(new SequenceFlow(flowId, null, source, target, new Expression(condition, EXPR_V1, null), null, null));
        return this;
    }

    /**
     * @param flowId flow id
     * @param source source node id
     * @param target target node id
     * @param condition condition text
     * @param language the language id of the condition, for example the FEEL URI
     * @return this
     */
    public BpmnModelBuilder flow(String flowId, String source, String target, String condition, String language) {
        flows.add(new SequenceFlow(flowId, null, source, target, new Expression(condition, language, null), null, null));
        return this;
    }

    /**
     * Connects nodes in order with flows named {@code <from>__<to>}.
     *
     * @param nodeIds node ids
     * @return this
     */
    public BpmnModelBuilder chain(String... nodeIds) {
        for (int i = 0; i + 1 < nodeIds.length; i++) {
            flow(nodeIds[i] + "__" + nodeIds[i + 1], nodeIds[i], nodeIds[i + 1]);
        }
        return this;
    }

    /** @return the executable process, with incoming and outgoing filled */
    public Process build() {
        List<FlowElement> elements = new ArrayList<>();
        for (FlowNode node : nodes) {
            elements.add(withRefs(node));
        }
        elements.addAll(flows);
        return new Process(id, null, ProcessType.NONE, true, false, elements, List.of(), List.of(), null);
    }

    /** @return the process inside a definitions element */
    public Definitions definitions() {
        return new Definitions("Definitions_" + id, null, "urn:test", EXPR_V1, null, null, null, List.of(),
                List.of(build()), List.of(), null);
    }

    private FlowNode withRefs(FlowNode node) {
        List<String> in = flows.stream().filter(f -> f.targetRef().equals(node.id())).map(SequenceFlow::id).toList();
        List<String> out = flows.stream().filter(f -> f.sourceRef().equals(node.id())).map(SequenceFlow::id).toList();
        ActivityAttributes act = ActivityAttributes.defaults();
        EventData data = EventData.empty();
        return switch (node) {
            case StartEvent s -> new StartEvent(s.id(), s.name(), in, out, s.eventDefinitions(), s.eventDefinitionRefs(),
                    data, s.parallelMultiple(), s.isInterrupting(), s.info());
            case EndEvent e -> new EndEvent(e.id(), e.name(), in, out, e.eventDefinitions(), e.eventDefinitionRefs(),
                    data, e.info());
            case ServiceTask t -> new ServiceTask(t.id(), t.name(), in, out, act, t.implementation(), t.operationRef(),
                    t.info());
            case BusinessRuleTask t -> new BusinessRuleTask(t.id(), t.name(), in, out, act, t.implementation(), t.info());
            case UserTask t -> new UserTask(t.id(), t.name(), in, out, act, t.implementation(), t.info());
            case ExclusiveGateway g -> new ExclusiveGateway(g.id(), g.name(), in, out, g.gatewayDirection(),
                    g.defaultFlow(), g.info());
            case ParallelGateway g -> new ParallelGateway(g.id(), g.name(), in, out, g.gatewayDirection(), g.info());
            case IntermediateCatchEvent c -> new IntermediateCatchEvent(c.id(), c.name(), in, out, c.eventDefinitions(),
                    c.eventDefinitionRefs(), data, c.parallelMultiple(), c.info());
            default -> node;
        };
    }

    private static void inputs(List<HorusExtension> ext, Map<String, String> inputs) {
        if (inputs != null) {
            new LinkedHashMap<>(inputs).forEach((k, v) -> ext.add(new InputMapping(k, v)));
        }
    }

    private static ElementInfo info(List<HorusExtension> ext) {
        return new ElementInfo(List.of(), new ExtensionBag(ext, List.of()), Map.of());
    }
}
