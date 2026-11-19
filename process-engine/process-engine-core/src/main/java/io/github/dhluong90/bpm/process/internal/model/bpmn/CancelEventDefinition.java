package io.github.dhluong90.bpm.process.internal.model.bpmn;

/**
 * BPMN {@code cancelEventDefinition}.
 *
 * @param id id
 * @param info info
 */
public record CancelEventDefinition(
        String id,
        ElementInfo info) implements EventDefinition {

    public CancelEventDefinition {
        info = info == null ? ElementInfo.empty() : info;
    }
}
