package io.github.dhluong90.bpm.process.internal.model.bpmn;

import java.util.Objects;

/**
 * BPMN {@code signal}.
 *
 * @param id id
 * @param name name
 * @param structureRef structureRef
 * @param info info
 */
public record Signal(
        String id,
        String name,
        String structureRef,
        ElementInfo info) implements RootElement {

    public Signal {
        Objects.requireNonNull(id, "id must not be null");
        info = info == null ? ElementInfo.empty() : info;
    }
}
