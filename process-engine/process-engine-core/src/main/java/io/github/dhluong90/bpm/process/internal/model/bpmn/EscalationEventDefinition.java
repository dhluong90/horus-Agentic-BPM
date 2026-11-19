package io.github.dhluong90.bpm.process.internal.model.bpmn;

/**
 * BPMN {@code escalationEventDefinition}.
 *
 * @param id id
 * @param escalationRef escalationRef
 * @param info info
 */
public record EscalationEventDefinition(
        String id,
        String escalationRef,
        ElementInfo info) implements EventDefinition {

    public EscalationEventDefinition {
        info = info == null ? ElementInfo.empty() : info;
    }
}
