package io.github.dhluong90.bpm.process.internal.model.bpmn;

import java.util.List;

/**
 * BPMN {@code dataInputAssociation}.
 *
 * @param id id
 * @param sourceRefs sourceRefs
 * @param targetRef targetRef
 * @param transformation transformation
 * @param assignments assignments
 * @param info info
 */
public record DataInputAssociation(
        String id,
        List<String> sourceRefs,
        String targetRef,
        Expression transformation,
        List<Assignment> assignments,
        ElementInfo info) implements DataAssociation {

    public DataInputAssociation {
        sourceRefs = sourceRefs == null ? List.of() : List.copyOf(sourceRefs);
        assignments = assignments == null ? List.of() : List.copyOf(assignments);
        info = info == null ? ElementInfo.empty() : info;
    }
}
