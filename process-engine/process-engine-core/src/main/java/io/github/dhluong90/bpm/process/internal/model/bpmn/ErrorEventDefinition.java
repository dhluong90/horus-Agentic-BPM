package io.github.dhluong90.bpm.process.internal.model.bpmn;

/**
 * BPMN {@code errorEventDefinition}.
 *
 * @param id id
 * @param errorRef the id of the root {@code error} it refers to, or null
 * @param errorCode the {@code errorCode} of that error as the reader resolved it; null means a boundary catches every
 *     error code (#489)
 * @param info info
 */
public record ErrorEventDefinition(
        String id,
        String errorRef,
        String errorCode,
        ElementInfo info) implements EventDefinition {

    public ErrorEventDefinition {
        info = info == null ? ElementInfo.empty() : info;
    }
}
