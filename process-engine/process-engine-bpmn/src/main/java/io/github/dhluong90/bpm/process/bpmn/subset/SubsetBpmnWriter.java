package io.github.dhluong90.bpm.process.bpmn.subset;

import io.github.dhluong90.bpm.process.bpmn.xml.BpmnNamespaces;
import io.github.dhluong90.bpm.process.internal.model.bpmn.BoundaryEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.CancelEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.CompensateEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Transaction;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ErrorEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EscalationEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.IntermediateCatchEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.IntermediateThrowEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.LinkEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.TerminateEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.TimerEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.DataObject;
import io.github.dhluong90.bpm.process.internal.model.bpmn.DataObjectReference;
import io.github.dhluong90.bpm.process.internal.model.bpmn.DataStoreReference;
import io.github.dhluong90.bpm.process.internal.model.bpmn.DataInputAssociation;
import io.github.dhluong90.bpm.process.internal.model.bpmn.DataOutputAssociation;
import io.github.dhluong90.bpm.process.internal.model.bpmn.IoSpecification;
import io.github.dhluong90.bpm.process.internal.model.bpmn.InputSet;
import io.github.dhluong90.bpm.process.internal.model.bpmn.OutputSet;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ActivityAttributes;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Artifact;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Collaboration;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Lane;
import io.github.dhluong90.bpm.process.internal.model.bpmn.LaneSet;
import io.github.dhluong90.bpm.process.internal.model.bpmn.MessageFlow;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Participant;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Association;
import io.github.dhluong90.bpm.process.internal.model.bpmn.BusinessRuleTask;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ManualTask;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ScriptTask;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Task;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ConditionalEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EndEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.IntermediateCatchEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.IntermediateThrowEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SignalEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.AdHocOrdering;
import io.github.dhluong90.bpm.process.internal.model.bpmn.AdHocSubProcess;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ComplexGateway;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ExclusiveGateway;
import io.github.dhluong90.bpm.process.internal.model.bpmn.InclusiveGateway;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowElement;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Group;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ParallelGateway;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SequenceFlow;
import io.github.dhluong90.bpm.process.internal.model.bpmn.CallActivity;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Event;
import io.github.dhluong90.bpm.process.internal.model.bpmn.IntermediateCatchEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.IntermediateThrowEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.MessageEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ReceiveTask;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SendTask;
import io.github.dhluong90.bpm.process.internal.model.horus.CorrelationKey;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ServiceTask;
import io.github.dhluong90.bpm.process.internal.model.horus.CalledVersion;
import io.github.dhluong90.bpm.process.internal.model.horus.OutputMapping;
import io.github.dhluong90.bpm.process.internal.model.bpmn.StartEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Activity;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Expression;
import io.github.dhluong90.bpm.process.internal.model.bpmn.LoopCharacteristics;
import io.github.dhluong90.bpm.process.internal.model.bpmn.MultiInstanceLoopCharacteristics;
import io.github.dhluong90.bpm.process.internal.model.bpmn.StandardLoopCharacteristics;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SubProcess;
import io.github.dhluong90.bpm.process.internal.model.bpmn.TextAnnotation;
import io.github.dhluong90.bpm.process.internal.model.bpmn.UserTask;
import io.github.dhluong90.bpm.process.internal.model.horus.CompensateHandlerRef;
import io.github.dhluong90.bpm.process.internal.model.horus.DecisionRef;
import io.github.dhluong90.bpm.process.internal.model.horus.OnFailure;
import io.github.dhluong90.bpm.process.internal.model.horus.AgentTaskSpec;
import io.github.dhluong90.bpm.process.internal.model.horus.GuardrailSpec;
import io.github.dhluong90.bpm.process.internal.model.horus.HandlerRef;
import io.github.dhluong90.bpm.process.internal.model.horus.InputMapping;
import io.github.dhluong90.bpm.process.internal.model.horus.ResultVariable;
import io.github.dhluong90.bpm.process.internal.model.horus.MemoryEntry;
import io.github.dhluong90.bpm.process.internal.model.horus.RetryPolicy;
import io.github.dhluong90.bpm.process.internal.model.horus.EscalationSpec;
import io.github.dhluong90.bpm.process.internal.model.horus.FormSpec;
import io.github.dhluong90.bpm.process.internal.model.horus.SlaSpec;
import io.github.dhluong90.bpm.process.internal.model.horus.TaskAssignment;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Writes the executable BPMN subset that {@link SubsetBpmnReader} reads (PRE-034): none {@code startEvent} and
 * {@code endEvent}, {@code serviceTask}, {@code businessRuleTask}, {@code userTask}, {@code exclusiveGateway} with
 * {@code default}, and {@code sequenceFlow} with its {@code conditionExpression}, with the {@code horus} attributes
 * and {@code horus:input} elements. Reading the output gives a process with the same definition hash as the one
 * written.
 *
 * <p>The document carries {@code incoming} and {@code outgoing} references and a generated left-to-right BPMNDI
 * layout, so it opens in BPMN modellers. Layout and foreign extensions are not part of the definition, so they are not
 * carried over from the source document.
 *
 * <p>Text annotations, associations and groups are written after the flow elements, with the categories that hold the
 * groups' labels after the process. They are not part of the definition either, but they are what a reader of the
 * diagram sees, so they are kept; the generated layout puts annotations and groups in a row under the flow.
 *
 * <p>The output is deterministic: the same process gives the same bytes. Every attribute and text value is escaped,
 * including tab, line feed and carriage return, so a value reads back exactly. Stateless and thread-safe.
 */
public final class SubsetBpmnWriter {

    private static final String INDENT = "  ";
    private static final int COLUMN_WIDTH = 170;
    private static final int ROW_HEIGHT = 130;
    private static final int LEFT = 150;
    private static final int TOP = 80;
    private static final int BACK_EDGE_MARGIN = 50;
    private static final int ARTIFACT_WIDTH = 150;
    private static final int ANNOTATION_HEIGHT = 60;
    private static final int GROUP_HEIGHT = 80;
    private static final int ARTIFACT_GAP = 50;

    /**
     * @param process a process in the executable subset
     * @return the BPMN 2.0 XML document, UTF-8
     */
    public byte[] write(Process process) {
        Xml xml = new Xml();
        xml.line(0, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        xml.line(0, "<bpmn:definitions xmlns:bpmn=\"" + BpmnNamespaces.BPMN + "\"");
        xml.line(0, "                  xmlns:bpmndi=\"" + BpmnNamespaces.BPMNDI + "\"");
        xml.line(0, "                  xmlns:dc=\"" + BpmnNamespaces.DC + "\"");
        xml.line(0, "                  xmlns:di=\"" + BpmnNamespaces.DI + "\"");
        xml.line(0, "                  xmlns:xsi=\"" + BpmnNamespaces.XSI + "\"");
        xml.line(0, "                  xmlns:horus=\"" + BpmnNamespaces.HORUS + "\"");
        xml.line(0, "                  id=" + attr("Definitions_" + shownId(process)));
        xml.line(0, "                  targetNamespace=\"urn:horus-bpm:definitions\"");
        xml.line(0, "                  expressionLanguage=\"" + BpmnNamespaces.EXPR_V1 + "\"");
        xml.line(0, "                  exporter=\"HORUS Process Engine\">");
        rootCodes(xml, process);
        // Each signal a process uses is one signal element, so the file reads back to the same names.
        Map<String, String> signalIds = new LinkedHashMap<>();
        process.allFlowElements().stream().filter(FlowNode.class::isInstance).map(FlowNode.class::cast)
                .flatMap(n -> eventDefinitions(n).stream()).filter(SignalEventDefinition.class::isInstance)
                .map(d -> ((SignalEventDefinition) d).signalRef())
                .forEach(name -> signalIds.computeIfAbsent(name, k -> "Signal_" + (signalIds.size() + 1)));
        signalIds.forEach((name, id) -> xml.line(1, "<bpmn:signal id=" + attr(id) + " name=" + attr(name) + "/>"));
        // #491: the messages the process names, each once, with ids that collide with no element.
        Map<String, String> messages = new LinkedHashMap<>();
        Set<String> taken = new HashSet<>();
        process.allFlowElements().forEach(e -> taken.add(e.id()));
        taken.add(process.id());
        for (FlowElement element : process.allFlowElements()) {
            if (element instanceof FlowNode node && messageName(node) != null && !messages.containsKey(messageName(node))) {
                messages.put(messageName(node), unique(taken, "Message_" + messageName(node).replace(':', '_')));
            }
        }
        Collaboration collaboration = process.collaboration();
        if (collaboration != null) {
            for (MessageFlow flow : collaboration.messageFlows()) {
                if (flow.messageRef() != null && !messages.containsKey(flow.messageRef())) {
                    messages.put(flow.messageRef(), unique(taken, "Message_" + flow.messageRef().replace(':', '_')));
                }
            }
        }
        messages.forEach((name, id) -> xml.line(1, "<bpmn:message id=" + attr(id) + " name=" + attr(name) + "/>"));
        rootDataStores(xml, process);
        Refs refs = new Refs(signalIds, messages);
        Set<String> collaborationIds = new HashSet<>();
        if (collaboration != null) {
            collaboration(xml, process, collaboration, messages, collaborationIds);
        }
        xml.line(1, "<bpmn:process id=" + attr(shownId(process)) + optional("name", process.name())
                + " isExecutable=\"true\">");
        process.laneSets().forEach(set -> laneSet(xml, set, 2, collaborationIds));
        for (FlowElement element : process.flowElements()) {
            flowElement(xml, element, 2, refs);
        }
        process.artifacts().forEach(artifact -> artifact(xml, artifact, 2));
        xml.line(1, "</bpmn:process>");
        Set<String> ids = new HashSet<>();
        process.allFlowElements().forEach(e -> ids.add(e.id()));
        process.artifacts().forEach(a -> ids.add(a.id()));
        ids.add(process.id());
        ids.add(shownId(process));
        ids.addAll(signalIds.values());
        ids.addAll(messages.values());
        ids.add("Definitions_" + shownId(process));
        ids.addAll(collaborationIds);
        categories(xml, process, ids);
        diagram(xml, process, ids);
        xml.line(0, "</bpmn:definitions>");
        return xml.out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The collaboration of the file (#497). This process is its own pool; every other pool is written as a black box,
     * and a message flow to a step of another pool is drawn to that pool, so the file is complete on its own and reads
     * back to the same definition, lanes and message flows.
     */
    private static void collaboration(Xml xml, Process process, Collaboration c, Map<String, String> messages,
            Set<String> ids) {
        String own = c.participants().stream().filter(x -> process.id().equals(x.processRef())
                || shownId(process).equals(x.processRef()))
                .map(Participant::id).findFirst().orElse(null);
        Set<String> pools = new HashSet<>();
        c.participants().forEach(x -> pools.add(x.id()));
        xml.line(1, "<bpmn:collaboration id=" + attr(c.id()) + optional("name", c.name()) + ">");
        ids.add(c.id());
        for (Participant participant : c.participants()) {
            ids.add(participant.id());
            xml.line(2, "<bpmn:participant id=" + attr(participant.id()) + optional("name", participant.name())
                    + (participant.id().equals(own) ? " processRef=" + attr(shownId(process)) : "") + "/>");
        }
        for (MessageFlow flow : c.messageFlows()) {
            ids.add(flow.id());
            Map<javax.xml.namespace.QName, String> extra = flow.info().otherAttributes();
            String key = extra.get(SubsetBpmnReader.CORRELATION_KEY);
            xml.line(2, "<bpmn:messageFlow id=" + attr(flow.id()) + optional("name", flow.name())
                    + " sourceRef=" + attr(end(flow.sourceRef(), extra.get(SubsetBpmnReader.SOURCE_POOL), own, pools))
                    + " targetRef=" + attr(end(flow.targetRef(), extra.get(SubsetBpmnReader.TARGET_POOL), own, pools))
                    + (flow.messageRef() == null ? "" : " messageRef=" + attr(messages.get(flow.messageRef())))
                    + (key == null ? "" : " horus:correlationKey=" + attr(key)) + "/>");
        }
        xml.line(1, "</bpmn:collaboration>");
    }

    private static String end(String ref, String pool, String own, Set<String> pools) {
        return ref == null || pools.contains(ref) || pool == null || pool.equals(own) ? ref : pool;
    }

    private static void laneSet(Xml xml, LaneSet set, int lvl, Set<String> ids) {
        ids.add(set.id());
        xml.line(lvl, "<bpmn:laneSet id=" + attr(set.id()) + optional("name", set.name()) + ">");
        for (Lane lane : set.lanes()) {
            ids.add(lane.id());
            xml.line(lvl + 1, "<bpmn:lane id=" + attr(lane.id()) + optional("name", lane.name()) + ">");
            lane.flowNodeRefs().forEach(ref -> xml.line(lvl + 2, "<bpmn:flowNodeRef>" + text(ref)
                    + "</bpmn:flowNodeRef>"));
            if (lane.childLaneSet() != null) {
                laneSet(xml, lane.childLaneSet(), lvl + 2, ids);
            }
            xml.line(lvl + 1, "</bpmn:lane>");
        }
        xml.line(lvl, "</bpmn:laneSet>");
    }

    /** The root {@code error} and {@code escalation} elements the event definitions of the process refer to (#489, #493). */
    private static void rootCodes(Xml xml, Process process) {
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, String> escalations = new LinkedHashMap<>();
        for (FlowElement element : process.allFlowElements()) {
            if (element instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.Event event) {
                for (EventDefinition d : event.eventDefinitions()) {
                    if (d instanceof ErrorEventDefinition e && e.errorRef() != null) {
                        errors.putIfAbsent(e.errorRef(), e.errorCode());
                    } else if (d instanceof EscalationEventDefinition e && e.escalationRef() != null) {
                        escalations.putIfAbsent(e.escalationRef(), e.escalationCode());
                    }
                }
            }
        }
        errors.forEach((id, code) -> xml.line(1, "<bpmn:error id=" + attr(id) + optional("errorCode", code) + "/>"));
        escalations.forEach((id, code) -> xml.line(1, "<bpmn:escalation id=" + attr(id)
                + optional("escalationCode", code) + "/>"));
    }

    /** The event definitions of an event as child elements (#489, #493). */
    private static void definitions(Xml xml, FlowNode node, int lvl, Refs refs) {
        if (!(node instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.Event event)) {
            return;
        }
        for (EventDefinition d : event.eventDefinitions()) {
            switch (d) {
                case TimerEventDefinition t -> {
                    xml.line(lvl, "<bpmn:timerEventDefinition>");
                    if (t.timeDuration() != null && t.timeDuration().text() != null) {
                        xml.line(lvl + 1, "<bpmn:timeDuration>" + text(t.timeDuration().text()) + "</bpmn:timeDuration>");
                    }
                    if (t.timeDate() != null && t.timeDate().text() != null) {
                        xml.line(lvl + 1, "<bpmn:timeDate>" + text(t.timeDate().text()) + "</bpmn:timeDate>");
                    }
                    if (t.timeCycle() != null && t.timeCycle().text() != null) {
                        xml.line(lvl + 1, "<bpmn:timeCycle>" + text(t.timeCycle().text()) + "</bpmn:timeCycle>");
                    }
                    xml.line(lvl, "</bpmn:timerEventDefinition>");
                }
                case ErrorEventDefinition e -> xml.line(lvl, "<bpmn:errorEventDefinition"
                        + optional("errorRef", e.errorRef()) + "/>");
                case EscalationEventDefinition e -> xml.line(lvl, "<bpmn:escalationEventDefinition"
                        + optional("escalationRef", e.escalationRef()) + "/>");
                case SignalEventDefinition s -> xml.line(lvl, "<bpmn:signalEventDefinition signalRef="
                        + attr(refs.signals().get(s.signalRef())) + "/>");
                case ConditionalEventDefinition c -> xml.line(lvl, "<bpmn:conditionalEventDefinition><bpmn:condition"
                        + " xsi:type=\"bpmn:tFormalExpression\" language=" + attr(c.condition().language() == null
                        ? BpmnNamespaces.EXPR_V1 : c.condition().language()) + ">"
                        + text(c.condition().text()) + "</bpmn:condition></bpmn:conditionalEventDefinition>");
                case MessageEventDefinition m -> xml.line(lvl, "<bpmn:messageEventDefinition messageRef="
                        + attr(refs.messages().get(m.messageRef())) + "/>");
                case TerminateEventDefinition t -> xml.line(lvl, "<bpmn:terminateEventDefinition/>");
                case CompensateEventDefinition c -> xml.line(lvl, "<bpmn:compensateEventDefinition"
                        + optional("activityRef", c.activityRef())
                        + (c.waitForCompletion() ? "" : " waitForCompletion=\"false\"") + "/>");
                case CancelEventDefinition c -> xml.line(lvl, "<bpmn:cancelEventDefinition/>");
                case LinkEventDefinition l -> xml.line(lvl, "<bpmn:linkEventDefinition" + optional("name", l.name())
                        + "/>");
                default -> throw new IllegalArgumentException("Element " + node.id() + " has an event definition ("
                        + d.getClass().getSimpleName() + ") outside the exportable subset");
            }
        }
    }

    private static void flowElement(Xml xml, FlowElement element, int lvl, Refs refs) {
        switch (element) {
            case SequenceFlow flow -> sequenceFlow(xml, flow, lvl);
            case FlowNode node -> node(xml, node, lvl, refs);
            case DataObject d -> xml.line(lvl, "<bpmn:dataObject id=" + attr(d.id()) + optional("name", d.name())
                    + optional("itemSubjectRef", d.itemSubjectRef()) + (d.isCollection() ? " isCollection=\"true\"" : "")
                    + optional("horus:variable", d.info().otherAttributes().get(SubsetBpmnReader.DATA_VARIABLE)) + "/>");
            case DataObjectReference d -> xml.line(lvl, "<bpmn:dataObjectReference id=" + attr(d.id())
                    + optional("name", d.name()) + optional("dataObjectRef", d.dataObjectRef())
                    + optional("itemSubjectRef", d.itemSubjectRef()) + "/>");
            case DataStoreReference d -> xml.line(lvl, "<bpmn:dataStoreReference id=" + attr(d.id())
                    + optional("name", d.name()) + optional("dataStoreRef", d.dataStoreRef())
                    + optional("itemSubjectRef", d.itemSubjectRef()) + "/>");
            default -> throw new IllegalArgumentException("Element " + element.id() + " ("
                    + element.getClass().getSimpleName() + ") is outside the exportable subset");
        }
    }

    private static List<EventDefinition> eventDefinitions(FlowNode node) {
        return node instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.Event event
                ? event.eventDefinitions() : List.of();
    }

    /** @return the message name of a message event or task, or null */
    private static String messageName(FlowNode node) {
        return switch (node) {
            case ReceiveTask t -> t.messageRef();
            case SendTask t -> t.messageRef();
            case Event e -> e.eventDefinitions().stream().filter(MessageEventDefinition.class::isInstance)
                    .map(d -> ((MessageEventDefinition) d).messageRef()).findFirst().orElse(null);
            default -> null;
        };
    }

    /** The ids of the root {@code signal} and {@code message} elements, by name. */
    private record Refs(Map<String, String> signals, Map<String, String> messages) {
    }

    private static void node(Xml xml, FlowNode node, int lvl, Refs refs) {
        String tag;
        StringBuilder attributes = new StringBuilder(" id=" + attr(node.id()) + optional("name", node.name()));
        List<InputMapping> inputs = List.of();
        String script = null;
        AgentTaskSpec agent = node.extensions().first(AgentTaskSpec.class).orElse(null);
        GuardrailSpec guardrail = node.extensions().first(GuardrailSpec.class).orElse(null);
        String messageName = messageName(node);
        switch (node) {
            case StartEvent s -> {
                tag = "bpmn:startEvent";
                attributes.append(s.isInterrupting() ? "" : " isInterrupting=\"false\"")
                        .append(s.parallelMultiple() ? " parallelMultiple=\"true\"" : "");
            }
            case EndEvent e -> {
                tag = "bpmn:endEvent";
                inputs = e.extensions().all(InputMapping.class);
            }
            case BoundaryEvent b -> {
                tag = "bpmn:boundaryEvent";
                attributes.append(b.parallelMultiple() ? " parallelMultiple=\"true\"" : "")
                        .append(optional("attachedToRef", b.attachedToRef()))
                        .append(b.cancelActivity() ? "" : " cancelActivity=\"false\"");
            }
            case IntermediateCatchEvent c -> {
                tag = "bpmn:intermediateCatchEvent";
                attributes.append(c.parallelMultiple() ? " parallelMultiple=\"true\"" : "");
            }
            case IntermediateThrowEvent t -> {
                tag = "bpmn:intermediateThrowEvent";
                inputs = t.extensions().all(InputMapping.class);
            }
            case ReceiveTask t -> {
                tag = "bpmn:receiveTask";
                attributes.append(forCompensation(t.activity())).append(t.instantiate() ? " instantiate=\"true\"" : "");
                attributes.append(optional("messageRef", refs.messages().get(messageName)));
            }
            case SendTask t -> {
                tag = "bpmn:sendTask";
                attributes.append(forCompensation(t.activity()));
                attributes.append(optional("messageRef", refs.messages().get(messageName)));
                inputs = t.extensions().all(InputMapping.class);
            }
            case ServiceTask t when agent != null -> {
                tag = "bpmn:serviceTask";
                attributes.append(t.activity().isForCompensation() ? " isForCompensation=\"true\"" : "");
                attributes.append(" horus:type=\"agentTask\"").append(optional("horus:agent", agent.agent()))
                        .append(optional("horus:timeout", agent.timeout() == null ? null : agent.timeout().toString()));
                inputs = t.extensions().all(InputMapping.class);
            }
            case ServiceTask t -> {
                tag = "bpmn:serviceTask";
                attributes.append(t.activity().isForCompensation() ? " isForCompensation=\"true\"" : "");
                t.extensions().first(HandlerRef.class).ifPresent(h -> attributes.append(optional("horus:handler",
                        h.handler())).append(optional("horus:mode", h.mode()))
                        .append(optional("horus:timeout", h.timeout() == null ? null : h.timeout().toString())));
                inputs = t.extensions().all(InputMapping.class);
            }
            case Task t -> {
                tag = "bpmn:task";
                attributes.append(forCompensation(t.activity()));
            }
            case ManualTask t -> {
                tag = "bpmn:manualTask";
                attributes.append(forCompensation(t.activity()));
            }
            case ScriptTask t -> {
                tag = "bpmn:scriptTask";
                attributes.append(forCompensation(t.activity()));
                attributes.append(optional("scriptFormat", t.scriptFormat()));
                script = t.script();
            }
            case BusinessRuleTask t -> {
                tag = "bpmn:businessRuleTask";
                attributes.append(forCompensation(t.activity()));
                t.extensions().first(DecisionRef.class).ifPresent(d -> attributes.append(optional("horus:ruleSet",
                        d.ruleSet())));
                inputs = t.extensions().all(InputMapping.class);
            }
            case UserTask t -> {
                tag = "bpmn:userTask";
                attributes.append(forCompensation(t.activity()));
                t.extensions().first(TaskAssignment.class).ifPresent(a -> attributes.append(optional("horus:assignee",
                        a.assignee())).append(a.fourEyes() ? " horus:fourEyes=\"true\"" : ""));
                t.extensions().first(SlaSpec.class).ifPresent(sla -> attributes
                        .append(optional("horus:sla", sla.duration().toString()))
                        .append(optional("horus:calendar", sla.calendar())));
                t.extensions().first(FormSpec.class).ifPresent(f -> attributes
                        .append(optional("horus:form", f.formKey()))
                        .append(optional("horus:formSchema", f.schema()))
                        .append(optional("horus:formHints", f.hints()))
                        .append(optional("horus:formLink", f.link())));
                t.extensions().first(EscalationSpec.class).ifPresent(e -> attributes
                        .append(optional("horus:escalation", e.levels().stream().map(l -> l.after().isZero() ? l.to()
                                : l.to() + "|" + l.after()).collect(java.util.stream.Collectors.joining(";"))))
                        .append(e.releaseClaim() ? "" : " horus:onClaimed=\"keep\""));
            }
            case CallActivity c -> {
                tag = "bpmn:callActivity";
                attributes.append(forCompensation(c.activity()));
                attributes.append(optional("calledElement", c.calledElement()));
                c.extensions().first(CalledVersion.class).ifPresent(v -> attributes.append(
                        optional("horus:calledVersion", String.valueOf(v.version()))));
                inputs = c.extensions().all(InputMapping.class);
            }
            case SubProcess s -> {
                tag = "bpmn:subProcess";
                attributes.append(s.triggeredByEvent() ? " triggeredByEvent=\"true\"" : "");
            }
            case Transaction t -> tag = "bpmn:transaction";
            case ParallelGateway g -> tag = "bpmn:parallelGateway";
            case io.github.dhluong90.bpm.process.internal.model.bpmn.EventBasedGateway g -> {
                tag = "bpmn:eventBasedGateway";
                attributes.append(g.instantiate() ? " instantiate=\"true\"" : "");
                attributes.append(g.eventGatewayType() == io.github.dhluong90.bpm.process.internal.model.bpmn
                        .EventBasedGatewayType.PARALLEL ? " eventGatewayType=\"Parallel\"" : "");
            }
            case ComplexGateway g -> {
                tag = "bpmn:complexGateway";
                attributes.append(optional("default", g.defaultFlow()));
            }
            case AdHocSubProcess a -> {
                tag = "bpmn:adHocSubProcess";
                attributes.append(a.ordering() == AdHocOrdering.SEQUENTIAL ? " ordering=\"Sequential\"" : "")
                        .append(a.cancelRemainingInstances() ? "" : " cancelRemainingInstances=\"false\"");
            }
            case ExclusiveGateway g -> {
                tag = "bpmn:exclusiveGateway";
                attributes.append(optional("default", g.defaultFlow()));
            }
            case InclusiveGateway g -> {
                tag = "bpmn:inclusiveGateway";
                attributes.append(optional("default", g.defaultFlow()));
            }
            default -> throw new IllegalArgumentException("Element " + node.id() + " ("
                    + node.getClass().getSimpleName() + ") is outside the exportable subset");
        }
        if (node instanceof Activity activity) {
            attributes.append(optional("default", activity.activity().defaultFlow()));
        }
        node.extensions().first(ResultVariable.class).ifPresent(r -> attributes.append(optional("horus:resultVariable",
                r.name())));
        node.extensions().first(CorrelationKey.class).ifPresent(k -> attributes.append(optional("horus:correlationKey",
                k.variable())));
        // #67: the undo and the failure action are part of the step, so the export reads back to the same hash.
        node.extensions().first(CompensateHandlerRef.class).ifPresent(c -> attributes.append(optional(
                "horus:compensate", c.handler())));
        node.extensions().first(OnFailure.class).ifPresent(f -> attributes.append(optional("horus:onFailure",
                f.action())));
        List<OutputMapping> outputs = node.extensions().all(OutputMapping.class);
        LoopCharacteristics loop = node instanceof Activity activity ? activity.activity().loopCharacteristics() : null;
        if (inputs.isEmpty() && outputs.isEmpty() && agent == null && script == null && node.incoming().isEmpty()
                && node.outgoing().isEmpty() && !(node instanceof SubProcess) && loop == null && !(node instanceof Transaction)
                && !(node instanceof AdHocSubProcess) && !hasData(node) && !(node instanceof ComplexGateway cg
                        && cg.activationCondition() != null)
                && !(node instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.Event e
                        && !e.eventDefinitions().isEmpty())) {
            xml.line(lvl, "<" + tag + attributes + "/>");
            return;
        }
        xml.line(lvl, "<" + tag + attributes + ">");
        if (!inputs.isEmpty() || !outputs.isEmpty() || agent != null) {
            xml.line(lvl + 1, "<bpmn:extensionElements>");
            for (InputMapping input : inputs) {
                xml.line(lvl + 2, "<horus:input name=" + attr(input.name()) + " value=" + attr(input.value())
                        + (input.mask() ? " mask=\"true\"" : "") + "/>");
            }
            for (OutputMapping output : outputs) {
                xml.line(lvl + 2, "<horus:output name=" + attr(output.name()) + " from=" + attr(output.from()) + "/>");
            }
            if (agent != null && agent.outputSchema() != null) {
                xml.line(lvl + 2, "<horus:outputSchema>" + text(agent.outputSchema()) + "</horus:outputSchema>");
            }
            if (agent != null && agent.fieldTitles() != null) {
                xml.line(lvl + 2, "<horus:fieldTitles>" + text(agent.fieldTitles()) + "</horus:fieldTitles>");
            }
            if (guardrail != null) {
                xml.line(lvl + 2, "<horus:guardrail" + optional("decision", guardrail.decision())
                        + optional("onViolation", guardrail.onViolation()) + optional("reviewer", guardrail.reviewer())
                        + "/>");
            }
            RetryPolicy retry = agent == null ? null : node.extensions().first(RetryPolicy.class).orElse(null);
            if (retry != null) {
                xml.line(lvl + 2, "<horus:retry" + optional("max", String.valueOf(retry.max()))
                        + optional("backoff", retry.backoff() == null ? null : retry.backoff().toString()) + "/>");
            }
            if (agent != null) {
                // #101: memory is part of the step, so the export reads back to the same definition hash.
                for (MemoryEntry m : node.extensions().all(MemoryEntry.class)) {
                    xml.line(lvl + 2, "<horus:memory" + optional("from", m.from()) + optional("fields",
                            String.join(",", m.fields())) + optional("as", m.as()) + " required=\"" + m.required()
                            + "\"" + (m.includeMasked() ? " includeMasked=\"true\"" : "") + "/>");
                }
            }
            xml.line(lvl + 1, "</bpmn:extensionElements>");
        }
        node.incoming().forEach(id -> xml.line(lvl + 1, "<bpmn:incoming>" + text(id) + "</bpmn:incoming>"));
        node.outgoing().forEach(id -> xml.line(lvl + 1, "<bpmn:outgoing>" + text(id) + "</bpmn:outgoing>"));
        if (node instanceof Activity activity) {
            dataOf(xml, activity.activity(), lvl + 1);
        }
        if (loop != null) {
            loopCharacteristics(xml, loop, lvl + 1);
        }
        // The script follows the activity's own content in the schema's order (#496: after a loop marker).
        if (script != null) {
            xml.line(lvl + 1, "<bpmn:script>" + text(script) + "</bpmn:script>");
        }
        definitions(xml, node, lvl + 1, refs);
        if (node instanceof ComplexGateway g && g.activationCondition() != null) {
            xml.line(lvl + 1, "<bpmn:activationCondition xsi:type=\"bpmn:tFormalExpression\" language="
                    + attr(language(g.activationCondition())) + ">" + text(g.activationCondition().text())
                    + "</bpmn:activationCondition>");
        }
        if (node instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.FlowElementsContainer sub) {
            // #486, #488: the steps and flows of a sub-process or a transaction, nested one level deeper; the diagram is
            // generated for the top level only.
            sub.flowElements().forEach(inner -> flowElement(xml, inner, lvl + 1, refs));
            sub.artifacts().forEach(inner -> artifact(xml, inner, lvl + 1));
        }
        if (node instanceof AdHocSubProcess a && a.completionCondition() != null) {
            xml.line(lvl + 1, "<bpmn:completionCondition xsi:type=\"bpmn:tFormalExpression\" language="
                    + attr(language(a.completionCondition())) + ">" + text(a.completionCondition().text())
                    + "</bpmn:completionCondition>");
        }
        xml.line(lvl, "</" + tag + ">");
    }

    private static boolean hasData(FlowNode node) {
        if (!(node instanceof Activity activity)) {
            return false;
        }
        var a = activity.activity();
        return a.ioSpecification() != null || !a.dataInputAssociations().isEmpty()
                || !a.dataOutputAssociations().isEmpty();
    }

    /** #499: the data inputs and outputs and their associations of an activity, in the schema's order. */
    private static void dataOf(Xml xml, ActivityAttributes a, int lvl) {
        IoSpecification io = a.ioSpecification();
        if (io != null) {
            xml.line(lvl, "<bpmn:ioSpecification" + optional("id", io.id()) + ">");
            io.dataInputs().forEach(d -> xml.line(lvl + 1, "<bpmn:dataInput id=" + attr(d.id())
                    + optional("name", d.name()) + "/>"));
            io.dataOutputs().forEach(d -> xml.line(lvl + 1, "<bpmn:dataOutput id=" + attr(d.id())
                    + optional("name", d.name()) + "/>"));
            for (InputSet set : io.inputSets()) {
                xml.line(lvl + 1, "<bpmn:inputSet" + optional("id", set.id()) + optional("name", set.name()) + ">");
                set.dataInputRefs().forEach(r -> xml.line(lvl + 2, "<bpmn:dataInputRefs>" + text(r)
                        + "</bpmn:dataInputRefs>"));
                xml.line(lvl + 1, "</bpmn:inputSet>");
            }
            for (OutputSet set : io.outputSets()) {
                xml.line(lvl + 1, "<bpmn:outputSet" + optional("id", set.id()) + optional("name", set.name()) + ">");
                set.dataOutputRefs().forEach(r -> xml.line(lvl + 2, "<bpmn:dataOutputRefs>" + text(r)
                        + "</bpmn:dataOutputRefs>"));
                xml.line(lvl + 1, "</bpmn:outputSet>");
            }
            xml.line(lvl, "</bpmn:ioSpecification>");
        }
        for (DataInputAssociation d : a.dataInputAssociations()) {
            association(xml, "dataInputAssociation", d.id(), d.sourceRefs(), d.targetRef(), lvl);
        }
        for (DataOutputAssociation d : a.dataOutputAssociations()) {
            association(xml, "dataOutputAssociation", d.id(), d.sourceRefs(), d.targetRef(), lvl);
        }
    }

    private static void association(Xml xml, String tag, String id, List<String> sources, String target, int lvl) {
        xml.line(lvl, "<bpmn:" + tag + optional("id", id) + ">");
        sources.forEach(r -> xml.line(lvl + 1, "<bpmn:sourceRef>" + text(r) + "</bpmn:sourceRef>"));
        xml.line(lvl + 1, "<bpmn:targetRef>" + text(target) + "</bpmn:targetRef>");
        xml.line(lvl, "</bpmn:" + tag + ">");
    }

    /** #499: one root data store for each store the data store references name, written before the process. */
    private static void rootDataStores(Xml xml, Process process) {
        Map<String, String> stores = new LinkedHashMap<>();
        for (FlowElement element : process.allFlowElements()) {
            if (element instanceof DataStoreReference d && d.dataStoreRef() != null) {
                stores.putIfAbsent(d.dataStoreRef(), d.name());
            }
        }
        stores.forEach((id, name) -> xml.line(1, "<bpmn:dataStore id=" + attr(id) + optional("name", name) + "/>"));
    }

    /** #496: the loop or multi-instance marker of an activity, read back to the same definition hash. */
    private static void loopCharacteristics(Xml xml, LoopCharacteristics loop, int lvl) {
        switch (loop) {
            case StandardLoopCharacteristics standard -> {
                String start = "<bpmn:standardLoopCharacteristics" + (standard.testBefore() ? " testBefore=\"true\"" : "")
                        + optional("loopMaximum", standard.loopMaximum() == null ? null
                                : String.valueOf(standard.loopMaximum()));
                if (standard.loopCondition() == null) {
                    xml.line(lvl, start + "/>");
                } else {
                    xml.line(lvl, start + ">");
                    expression(xml, "loopCondition", standard.loopCondition(), lvl + 1);
                    xml.line(lvl, "</bpmn:standardLoopCharacteristics>");
                }
            }
            case MultiInstanceLoopCharacteristics mi -> {
                String start = "<bpmn:multiInstanceLoopCharacteristics" + (mi.isSequential() ? " isSequential=\"true\"" : "")
                        + optional("horus:collection", mi.loopDataInputRef())
                        + optional("horus:elementVariable", mi.inputDataItem() == null ? null : mi.inputDataItem().name())
                        + optional("horus:outputCollection", mi.loopDataOutputRef())
                        + optional("horus:outputElement", mi.outputDataItem() == null ? null : mi.outputDataItem().name());
                if (mi.loopCardinality() == null && mi.completionCondition() == null) {
                    xml.line(lvl, start + "/>");
                } else {
                    xml.line(lvl, start + ">");
                    if (mi.loopCardinality() != null) {
                        expression(xml, "loopCardinality", mi.loopCardinality(), lvl + 1);
                    }
                    if (mi.completionCondition() != null) {
                        expression(xml, "completionCondition", mi.completionCondition(), lvl + 1);
                    }
                    xml.line(lvl, "</bpmn:multiInstanceLoopCharacteristics>");
                }
            }
        }
    }

    private static void expression(Xml xml, String tag, Expression expression, int lvl) {
        String language = expression.language() == null ? BpmnNamespaces.EXPR_V1 : expression.language();
        xml.line(lvl, "<bpmn:" + tag + " xsi:type=\"bpmn:tFormalExpression\" language=" + attr(language) + ">"
                + text(expression.text()) + "</bpmn:" + tag + ">");
    }

    private static String language(io.github.dhluong90.bpm.process.internal.model.bpmn.Expression e) {
        return e.language() == null ? BpmnNamespaces.EXPR_V1 : e.language();
    }

    private static void artifact(Xml xml, Artifact artifact, int lvl) {
        switch (artifact) {
            case TextAnnotation note -> {
                String start = "<bpmn:textAnnotation id=" + attr(note.id()) + optional("textFormat", note.textFormat());
                if (note.text() == null) {
                    xml.line(lvl, start + "/>");
                } else {
                    xml.line(lvl, start + ">");
                    xml.line(lvl + 1, "<bpmn:text>" + text(note.text()) + "</bpmn:text>");
                    xml.line(lvl, "</bpmn:textAnnotation>");
                }
            }
            case Association a -> xml.line(lvl, "<bpmn:association id=" + attr(a.id()) + " associationDirection=\""
                    + switch (a.associationDirection()) {
                        case NONE -> "None";
                        case ONE -> "One";
                        case BOTH -> "Both";
                    } + "\"" + optional("sourceRef", a.sourceRef()) + optional("targetRef", a.targetRef()) + "/>");
            case Group g -> xml.line(lvl, "<bpmn:group id=" + attr(g.id()) + optional("categoryValueRef",
                    g.categoryValueRef()) + "/>");
        }
    }

    /**
     * One category per category id the groups name, holding each referenced value once, in document order. A group
     * whose reference did not resolve in the source keeps the reference and gets no category, as in the source.
     */
    private static void categories(Xml xml, Process process, Set<String> ids) {
        Map<String, Map<String, String>> categories = new LinkedHashMap<>();
        Set<String> values = new HashSet<>();
        for (Artifact artifact : process.artifacts()) {
            String category = artifact instanceof Group g ? SubsetBpmnReader.categoryId(g) : null;
            if (category != null && values.add(((Group) artifact).categoryValueRef())) {
                Group g = (Group) artifact;
                ids.add(g.categoryValueRef());
                categories.computeIfAbsent(category, k -> new LinkedHashMap<>()).put(g.categoryValueRef(),
                        SubsetBpmnReader.categoryValueLabel(g));
            }
        }
        for (Map.Entry<String, Map<String, String>> category : categories.entrySet()) {
            xml.line(1, "<bpmn:category id=" + attr(unique(ids, category.getKey())) + ">");
            category.getValue().forEach((id, label) -> xml.line(2, "<bpmn:categoryValue id=" + attr(id)
                    + optional("value", label) + "/>"));
            xml.line(1, "</bpmn:category>");
        }
    }

    private static void sequenceFlow(Xml xml, SequenceFlow flow, int lvl) {
        String start = "<bpmn:sequenceFlow id=" + attr(flow.id()) + optional("name", flow.name())
                + optional("sourceRef", flow.sourceRef()) + optional("targetRef", flow.targetRef());
        if (flow.conditionExpression() == null) {
            xml.line(lvl, start + "/>");
            return;
        }
        String language = flow.conditionExpression().language() == null ? BpmnNamespaces.EXPR_V1
                : flow.conditionExpression().language();
        xml.line(lvl, start + ">");
        xml.line(lvl + 1, "<bpmn:conditionExpression xsi:type=\"bpmn:tFormalExpression\" language=" + attr(language) + ">"
                + text(flow.conditionExpression().text()) + "</bpmn:conditionExpression>");
        xml.line(lvl, "</bpmn:sequenceFlow>");
    }

    // ---- generated layout ---------------------------------------------------------------------------------

    private record Box(int x, int y, int width, int height) {
        int centreX() {
            return x + width / 2;
        }

        int centreY() {
            return y + height / 2;
        }
    }

    /**
     * Columns are the shortest distance from a start event along the sequence flows; rows follow document order
     * within a column. Nodes no start event reaches go in the column after the last one.
     */
    private static void diagram(Xml xml, Process process, Set<String> ids) {
        List<FlowNode> nodes = process.flowElements().stream().filter(FlowNode.class::isInstance)
                .map(FlowNode.class::cast).toList();
        List<SequenceFlow> flows = process.flowElements().stream().filter(SequenceFlow.class::isInstance)
                .map(SequenceFlow.class::cast).toList();
        Map<String, Integer> column = columns(nodes, flows);
        Map<Integer, Integer> rowsUsed = new HashMap<>();
        Map<String, Box> boxes = new LinkedHashMap<>();
        for (FlowNode node : nodes) {
            if (node instanceof BoundaryEvent) {
                continue;
            }
            int col = column.get(node.id());
            int row = rowsUsed.merge(col, 1, Integer::sum) - 1;
            int[] size = size(node);
            int cellX = LEFT + col * COLUMN_WIDTH;
            int cellY = TOP + row * ROW_HEIGHT;
            boxes.put(node.id(), new Box(cellX + (100 - size[0]) / 2, cellY + (80 - size[1]) / 2, size[0], size[1]));
        }
        // #489: a boundary event sits on the lower edge of its activity, side by side when there are several.
        Map<String, Integer> boundariesPlaced = new HashMap<>();
        for (FlowNode node : nodes) {
            if (node instanceof BoundaryEvent b && boxes.containsKey(b.attachedToRef())) {
                Box host = boxes.get(b.attachedToRef());
                int n = boundariesPlaced.merge(b.attachedToRef(), 1, Integer::sum) - 1;
                boxes.put(node.id(), new Box(host.x() + 12 + n * 40, host.y() + host.height() - 18, 36, 36));
            } else if (node instanceof BoundaryEvent) {
                boxes.put(node.id(), new Box(LEFT, TOP, 36, 36));
            }
        }
        int bottom = boxes.values().stream().mapToInt(b -> b.y() + b.height()).max().orElse(TOP);
        Map<String, Box> artifactBoxes = new LinkedHashMap<>();
        int artifactX = LEFT;
        for (Artifact artifact : process.artifacts()) {
            int height = switch (artifact) {
                case TextAnnotation t -> ANNOTATION_HEIGHT;
                case Group g -> GROUP_HEIGHT;
                case Association a -> 0;
            };
            if (height > 0) {
                artifactBoxes.put(artifact.id(), new Box(artifactX, bottom + 2 * BACK_EDGE_MARGIN, ARTIFACT_WIDTH,
                        height));
                artifactX += ARTIFACT_WIDTH + ARTIFACT_GAP;
            }
        }
        xml.line(1, "<bpmndi:BPMNDiagram id=" + attr(unique(ids, "Diagram_" + shownId(process))) + ">");
        xml.line(2, "<bpmndi:BPMNPlane id=" + attr(unique(ids, "Plane_" + shownId(process))) + " bpmnElement="
                + attr(shownId(process)) + ">");
        for (FlowNode node : nodes) {
            Box b = boxes.get(node.id());
            String marker = node instanceof ExclusiveGateway ? " isMarkerVisible=\"true\"" : "";
            xml.line(3, "<bpmndi:BPMNShape id=" + attr(unique(ids, node.id() + "_di")) + " bpmnElement="
                    + attr(node.id()) + marker + ">");
            xml.line(4, "<dc:Bounds x=\"" + b.x() + "\" y=\"" + b.y() + "\" width=\"" + b.width() + "\" height=\""
                    + b.height() + "\"/>");
            xml.line(3, "</bpmndi:BPMNShape>");
        }
        for (Map.Entry<String, Box> shape : artifactBoxes.entrySet()) {
            Box b = shape.getValue();
            xml.line(3, "<bpmndi:BPMNShape id=" + attr(unique(ids, shape.getKey() + "_di")) + " bpmnElement="
                    + attr(shape.getKey()) + ">");
            xml.line(4, "<dc:Bounds x=\"" + b.x() + "\" y=\"" + b.y() + "\" width=\"" + b.width() + "\" height=\""
                    + b.height() + "\"/>");
            xml.line(3, "</bpmndi:BPMNShape>");
        }
        for (SequenceFlow flow : flows) {
            Box source = boxes.get(flow.sourceRef());
            Box target = boxes.get(flow.targetRef());
            if (source == null || target == null) {
                continue;
            }
            xml.line(3, "<bpmndi:BPMNEdge id=" + attr(unique(ids, flow.id() + "_di")) + " bpmnElement="
                    + attr(flow.id()) + ">");
            for (int[] point : waypoints(source, target, bottom + BACK_EDGE_MARGIN)) {
                xml.line(4, "<di:waypoint x=\"" + point[0] + "\" y=\"" + point[1] + "\"/>");
            }
            xml.line(3, "</bpmndi:BPMNEdge>");
        }
        // An association is drawn between the centres of its ends; one whose end has no shape (a sequence flow, for
        // example) is kept in the process but not drawn.
        for (Artifact artifact : process.artifacts()) {
            if (!(artifact instanceof Association a)) {
                continue;
            }
            Box source = a.sourceRef() == null ? null : boxes.getOrDefault(a.sourceRef(), artifactBoxes.get(a.sourceRef()));
            Box target = a.targetRef() == null ? null : boxes.getOrDefault(a.targetRef(), artifactBoxes.get(a.targetRef()));
            if (source == null || target == null) {
                continue;
            }
            xml.line(3, "<bpmndi:BPMNEdge id=" + attr(unique(ids, a.id() + "_di")) + " bpmnElement=" + attr(a.id())
                    + ">");
            xml.line(4, "<di:waypoint x=\"" + source.centreX() + "\" y=\"" + source.centreY() + "\"/>");
            xml.line(4, "<di:waypoint x=\"" + target.centreX() + "\" y=\"" + target.centreY() + "\"/>");
            xml.line(3, "</bpmndi:BPMNEdge>");
        }
        xml.line(2, "</bpmndi:BPMNPlane>");
        xml.line(1, "</bpmndi:BPMNDiagram>");
    }

    private static Map<String, Integer> columns(List<FlowNode> nodes, List<SequenceFlow> flows) {
        Map<String, List<String>> next = new HashMap<>();
        for (SequenceFlow f : flows) {
            next.computeIfAbsent(f.sourceRef(), k -> new ArrayList<>()).add(f.targetRef());
        }
        // A boundary event is reached from its activity (#489).
        for (FlowNode node : nodes) {
            if (node instanceof BoundaryEvent b && b.attachedToRef() != null) {
                next.computeIfAbsent(b.attachedToRef(), k -> new ArrayList<>()).add(b.id());
            }
        }
        Map<String, Integer> column = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        for (FlowNode node : nodes) {
            if (node instanceof StartEvent) {
                column.put(node.id(), 0);
                queue.add(node.id());
            }
        }
        while (!queue.isEmpty()) {
            String id = queue.poll();
            for (String target : next.getOrDefault(id, List.of())) {
                if (target != null && !column.containsKey(target)) {
                    column.put(target, column.get(id) + 1);
                    queue.add(target);
                }
            }
        }
        int unreached = column.values().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
        for (FlowNode node : nodes) {
            column.putIfAbsent(node.id(), unreached);
        }
        return column;
    }

    private static int[] size(FlowNode node) {
        return switch (node) {
            case StartEvent s -> new int[] {36, 36};
            case EndEvent e -> new int[] {36, 36};
            case BoundaryEvent b -> new int[] {36, 36};
            case IntermediateCatchEvent c -> new int[] {36, 36};
            case IntermediateThrowEvent t -> new int[] {36, 36};
            case ExclusiveGateway g -> new int[] {50, 50};
            case InclusiveGateway g -> new int[] {50, 50};
            case io.github.dhluong90.bpm.process.internal.model.bpmn.EventBasedGateway g -> new int[] {50, 50};
            case ComplexGateway g -> new int[] {50, 50};
            default -> new int[] {100, 80};
        };
    }

    /** Forward edges leave right and enter left with one vertical leg; other edges run below the diagram. */
    private static List<int[]> waypoints(Box source, Box target, int below) {
        int sx = source.x() + source.width();
        int tx = target.x();
        if (tx > sx) {
            if (source.centreY() == target.centreY()) {
                return List.of(new int[] {sx, source.centreY()}, new int[] {tx, target.centreY()});
            }
            int mid = sx + (tx - sx) / 2;
            return List.of(new int[] {sx, source.centreY()}, new int[] {mid, source.centreY()},
                    new int[] {mid, target.centreY()}, new int[] {tx, target.centreY()});
        }
        return List.of(new int[] {source.centreX(), source.y() + source.height()}, new int[] {source.centreX(), below},
                new int[] {target.centreX(), below}, new int[] {target.centreX(), target.y() + target.height()});
    }

    private static String unique(Set<String> ids, String wanted) {
        String id = wanted;
        for (int n = 2; !ids.add(id); n++) {
            id = wanted + "_" + n;
        }
        return id;
    }

    // ---- escaping -----------------------------------------------------------------------------------------

    /** The id the process had in the file it was read from, or its key when it was never mapped. */
    private static String shownId(Process process) {
        return process.info() == null ? process.id() : process.info().extensions()
                .first(io.github.dhluong90.bpm.process.internal.model.horus.OriginalId.class)
                .map(io.github.dhluong90.bpm.process.internal.model.horus.OriginalId::id).orElse(process.id());
    }

    /** #632: the compensation marker of an activity, written the same on every activity type. */
    private static String forCompensation(io.github.dhluong90.bpm.process.internal.model.bpmn.ActivityAttributes a) {
        return a.isForCompensation() ? " isForCompensation=\"true\"" : "";
    }

    private static String optional(String name, String value) {
        return value == null ? "" : " " + name + "=" + attr(value);
    }

    private static String attr(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\t' -> out.append("&#9;");
                case '\n' -> out.append("&#10;");
                case '\r' -> out.append("&#13;");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static String text(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '\r' -> out.append("&#13;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static final class Xml {
        private final StringBuilder out = new StringBuilder(4096);

        void line(int depth, String text) {
            out.append(INDENT.repeat(depth)).append(text).append('\n');
        }
    }
}
