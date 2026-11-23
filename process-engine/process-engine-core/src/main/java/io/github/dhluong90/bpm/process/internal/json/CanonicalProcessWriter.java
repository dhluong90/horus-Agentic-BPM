package io.github.dhluong90.bpm.process.internal.json;

import io.github.dhluong90.bpm.process.internal.model.bpmn.BoundaryEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ErrorEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EscalationEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.LinkEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.TerminateEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.BusinessRuleTask;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ManualTask;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ScriptTask;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Task;
import io.github.dhluong90.bpm.process.internal.model.bpmn.CallActivity;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Event;
import io.github.dhluong90.bpm.process.internal.model.bpmn.MessageEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ReceiveTask;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SendTask;
import io.github.dhluong90.bpm.process.internal.model.horus.CorrelationKey;
import io.github.dhluong90.bpm.process.internal.model.horus.CalledVersion;
import io.github.dhluong90.bpm.process.internal.model.horus.OutputMapping;
import io.github.dhluong90.bpm.process.internal.model.bpmn.CompensateEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EndEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.IntermediateThrowEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SubProcess;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Transaction;
import io.github.dhluong90.bpm.process.internal.model.horus.CompensateHandlerRef;
import io.github.dhluong90.bpm.process.internal.model.horus.OnFailure;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ExclusiveGateway;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowElement;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ConditionalEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.IntermediateCatchEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SignalEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ParallelGateway;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SequenceFlow;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ServiceTask;
import io.github.dhluong90.bpm.process.internal.model.bpmn.StartEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.TimerEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.UserTask;
import io.github.dhluong90.bpm.process.internal.model.horus.AgentTaskSpec;
import io.github.dhluong90.bpm.process.internal.model.horus.DecisionRef;
import io.github.dhluong90.bpm.process.internal.model.horus.GuardrailSpec;
import io.github.dhluong90.bpm.process.internal.model.horus.HandlerRef;
import io.github.dhluong90.bpm.process.internal.model.horus.InputMapping;
import io.github.dhluong90.bpm.process.internal.model.horus.MemoryEntry;
import io.github.dhluong90.bpm.process.internal.model.horus.ResultVariable;
import io.github.dhluong90.bpm.process.internal.model.horus.RetryPolicy;
import io.github.dhluong90.bpm.process.internal.model.horus.EscalationSpec;
import io.github.dhluong90.bpm.process.internal.model.horus.SlaSpec;
import io.github.dhluong90.bpm.process.internal.model.horus.TaskAssignment;
import java.util.List;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Writes the executable subset in the {@code process-definition.v1} profile, the lowest schema version that
 * expresses it (DESIGN section 5.2, DN-04). Layout and foreign extensions are not written, so they never change
 * the hash. The hash is the SHA-256 of the RFC 8785 form.
 */
public final class CanonicalProcessWriter {

    private CanonicalProcessWriter() {
    }

    /**
     * @param process the process
     * @return the canonical document
     */
    public static ObjectNode write(Process process) {
        ObjectNode doc = EngineJson.NODES.objectNode();
        doc.put("schemaVersion", 1);
        doc.put("process", process.id());
        if (process.name() != null) {
            doc.put("name", process.name());
        }
        writeSteps(doc.putArray("steps"), process.flowElements(), process.artifacts());
        return doc;
    }

    /**
     * The steps of one container. A {@code saga} block ({@code transaction}, #67) is a step of type {@code saga}
     * whose own steps are nested under {@code steps}, so a definition without one keeps the hash it had.
     */
    private static void writeSteps(ArrayNode steps, List<FlowElement> elements,
            List<io.github.dhluong90.bpm.process.internal.model.bpmn.Artifact> artifacts) {
        List<SequenceFlow> flows = elements.stream().filter(SequenceFlow.class::isInstance)
                .map(SequenceFlow.class::cast).toList();
        for (FlowElement element : elements) {
            if (!(element instanceof FlowNode node)) {
                continue;
            }
            ObjectNode step = steps.addObject();
            step.put("id", node.id());
            if (node.name() != null) {
                step.put("name", node.name());
            }
            List<SequenceFlow> out = flows.stream().filter(f -> f.sourceRef().equals(node.id())).toList();
            switch (node) {
                case StartEvent s -> {
                    step.put("type", "start");
                    writeEventDefinition(step, s.eventDefinitions());
                    if (!s.isInterrupting()) {
                        step.put("interrupting", false);
                    }
                }
                case EndEvent e when e.eventDefinitions().size() == 1
                        && e.eventDefinitions().get(0) instanceof ErrorEventDefinition d -> {
                    // #493: an end event that raises an error; the code is part of the hash.
                    step.put("type", "errorEnd");
                    step.put("errorCode", d.errorCode());
                }
                case EndEvent e when e.eventDefinitions().size() == 1
                        && e.eventDefinitions().get(0) instanceof EscalationEventDefinition d -> {
                    step.put("type", "escalationEnd");
                    if (d.escalationCode() != null) {
                        step.put("escalationCode", d.escalationCode());
                    }
                }
                case EndEvent e when e.eventDefinitions().size() == 1
                        && e.eventDefinitions().get(0) instanceof TerminateEventDefinition ->
                        step.put("type", "terminateEnd");
                case EndEvent e when e.eventDefinitions().size() == 1
                        && e.eventDefinitions().get(0) instanceof CompensateEventDefinition d -> {
                    // #488: an end event that undoes its scope, or one activity of it, before it ends.
                    step.put("type", "compensationEnd");
                    if (d.activityRef() != null) {
                        step.put("activity", d.activityRef());
                    }
                }
                case EndEvent e when e.eventDefinitions().size() == 1
                        && e.eventDefinitions().get(0) instanceof
                                io.github.dhluong90.bpm.process.internal.model.bpmn.CancelEventDefinition ->
                        step.put("type", "cancelEnd");
                case EndEvent e -> {
                    step.put("type", "end");
                    writeEventDefinition(step, e.eventDefinitions());
                }
                case BoundaryEvent b -> {
                    // #489: a boundary event, with its trigger, what it is attached to and whether it interrupts.
                    step.put("type", "boundaryEvent");
                    step.put("attachedTo", b.attachedToRef());
                    if (!b.cancelActivity()) {
                        step.put("interrupting", false);
                    }
                    if (b.eventDefinitions().size() == 1) {
                        switch (b.eventDefinitions().get(0)) {
                            case TimerEventDefinition t -> {
                                step.put("trigger", "timer");
                                if (t.timeDuration() != null && t.timeDuration().text() != null) {
                                    step.put("duration", t.timeDuration().text().strip());
                                }
                                if (t.timeDate() != null && t.timeDate().text() != null) {
                                    step.put("until", t.timeDate().text().strip());
                                }
                            }
                            case ErrorEventDefinition e -> {
                                step.put("trigger", "error");
                                if (e.errorCode() != null) {
                                    step.put("errorCode", e.errorCode());
                                }
                            }
                            case EscalationEventDefinition e -> {
                                step.put("trigger", "escalation");
                                if (e.escalationCode() != null) {
                                    step.put("escalationCode", e.escalationCode());
                                }
                            }
                            case CompensateEventDefinition c -> {
                                // #488: the compensation activity is part of the step, so it is part of the hash.
                                step.put("trigger", "compensation");
                                artifacts.stream().filter(a -> a instanceof
                                        io.github.dhluong90.bpm.process.internal.model.bpmn.Association x
                                        && b.id().equals(x.sourceRef())).findFirst().ifPresent(a ->
                                        step.put("compensatedBy", ((io.github.dhluong90.bpm.process.internal.model
                                                .bpmn.Association) a).targetRef()));
                            }
                            case io.github.dhluong90.bpm.process.internal.model.bpmn.CancelEventDefinition c ->
                                    step.put("trigger", "cancel");
                            default -> step.put("trigger", "other");
                        }
                    }
                }
                case IntermediateThrowEvent t when t.eventDefinitions().size() == 1
                        && t.eventDefinitions().get(0) instanceof EscalationEventDefinition d -> {
                    step.put("type", "escalationThrow");
                    if (d.escalationCode() != null) {
                        step.put("escalationCode", d.escalationCode());
                    }
                }
                case IntermediateThrowEvent t when t.eventDefinitions().size() == 1
                        && t.eventDefinitions().get(0) instanceof LinkEventDefinition d -> {
                    step.put("type", "linkThrow");
                    step.put("link", d.name());
                }
                case IntermediateCatchEvent c when c.eventDefinitions().size() == 1
                        && c.eventDefinitions().get(0) instanceof LinkEventDefinition d -> {
                    step.put("type", "linkCatch");
                    step.put("link", d.name());
                }
                case ServiceTask t when t.extensions().first(AgentTaskSpec.class).isPresent() -> {
                    // process-definition.v1 agentTaskStep: the whole guard spec is part of the hash (#52).
                    AgentTaskSpec agent = t.extensions().first(AgentTaskSpec.class).orElseThrow();
                    step.put("type", "agentTask");
                    step.put("agent", agent.agent());
                    if (agent.fieldTitles() != null) {
                        step.set("fieldTitles", EngineJson.JSON.readTree(agent.fieldTitles()));
                    }
                    if (agent.outputSchema() != null) {
                        step.set("outputSchema", EngineJson.JSON.readTree(agent.outputSchema()));
                    }
                    t.extensions().first(GuardrailSpec.class).ifPresent(g -> {
                        ObjectNode guard = step.putObject("guardrails");
                        guard.put("decision", g.decision());
                        guard.put("onViolation", g.onViolation());
                        if (g.reviewer() != null) {
                            int colon = g.reviewer().indexOf(':');
                            guard.putObject("reviewer").put(g.reviewer().substring(0, colon),
                                    g.reviewer().substring(colon + 1));
                        }
                    });
                    if (agent.timeout() != null) {
                        step.put("timeout", agent.timeout().toString());
                    }
                    t.extensions().first(RetryPolicy.class).ifPresent(r -> {
                        // #58: the retry policy is part of the hash, like the rest of the step.
                        ObjectNode retry = step.putObject("retry");
                        retry.put("max", r.max());
                        if (r.backoff() != null) {
                            retry.put("backoff", r.backoff().toString());
                        }
                    });
                    List<String> masked = t.extensions().all(InputMapping.class).stream().filter(InputMapping::mask)
                            .map(InputMapping::name).toList();
                    if (!masked.isEmpty()) {
                        // #59: which inputs history keeps only as keyed fingerprints is part of the hash too.
                        masked.forEach(step.putArray("maskedInputs")::add);
                    }
                    List<MemoryEntry> memory = t.extensions().all(MemoryEntry.class);
                    if (!memory.isEmpty()) {
                        // #101: what an agent step reads from earlier steps, includeMasked included, is part of the
                        // hash, so a second person approves sending a masked field. A step without memory keeps the
                        // hash it had.
                        ArrayNode entries = step.putArray("memory");
                        for (MemoryEntry m : memory) {
                            ObjectNode entry = entries.addObject();
                            entry.put("from", m.from());
                            m.fields().forEach(entry.putArray("fields")::add);
                            entry.put("as", m.as());
                            entry.put("required", m.required());
                            if (m.includeMasked()) {
                                entry.put("includeMasked", true);
                            }
                        }
                    }
                }
                case ServiceTask t -> {
                    step.put("type", "serviceTask");
                    t.extensions().first(HandlerRef.class).ifPresent(h -> {
                        step.put("handler", h.handler());
                        // Only a remote step adds keys, so the hash of every local step stays what it was.
                        if ("remote".equals(h.mode())) {
                            step.put("mode", "remote");
                            if (h.timeout() != null) {
                                step.put("timeout", h.timeout().toString());
                            }
                        }
                    });
                }
                case Task t -> step.put("type", "task");
                case ManualTask t -> step.put("type", "manualTask");
                case ScriptTask t -> {
                    step.put("type", "scriptTask");
                    step.put("script", t.script() == null ? "" : normal(t.scriptFormat(), t.script().strip()));
                }
                case BusinessRuleTask t -> {
                    step.put("type", "decision");
                    t.extensions().first(DecisionRef.class).ifPresent(d -> step.put("ruleSet", d.ruleSet()));
                }
                case UserTask t -> {
                    step.put("type", "userTask");
                    t.extensions().first(TaskAssignment.class).ifPresent(a -> {
                        int colon = a.assignee().indexOf(':');
                        step.putObject("assignee").put(a.assignee().substring(0, colon), a.assignee().substring(colon + 1));
                        if (a.fourEyes()) {
                            step.put("fourEyes", true);
                        }
                    });
                    // Only when declared, so the hash of a definition without an SLA is unchanged (PRE-008).
                    t.extensions().first(SlaSpec.class).ifPresent(sla -> {
                        ObjectNode spec = step.putObject("sla").put("duration", sla.duration().toString());
                        if (sla.calendar() != null) {
                            spec.put("calendar", sla.calendar());
                        }
                    });
                    // Likewise only when declared (PRE-178).
                    t.extensions().first(EscalationSpec.class).ifPresent(escalation -> {
                        ObjectNode spec = step.putObject("escalation");
                        ArrayNode levels = spec.putArray("levels");
                        escalation.levels().forEach(l -> levels.addObject().put("after", l.after().toString())
                                .put("to", l.to()));
                        spec.put("onClaimed", escalation.releaseClaim() ? "release" : "keep");
                    });
                }
                case ExclusiveGateway g -> {
                    step.put("type", "exclusiveGateway");
                    ArrayNode branches = step.putArray("branches");
                    for (SequenceFlow f : out) {
                        if (f.id().equals(g.defaultFlow())) {
                            continue;
                        }
                        branches.addObject().put("when", f.conditionExpression() == null ? "true"
                                : normal(f.conditionExpression().language(), f.conditionExpression().text()))
                                .put("next", f.targetRef());
                    }
                    flows.stream().filter(f -> f.id().equals(g.defaultFlow())).findFirst()
                            .ifPresent(f -> step.put("default", f.targetRef()));
                }
                case IntermediateCatchEvent c when c.eventDefinitions().size() == 1
                        && c.eventDefinitions().get(0) instanceof TimerEventDefinition timer -> {
                    // process-definition.v1 timerStep: duration or until (DESIGN section 5.1).
                    step.put("type", "timer");
                    if (timer.timeDuration() != null && timer.timeDuration().text() != null) {
                        step.put("duration", timer.timeDuration().text().strip());
                    }
                    if (timer.timeDate() != null && timer.timeDate().text() != null) {
                        step.put("until", timer.timeDate().text().strip());
                    }
                }
                case IntermediateCatchEvent c when c.eventDefinitions().size() == 1
                        && c.eventDefinitions().get(0) instanceof SignalEventDefinition signal -> {
                    step.put("type", "signalCatch");
                    step.put("signal", signal.signalRef());
                }
                case IntermediateCatchEvent c when c.eventDefinitions().size() == 1
                        && c.eventDefinitions().get(0) instanceof ConditionalEventDefinition conditional -> {
                    step.put("type", "conditionalCatch");
                    step.put("condition", normal(conditional.condition().language(), conditional.condition().text().strip()));
                }
                case IntermediateThrowEvent t when t.eventDefinitions().size() == 1
                        && t.eventDefinitions().get(0) instanceof SignalEventDefinition signal -> {
                    step.put("type", "signalThrow");
                    step.put("signal", signal.signalRef());
                }
                case ParallelGateway g -> {
                    step.put("type", "parallelGateway");
                    // A fork lists every branch in document order; a single next is written below.
                    if (out.size() > 1) {
                        ArrayNode next = step.putArray("next");
                        out.forEach(f -> next.add(f.targetRef()));
                    }
                }
                case CallActivity c -> {
                    // #92: the called definition key, the pinned version when there is one, and the outputs brought back.
                    step.put("type", "callProcess");
                    step.put("process", c.calledElement());
                    c.extensions().first(CalledVersion.class).ifPresent(v -> step.put("version", v.version()));
                    List<OutputMapping> outputs = c.extensions().all(OutputMapping.class);
                    if (!outputs.isEmpty()) {
                        ObjectNode out2 = step.putObject("outputs");
                        outputs.forEach(o -> out2.put(o.name(), o.from()));
                    }
                }
                case Transaction saga -> {
                    step.put("type", "saga");
                    writeSteps(step.putArray("steps"), saga.flowElements(), saga.artifacts());
                }
                case SubProcess sub -> {
                    // #486: an embedded sub-process nests its steps like a saga block and has no undo of its own.
                    step.put("type", "subProcess");
                    if (sub.triggeredByEvent()) {
                        // #487: an event sub-process is started by the trigger of its own start event.
                        step.put("triggeredByEvent", true);
                    }
                    writeSteps(step.putArray("steps"), sub.flowElements(), sub.artifacts());
                }
                case IntermediateThrowEvent t when t.eventDefinitions().size() == 1
                        && t.eventDefinitions().get(0) instanceof CompensateEventDefinition d -> {
                    step.put("type", "throwCompensation");
                    if (d.activityRef() != null) {
                        step.put("activity", d.activityRef());
                    }
                }
                // #491: message steps. The message name, the key variable and the mappings follow below.
                case IntermediateCatchEvent c when messageOf(c) != null -> step.put("type", "messageCatch");
                case IntermediateThrowEvent t when messageOf(t) != null -> step.put("type", "messageThrow");
                case ReceiveTask t -> step.put("type", "receiveTask");
                case SendTask t -> step.put("type", "sendTask");
                default -> step.put("type", node.getClass().getSimpleName());
            }
            // #67: only when declared, so the hash of a step without them is unchanged.
            node.extensions().first(CompensateHandlerRef.class).ifPresent(c -> step.put("compensate", c.handler()));
            node.extensions().first(OnFailure.class).ifPresent(f -> step.put("onFailure", f.action()));
            List<InputMapping> inputs = node.extensions().all(InputMapping.class);
            if (!inputs.isEmpty()) {
                ObjectNode in = step.putObject("inputs");
                inputs.forEach(i -> in.put(i.name(), i.value()));
            }
            node.extensions().first(ResultVariable.class).ifPresent(r -> step.put("resultVariable", r.name()));
            if (node instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.Activity a
                    && a.activity().isForCompensation()) {
                step.put("forCompensation", true);
            }
            boolean ends = node instanceof IntermediateThrowEvent t && (t.eventDefinitions().isEmpty()
                    || t.eventDefinitions().get(0) instanceof LinkEventDefinition);
            String message = messageOf(node);
            if (message != null) {
                step.put("message", message);
                node.extensions().first(CorrelationKey.class).ifPresent(k -> step.put("correlationKey", k.variable()));
                List<OutputMapping> mapped = node.extensions().all(OutputMapping.class);
                if (!mapped.isEmpty() && !(node instanceof CallActivity)) {
                    ObjectNode received = step.putObject("outputs");
                    mapped.forEach(o -> received.put(o.name(), o.from()));
                }
            }
            if (!(node instanceof ExclusiveGateway) && !ends && out.size() == 1) {
                step.put("next", out.get(0).targetRef());
            }
        }
    }

    /**
     * A start or end event's signal or condition, and the trigger of an event sub-process's start event (#487), written
     * only when declared so other definitions keep their hash.
     */
    private static void writeEventDefinition(ObjectNode step, List<EventDefinition> definitions) {
        if (definitions.size() != 1) {
            return;
        }
        switch (definitions.get(0)) {
            case TimerEventDefinition t -> {
                step.put("trigger", "timer");
                if (t.timeDuration() != null && t.timeDuration().text() != null) {
                    step.put("duration", t.timeDuration().text().strip());
                }
                if (t.timeDate() != null && t.timeDate().text() != null) {
                    step.put("until", t.timeDate().text().strip());
                }
            }
            case ErrorEventDefinition e -> {
                step.put("trigger", "error");
                if (e.errorCode() != null) {
                    step.put("errorCode", e.errorCode());
                }
            }
            case EscalationEventDefinition e -> {
                step.put("trigger", "escalation");
                if (e.escalationCode() != null) {
                    step.put("escalationCode", e.escalationCode());
                }
            }
            case CompensateEventDefinition c -> step.put("trigger", "compensation");
            case SignalEventDefinition signal -> step.put("signal", signal.signalRef());
            case ConditionalEventDefinition conditional -> step.put("condition", normal(conditional.condition().language(), conditional.condition().text().strip()));
            default -> { }
        }
    }

    /** @return the message name of a message event or task, or null for any other node */
    private static String messageOf(FlowNode node) {
        return switch (node) {
            case ReceiveTask t -> t.messageRef();
            case SendTask t -> t.messageRef();
            case Event e -> e.eventDefinitions().stream().filter(MessageEventDefinition.class::isInstance)
                    .map(d -> ((MessageEventDefinition) d).messageRef()).findFirst().orElse(null);
            default -> null;
        };
    }

    /**
     * @param process the process
     * @return the RFC 8785 text of the canonical document
     */
    public static String canonicalText(Process process) {
        return CanonicalJson.write(write(process));
    }

    /**
     * The text a condition or script hashes as. A FEEL text is written in the normal form of
     * {@code urn:horus-bpm:expr:v1}, so the same meaning in either language is one hash (ADR-0026); a v1 text stays as
     * authored, so no earlier hash changes. A text that does not compile is kept as written; it never reaches a hash,
     * because import refuses it first.
     */
    private static String normal(String languageId, String text) {
        if (text == null || !io.github.dhluong90.bpm.expr.languages.ExpressionLanguages.isFeel(languageId)) {
            return text;
        }
        try {
            return io.github.dhluong90.bpm.expr.languages.ExpressionLanguages.normalForm(languageId, text.strip());
        } catch (io.github.dhluong90.bpm.expr.api.ExpressionInvalidException e) {
            return text;
        }
    }
}
