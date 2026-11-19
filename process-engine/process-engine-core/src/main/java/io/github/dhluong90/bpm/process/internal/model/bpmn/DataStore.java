package io.github.dhluong90.bpm.process.internal.model.bpmn;

import java.util.Objects;

/**
 * BPMN {@code dataStore}.
 *
 * @param id id
 * @param name name
 * @param capacity capacity
 * @param isUnlimited isUnlimited
 * @param itemSubjectRef itemSubjectRef
 * @param info info
 */
public record DataStore(
        String id,
        String name,
        Integer capacity,
        boolean isUnlimited,
        String itemSubjectRef,
        ElementInfo info) implements RootElement {

    public DataStore {
        Objects.requireNonNull(id, "id must not be null");
        info = info == null ? ElementInfo.empty() : info;
    }
}
