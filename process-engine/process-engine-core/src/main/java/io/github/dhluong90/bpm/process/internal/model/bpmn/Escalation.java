package io.github.dhluong90.bpm.process.internal.model.bpmn;

import java.util.Objects;

/**
 * BPMN {@code escalation}.
 *
 * @param id id
 * @param name name
 * @param escalationCode escalationCode
 * @param structureRef structureRef
 * @param info info
 */
public record Escalation(
        String id,
        String name,
        String escalationCode,
        String structureRef,
        ElementInfo info) implements RootElement {

    public Escalation {
        Objects.requireNonNull(id, "id must not be null");
        info = info == null ? ElementInfo.empty() : info;
    }
}
