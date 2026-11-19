package io.github.dhluong90.bpm.process.internal.model.bpmn;

/**
 * BPMN {@code errorEventDefinition}.
 *
 * @param id id
 * @param errorRef errorRef
 * @param info info
 */
public record ErrorEventDefinition(
        String id,
        String errorRef,
        ElementInfo info) implements EventDefinition {

    public ErrorEventDefinition {
        info = info == null ? ElementInfo.empty() : info;
    }
}
