package io.github.dhluong90.bpm.process.internal.model.bpmn;

/**
 * BPMN {@code messageEventDefinition}.
 *
 * @param id id
 * @param messageRef messageRef
 * @param operationRef operationRef
 * @param info info
 */
public record MessageEventDefinition(
        String id,
        String messageRef,
        String operationRef,
        ElementInfo info) implements EventDefinition {

    public MessageEventDefinition {
        info = info == null ? ElementInfo.empty() : info;
    }
}
