package io.github.dhluong90.bpm.process.api;

/** The caller is not the user, a holder of the role or a member of the group the task is assigned to. Code {@code TASK_NOT_ASSIGNED}. */
public final class TaskNotAssignedException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail */
    public TaskNotAssignedException(String message) {
        super("TASK_NOT_ASSIGNED", message);
    }
}
