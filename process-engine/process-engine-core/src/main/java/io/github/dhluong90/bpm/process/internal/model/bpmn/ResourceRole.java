package io.github.dhluong90.bpm.process.internal.model.bpmn;

/**
 * BPMN {@code performer}, {@code humanPerformer} or {@code potentialOwner}.
 *
 * @param id id
 * @param kind kind
 * @param name name
 * @param resourceRef resourceRef
 * @param resourceAssignmentExpression resourceAssignmentExpression
 * @param info info
 */
public record ResourceRole(
        String id,
        ResourceRoleKind kind,
        String name,
        String resourceRef,
        Expression resourceAssignmentExpression,
        ElementInfo info) implements BaseElement {

    public ResourceRole {
        info = info == null ? ElementInfo.empty() : info;
    }
}
