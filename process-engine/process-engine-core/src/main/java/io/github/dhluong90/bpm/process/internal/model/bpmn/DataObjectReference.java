package io.github.dhluong90.bpm.process.internal.model.bpmn;

import java.util.Objects;

/**
 * BPMN {@code dataObjectReference}.
 *
 * @param id id
 * @param name name
 * @param dataObjectRef dataObjectRef
 * @param itemSubjectRef itemSubjectRef
 * @param info info
 */
public record DataObjectReference(
        String id,
        String name,
        String dataObjectRef,
        String itemSubjectRef,
        ElementInfo info) implements FlowElement {

    public DataObjectReference {
        Objects.requireNonNull(id, "id must not be null");
        info = info == null ? ElementInfo.empty() : info;
    }
}
