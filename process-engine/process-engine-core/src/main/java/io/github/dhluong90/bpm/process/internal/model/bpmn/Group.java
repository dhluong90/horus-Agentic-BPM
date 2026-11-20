package io.github.dhluong90.bpm.process.internal.model.bpmn;

/**
 * BPMN {@code group}.
 *
 * @param id id
 * @param categoryValueRef categoryValueRef
 * @param info info
 */
public record Group(
        String id,
        String categoryValueRef,
        ElementInfo info) implements Artifact {

    public Group {
        info = info == null ? ElementInfo.empty() : info;
    }
}
