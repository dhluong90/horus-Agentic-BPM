package io.github.dhluong90.bpm.process.internal.json;

import io.github.dhluong90.bpm.process.internal.model.bpmn.Activity;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ActivityAttributes;
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
import io.github.dhluong90.bpm.process.internal.model.bpmn.ComplexGateway;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ConditionalEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.IntermediateCatchEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SignalEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.InclusiveGateway;
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
import io.github.dhluong90.bpm.process.internal.model.horus.FormSpec;
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
        writeDataElements(doc, process.flowElements());
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
                    if (s.eventDefinitions().size() > 1) {
                        writeMultiple(step, s.eventDefinitions(), s.parallelMultiple());
                    }
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
                    if (!d.waitForCompletion()) {
                        step.put("background", true);
                    }
                }
                case EndEvent e when e.eventDefinitions().size() == 1
                        && e.eventDefinitions().get(0) instanceof
                                io.github.dhluong90.bpm.process.internal.model.bpmn.CancelEventDefinition ->
                        step.put("type", "cancelEnd");
                case EndEvent e when e.eventDefinitions().size() > 1 -> {
                    // A multiple end event throws every definition, in document order (PD-149).
                    step.put("type", "multipleEnd");
                    writeMultiple(step, e.eventDefinitions(), false);
                }
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
                            case MessageEventDefinition m -> step.put("trigger", "message");
                            case SignalEventDefinition sg -> {
                                step.put("trigger", "signal");
                                step.put("signal", sg.signalRef());
                            }
                            case ConditionalEventDefinition c -> {
                                step.put("trigger", "condition");
                                step.put("condition", normal(c.condition().language(), c.condition().text().strip()));
                            }
                            default -> step.put("trigger", "other");
                        }
                    } else {
                        writeMultiple(step, b.eventDefinitions(), b.parallelMultiple());
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
                        // No assignee only in a draft with an open binding (PRE-390); publish refuses it.
                        if (a.assignee() != null) {
                            int colon = a.assignee().indexOf(':');
                            step.putObject("assignee").put(a.assignee().substring(0, colon),
                                    a.assignee().substring(colon + 1));
                        }
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
                    // Likewise only when declared (PRE-031): a key, a schema path with its hints, or a link.
                    t.extensions().first(FormSpec.class).ifPresent(form -> {
                        if (form.formKey() != null) {
                            step.put("form", form.formKey());
                        } else if (form.link() != null) {
                            step.putObject("form").put("link", form.link());
                        } else {
                            ObjectNode spec = step.putObject("form").put("schema", form.schema());
                            if (form.hints() != null) {
                                spec.set("hints", EngineJson.JSON.readTree(form.hints()));
                            }
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
                case InclusiveGateway g -> {
                    step.put("type", "inclusiveGateway");
                    // Every branch whose condition holds is taken; a branch with no condition is always taken.
                    ArrayNode branches = step.putArray("branches");
                    for (SequenceFlow f : out) {
                        if (f.id().equals(g.defaultFlow())) {
                            continue;
                        }
                        branches.addObject().put("when", f.conditionExpression() == null ? "true"
                                : f.conditionExpression().text()).put("next", f.targetRef());
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
                case io.github.dhluong90.bpm.process.internal.model.bpmn.EventBasedGateway g -> {
                    // #495: the catch events it waits on, in document order.
                    step.put("type", "eventBasedGateway");
                    ArrayNode next = step.putArray("next");
                    out.forEach(f -> next.add(f.targetRef()));
                    if (g.instantiate()) {
                        // #499: the gateway starts the instance; the names of its signal and message catches let a
                        // broadcast or a message find the definition.
                        step.put("instantiate", true);
                        if (g.eventGatewayType()
                                == io.github.dhluong90.bpm.process.internal.model.bpmn.EventBasedGatewayType.PARALLEL) {
                            step.put("eventGatewayType", "parallel");
                        }
                        ArrayNode signals = step.putArray("signals");
                        ArrayNode messages = step.putArray("messages");
                        for (SequenceFlow f : out) {
                            elements.stream().filter(e -> e.id() != null && e.id().equals(f.targetRef())).findFirst()
                                    .ifPresent(target -> {
                                        if (target instanceof FlowNode catchNode) {
                                            if (signalOf(catchNode) != null) {
                                                signals.add(signalOf(catchNode));
                                            }
                                            if (messageOf(catchNode) != null) {
                                                messages.add(messageOf(catchNode));
                                            }
                                        }
                                    });
                        }
                    }
                }
                case IntermediateCatchEvent c when c.eventDefinitions().size() > 1 -> {
                    step.put("type", "multipleCatch");
                    writeMultiple(step, c.eventDefinitions(), c.parallelMultiple());
                }
                case ComplexGateway g -> {
                    // #500: the activation condition, every outgoing branch with its condition, and the default.
                    step.put("type", "complexGateway");
                    if (g.activationCondition() != null && g.activationCondition().text() != null) {
                        step.put("activation", normal(g.activationCondition().language(),
                                g.activationCondition().text()));
                    }
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
                case io.github.dhluong90.bpm.process.internal.model.bpmn.AdHocSubProcess adHoc -> {
                    // #500: ordering, completion condition and cancel flag are part of the step, so of the hash.
                    step.put("type", "adHocSubProcess");
                    step.put("ordering", adHoc.ordering() == io.github.dhluong90.bpm.process.internal.model.bpmn
                            .AdHocOrdering.SEQUENTIAL ? "sequential" : "parallel");
                    if (!adHoc.cancelRemainingInstances()) {
                        step.put("cancelRemaining", false);
                    }
                    if (adHoc.completionCondition() != null && adHoc.completionCondition().text() != null) {
                        step.put("completionCondition", normal(adHoc.completionCondition().language(),
                                adHoc.completionCondition().text()));
                    }
                    writeSteps(step.putArray("steps"), adHoc.flowElements(), adHoc.artifacts());
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
                    writeDataElements(step, saga.flowElements());
                }
                case SubProcess sub -> {
                    // #486: an embedded sub-process nests its steps like a saga block and has no undo of its own.
                    step.put("type", "subProcess");
                    if (sub.triggeredByEvent()) {
                        // #487: an event sub-process is started by the trigger of its own start event.
                        step.put("triggeredByEvent", true);
                    }
                    writeSteps(step.putArray("steps"), sub.flowElements(), sub.artifacts());
                    writeDataElements(step, sub.flowElements());
                }
                case IntermediateThrowEvent t when t.eventDefinitions().size() == 1
                        && t.eventDefinitions().get(0) instanceof CompensateEventDefinition d -> {
                    step.put("type", "throwCompensation");
                    if (d.activityRef() != null) {
                        step.put("activity", d.activityRef());
                    }
                    if (!d.waitForCompletion()) {
                        // The flow goes on while the undo runs in the background: part of the step, so of the hash.
                        step.put("background", true);
                    }
                }
                case IntermediateThrowEvent t when t.eventDefinitions().size() > 1 -> {
                    step.put("type", "multipleThrow");
                    writeMultiple(step, t.eventDefinitions(), false);
                }
                // #491: message steps. The message name, the key variable and the mappings follow below.
                case IntermediateCatchEvent c when messageOf(c) != null -> step.put("type", "messageCatch");
                case IntermediateThrowEvent t when messageOf(t) != null -> step.put("type", "messageThrow");
                case ReceiveTask t -> {
                    step.put("type", "receiveTask");
                    if (t.instantiate()) {
                        // #632: the receive task starts the process; only when set, so other hashes are unchanged.
                        step.put("instantiate", true);
                    }
                }
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
            if (node instanceof Activity a
                    && a.activity().isForCompensation()) {
                step.put("forCompensation", true);
            }
            // PRE-373: only when declared, so the hash of an activity without a default flow is unchanged. Written
            // as the flow id: two flows may lead to one target, and the hash must tell which of them is the default.
            if (node instanceof Activity a && a.activity().defaultFlow() != null) {
                step.put("defaultFlow", a.activity().defaultFlow());
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
            // #496: only when declared, so the hash of a step that does not repeat is unchanged.
            if (node instanceof Activity activity
                    && activity.activity().loopCharacteristics() != null) {
                writeLoop(step.putObject("loop"), activity.activity().loopCharacteristics());
            }
            if (node instanceof Activity activity) {
                writeDataAssociations(step, activity.activity());
            }
            if (!(node instanceof ExclusiveGateway) && !(node instanceof InclusiveGateway)
                    && !(node instanceof ComplexGateway)
                    && !(node instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.EventBasedGateway) && !ends && out.size() == 1) {
                step.put("next", out.get(0).targetRef());
            }
        }
    }

    /**
     * #499: the data objects, data object references and data store references of a container, and the data inputs
     * and outputs of an activity, written only when declared so a definition without them keeps its hash.
     */
    private static void writeDataElements(ObjectNode target, List<FlowElement> elements) {
        ArrayNode data = null;
        for (FlowElement element : elements) {
            ObjectNode entry = null;
            if (element instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.DataObject d) {
                data = data == null ? target.putArray("dataObjects") : data;
                entry = data.addObject().put("kind", "dataObject").put("id", d.id());
                entry.put("variable", d.info().otherAttributes().getOrDefault(
                        new javax.xml.namespace.QName("urn:horus-bpm:bpmn:extensions:v1", "variable"), d.id()));
            } else if (element instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.DataObjectReference d) {
                data = data == null ? target.putArray("dataObjects") : data;
                entry = data.addObject().put("kind", "dataObjectReference").put("id", d.id());
                entry.put("ref", d.dataObjectRef());
            } else if (element instanceof io.github.dhluong90.bpm.process.internal.model.bpmn.DataStoreReference d) {
                data = data == null ? target.putArray("dataObjects") : data;
                entry = data.addObject().put("kind", "dataStoreReference").put("id", d.id());
                entry.put("ref", d.dataStoreRef());
            }
            if (entry != null && ((io.github.dhluong90.bpm.process.internal.model.bpmn.FlowElement) element).name() != null) {
                entry.put("name", element.name());
            }
        }
    }

    private static void writeDataAssociations(ObjectNode step,
            ActivityAttributes a) {
        if (a.ioSpecification() == null && a.dataInputAssociations().isEmpty() && a.dataOutputAssociations().isEmpty()) {
            return;
        }
        ObjectNode data = step.putObject("data");
        if (a.ioSpecification() != null) {
            ArrayNode inputs = data.putArray("dataInputs");
            a.ioSpecification().dataInputs().forEach(d -> inputs.addObject().put("id", d.id()).put("name", d.name()));
            ArrayNode outputs = data.putArray("dataOutputs");
            a.ioSpecification().dataOutputs().forEach(d -> outputs.addObject().put("id", d.id()).put("name", d.name()));
        }
        ArrayNode in = data.putArray("in");
        a.dataInputAssociations().forEach(d -> in.addObject().put("from", String.join(",", d.sourceRefs()))
                .put("to", d.targetRef()));
        ArrayNode out = data.putArray("out");
        a.dataOutputAssociations().forEach(d -> out.addObject().put("from", String.join(",", d.sourceRefs()))
                .put("to", d.targetRef()));
    }

    /**
     * A start or end event's signal or condition, and the trigger of an event sub-process's start event (#487), written
     * only when declared so other definitions keep their hash.
     */
    private static void writeEventDefinition(ObjectNode step, List<EventDefinition> definitions) {
        if (definitions.size() != 1) {
            return;
        }
        writeOneDefinition(step, definitions);
    }

    /**
     * The definitions of a multiple event (#500): {@code multiple} fires on any one, {@code parallelMultiple} only when
     * all of them have occurred. Each is written as its own entry, so the hash covers every one.
     */
    private static void writeMultiple(ObjectNode step, List<EventDefinition> definitions, boolean parallel) {
        step.put("trigger", parallel ? "parallelMultiple" : "multiple");
        ArrayNode events = step.putArray("events");
        for (EventDefinition d : definitions) {
            ObjectNode entry = events.addObject();
            switch (d) {
                case MessageEventDefinition m -> entry.put("trigger", "message").put("message", m.messageRef());
                case SignalEventDefinition sg -> entry.put("trigger", "signal").put("signal", sg.signalRef());
                case ConditionalEventDefinition c -> entry.put("trigger", "conditional").put("condition",
                        normal(c.condition().language(), c.condition().text().strip()));
                case CompensateEventDefinition c -> {
                    entry.put("trigger", "compensation");
                    if (c.activityRef() != null) {
                        entry.put("activity", c.activityRef());
                    }
                    if (!c.waitForCompletion()) {
                        entry.put("background", true);
                    }
                }
                default -> writeOneDefinition(entry, List.of(d));
            }
        }
    }

    private static void writeOneDefinition(ObjectNode step, List<EventDefinition> definitions) {
        switch (definitions.get(0)) {
            case TimerEventDefinition t -> {
                step.put("trigger", "timer");
                if (t.timeDuration() != null && t.timeDuration().text() != null) {
                    step.put("duration", t.timeDuration().text().strip());
                }
                if (t.timeDate() != null && t.timeDate().text() != null) {
                    step.put("until", t.timeDate().text().strip());
                }
                if (t.timeCycle() != null && t.timeCycle().text() != null) {
                    step.put("cycle", t.timeCycle().text().strip());
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
    private static String signalOf(FlowNode node) {
        return node instanceof Event e ? e.eventDefinitions().stream().filter(SignalEventDefinition.class::isInstance)
                .map(d -> ((SignalEventDefinition) d).signalRef()).findFirst().orElse(null) : null;
    }

    private static String messageOf(FlowNode node) {
        return switch (node) {
            case ReceiveTask t -> t.messageRef();
            case SendTask t -> t.messageRef();
            case Event e -> e.eventDefinitions().stream().filter(MessageEventDefinition.class::isInstance)
                    .map(d -> ((MessageEventDefinition) d).messageRef()).findFirst().orElse(null);
            default -> null;
        };
    }

    /** The {@code loop} of a step: a standard loop or a multi-instance step, with only what the definition declares. */
    private static void writeLoop(ObjectNode loop,
            io.github.dhluong90.bpm.process.internal.model.bpmn.LoopCharacteristics characteristics) {
        switch (characteristics) {
            case io.github.dhluong90.bpm.process.internal.model.bpmn.StandardLoopCharacteristics standard -> {
                loop.put("kind", "loop");
                if (standard.loopMaximum() != null) {
                    loop.put("max", standard.loopMaximum());
                }
                if (standard.loopCondition() != null && standard.loopCondition().text() != null) {
                    loop.put("condition", standard.loopCondition().text().strip());
                }
                if (standard.testBefore()) {
                    loop.put("testBefore", true);
                }
            }
            case io.github.dhluong90.bpm.process.internal.model.bpmn.MultiInstanceLoopCharacteristics mi -> {
                loop.put("kind", mi.isSequential() ? "sequential" : "parallel");
                if (mi.loopDataInputRef() != null) {
                    loop.put("collection", mi.loopDataInputRef());
                }
                if (mi.inputDataItem() != null) {
                    loop.put("element", mi.inputDataItem().name());
                }
                if (mi.loopCardinality() != null && mi.loopCardinality().text() != null) {
                    loop.put("cardinality", mi.loopCardinality().text().strip());
                }
                if (mi.loopDataOutputRef() != null) {
                    loop.put("outputCollection", mi.loopDataOutputRef());
                }
                if (mi.outputDataItem() != null) {
                    loop.put("outputElement", mi.outputDataItem().name());
                }
                if (mi.completionCondition() != null && mi.completionCondition().text() != null) {
                    loop.put("completionCondition", mi.completionCondition().text().strip());
                }
            }
        }
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
