package io.github.dhluong90.bpm.process.internal.model.bpmn;

/**
 * BPMN {@code escalationEventDefinition}.
 *
 * @param id id
 * @param escalationRef the id of the root {@code escalation} it refers to, or null
 * @param escalationCode the {@code escalationCode} of that escalation as the reader resolved it; null means a boundary
 *     catches every escalation (#489)
 * @param info info
 */
public record EscalationEventDefinition(
        String id,
        String escalationRef,
        String escalationCode,
        ElementInfo info) implements EventDefinition {

    public EscalationEventDefinition {
        info = info == null ? ElementInfo.empty() : info;
    }
}
