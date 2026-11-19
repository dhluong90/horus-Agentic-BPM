package io.github.dhluong90.bpm.process.api;

/** No instance with this id in the caller's tenant. Code {@code INSTANCE_NOT_FOUND}. */
public final class InstanceNotFoundException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail */
    public InstanceNotFoundException(String message) {
        super("INSTANCE_NOT_FOUND", message);
    }
}
