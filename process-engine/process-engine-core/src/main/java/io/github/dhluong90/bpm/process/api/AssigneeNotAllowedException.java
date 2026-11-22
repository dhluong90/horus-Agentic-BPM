package io.github.dhluong90.bpm.process.api;

/**
 * The person a task would be handed to may not work it: they lack the task's role or group, they started the
 * instance or completed an earlier task of it on a four-eyes task, or they already hold the task. Code
 * {@code ASSIGNEE_NOT_ALLOWED} (PD-110).
 */
public final class AssigneeNotAllowedException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail */
    public AssigneeNotAllowedException(String message) {
        super("ASSIGNEE_NOT_ALLOWED", message);
    }
}
