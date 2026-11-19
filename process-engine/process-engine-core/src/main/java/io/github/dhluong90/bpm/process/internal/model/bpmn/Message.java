package io.github.dhluong90.bpm.process.internal.model.bpmn;

import java.util.Objects;

/**
 * BPMN {@code message}.
 *
 * @param id id
 * @param name name
 * @param itemRef itemRef
 * @param info info
 */
public record Message(
        String id,
        String name,
        String itemRef,
        ElementInfo info) implements RootElement {

    public Message {
        Objects.requireNonNull(id, "id must not be null");
        info = info == null ? ElementInfo.empty() : info;
    }
}
