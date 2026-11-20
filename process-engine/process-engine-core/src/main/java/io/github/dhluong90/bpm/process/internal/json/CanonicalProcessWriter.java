package io.github.dhluong90.bpm.process.internal.json;

import io.github.dhluong90.bpm.process.internal.model.bpmn.BusinessRuleTask;
import io.github.dhluong90.bpm.process.internal.model.bpmn.EndEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ExclusiveGateway;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowElement;
import io.github.dhluong90.bpm.process.internal.model.bpmn.FlowNode;
import io.github.dhluong90.bpm.process.internal.model.bpmn.IntermediateCatchEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ParallelGateway;
import io.github.dhluong90.bpm.process.internal.model.bpmn.Process;
import io.github.dhluong90.bpm.process.internal.model.bpmn.SequenceFlow;
import io.github.dhluong90.bpm.process.internal.model.bpmn.ServiceTask;
import io.github.dhluong90.bpm.process.internal.model.bpmn.StartEvent;
import io.github.dhluong90.bpm.process.internal.model.bpmn.TimerEventDefinition;
import io.github.dhluong90.bpm.process.internal.model.bpmn.UserTask;
import io.github.dhluong90.bpm.process.internal.model.horus.DecisionRef;
import io.github.dhluong90.bpm.process.internal.model.horus.HandlerRef;
import io.github.dhluong90.bpm.process.internal.model.horus.InputMapping;
import io.github.dhluong90.bpm.process.internal.model.horus.ResultVariable;
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
        ArrayNode steps = doc.putArray("steps");
        List<SequenceFlow> flows = process.flowElements().stream().filter(SequenceFlow.class::isInstance)
                .map(SequenceFlow.class::cast).toList();
        for (FlowElement element : process.flowElements()) {
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
                case StartEvent s -> step.put("type", "start");
                case EndEvent e -> step.put("type", "end");
                case ServiceTask t -> {
                    step.put("type", "serviceTask");
                    t.extensions().first(HandlerRef.class).ifPresent(h -> step.put("handler", h.handler()));
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
                }
                case ExclusiveGateway g -> {
                    step.put("type", "exclusiveGateway");
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
                case ParallelGateway g -> {
                    step.put("type", "parallelGateway");
                    // A fork lists every branch in document order; a single next is written below.
                    if (out.size() > 1) {
                        ArrayNode next = step.putArray("next");
                        out.forEach(f -> next.add(f.targetRef()));
                    }
                }
                default -> step.put("type", node.getClass().getSimpleName());
            }
            List<InputMapping> inputs = node.extensions().all(InputMapping.class);
            if (!inputs.isEmpty()) {
                ObjectNode in = step.putObject("inputs");
                inputs.forEach(i -> in.put(i.name(), i.value()));
            }
            node.extensions().first(ResultVariable.class).ifPresent(r -> step.put("resultVariable", r.name()));
            if (!(node instanceof ExclusiveGateway) && out.size() == 1) {
                step.put("next", out.get(0).targetRef());
            }
        }
        return doc;
    }

    /**
     * @param process the process
     * @return the RFC 8785 text of the canonical document
     */
    public static String canonicalText(Process process) {
        return CanonicalJson.write(write(process));
    }
}
