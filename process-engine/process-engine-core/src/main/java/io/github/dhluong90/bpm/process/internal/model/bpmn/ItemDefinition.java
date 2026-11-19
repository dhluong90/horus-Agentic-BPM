package io.github.dhluong90.bpm.process.internal.model.bpmn;

import java.util.Objects;

/**
 * BPMN {@code itemDefinition}.
 *
 * @param id id
 * @param structureRef structureRef
 * @param itemKind itemKind
 * @param isCollection isCollection
 * @param info info
 */
public record ItemDefinition(
        String id,
        String structureRef,
        ItemKind itemKind,
        boolean isCollection,
        ElementInfo info) implements RootElement {

    public ItemDefinition {
        Objects.requireNonNull(id, "id must not be null");
        info = info == null ? ElementInfo.empty() : info;
    }
}
