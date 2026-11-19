package io.github.dhluong90.bpm.process.internal.model.bpmn;

/**
 * BPMN {@code compensateEventDefinition}.
 *
 * @param id id
 * @param activityRef activityRef
 * @param waitForCompletion waitForCompletion
 * @param info info
 */
public record CompensateEventDefinition(
        String id,
        String activityRef,
        boolean waitForCompletion,
        ElementInfo info) implements EventDefinition {

    public CompensateEventDefinition {
        info = info == null ? ElementInfo.empty() : info;
    }
}
