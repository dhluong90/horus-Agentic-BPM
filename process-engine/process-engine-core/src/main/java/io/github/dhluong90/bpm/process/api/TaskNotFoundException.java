package io.github.dhluong90.bpm.process.api;

/** No task with this id in the caller's tenant. Code {@code TASK_NOT_FOUND}. */
public final class TaskNotFoundException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail */
    public TaskNotFoundException(String message) {
        super("TASK_NOT_FOUND", message);
    }
}
