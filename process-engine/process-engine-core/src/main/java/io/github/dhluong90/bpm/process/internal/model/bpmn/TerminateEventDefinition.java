package io.github.dhluong90.bpm.process.internal.model.bpmn;

/**
 * BPMN {@code terminateEventDefinition}.
 *
 * @param id id
 * @param info info
 */
public record TerminateEventDefinition(
        String id,
        ElementInfo info) implements EventDefinition {

    public TerminateEventDefinition {
        info = info == null ? ElementInfo.empty() : info;
    }
}
