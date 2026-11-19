package io.github.dhluong90.bpm.process.internal.model.bpmn;

import java.util.Objects;

/**
 * BPMN {@code sequenceFlow}.
 *
 * @param id id
 * @param name name
 * @param sourceRef sourceRef
 * @param targetRef targetRef
 * @param conditionExpression conditionExpression
 * @param isImmediate isImmediate
 * @param info info
 */
public record SequenceFlow(
        String id,
        String name,
        String sourceRef,
        String targetRef,
        Expression conditionExpression,
        Boolean isImmediate,
        ElementInfo info) implements FlowElement {

    public SequenceFlow {
        Objects.requireNonNull(id, "id must not be null");
        info = info == null ? ElementInfo.empty() : info;
    }
}
