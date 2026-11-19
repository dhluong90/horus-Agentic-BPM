package io.github.dhluong90.bpm.process.api;

/** The caller lacks the engine permission the operation needs. Code {@code PERMISSION_DENIED}. */
public final class PermissionDeniedException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail */
    public PermissionDeniedException(String message) {
        super("PERMISSION_DENIED", message);
    }
}
