package io.github.dhluong90.bpm.process.internal.model.bpmn;

import java.util.Objects;

/**
 * BPMN {@code error} (named BpmnError to avoid {@code java.lang.Error}).
 *
 * @param id id
 * @param name name
 * @param errorCode errorCode
 * @param structureRef structureRef
 * @param info info
 */
public record BpmnError(
        String id,
        String name,
        String errorCode,
        String structureRef,
        ElementInfo info) implements RootElement {

    public BpmnError {
        Objects.requireNonNull(id, "id must not be null");
        info = info == null ? ElementInfo.empty() : info;
    }
}
