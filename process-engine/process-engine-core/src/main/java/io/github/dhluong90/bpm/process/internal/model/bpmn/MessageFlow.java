package io.github.dhluong90.bpm.process.internal.model.bpmn;

/**
 * BPMN {@code messageFlow}.
 *
 * @param id id
 * @param name name
 * @param sourceRef sourceRef
 * @param targetRef targetRef
 * @param messageRef messageRef
 * @param info info
 */
public record MessageFlow(
        String id,
        String name,
        String sourceRef,
        String targetRef,
        String messageRef,
        ElementInfo info) implements BaseElement {

    public MessageFlow {
        info = info == null ? ElementInfo.empty() : info;
    }
}
