package io.github.dhluong90.bpm.process.internal.model.bpmn;

import java.util.Objects;

/**
 * BPMN {@code dataObject}.
 *
 * @param id id
 * @param name name
 * @param itemSubjectRef itemSubjectRef
 * @param isCollection isCollection
 * @param info info
 */
public record DataObject(
        String id,
        String name,
        String itemSubjectRef,
        boolean isCollection,
        ElementInfo info) implements FlowElement {

    public DataObject {
        Objects.requireNonNull(id, "id must not be null");
        info = info == null ? ElementInfo.empty() : info;
    }
}
