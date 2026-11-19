package io.github.dhluong90.bpm.process.internal.model.bpmn;

/**
 * BPMN {@code association}.
 *
 * @param id id
 * @param sourceRef sourceRef
 * @param targetRef targetRef
 * @param associationDirection associationDirection
 * @param info info
 */
public record Association(
        String id,
        String sourceRef,
        String targetRef,
        AssociationDirection associationDirection,
        ElementInfo info) implements Artifact {

    public Association {
        info = info == null ? ElementInfo.empty() : info;
    }
}
