package io.github.dhluong90.bpm.process.internal.model.bpmn;

import java.util.Objects;

/**
 * BPMN {@code dataStoreReference}.
 *
 * @param id id
 * @param name name
 * @param dataStoreRef dataStoreRef
 * @param itemSubjectRef itemSubjectRef
 * @param info info
 */
public record DataStoreReference(
        String id,
        String name,
        String dataStoreRef,
        String itemSubjectRef,
        ElementInfo info) implements FlowElement {

    public DataStoreReference {
        Objects.requireNonNull(id, "id must not be null");
        info = info == null ? ElementInfo.empty() : info;
    }
}
