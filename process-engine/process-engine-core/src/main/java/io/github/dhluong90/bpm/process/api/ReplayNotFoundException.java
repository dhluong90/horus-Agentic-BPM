package io.github.dhluong90.bpm.process.api;

/**
 * No replay run with this id exists in the caller's tenant, or the run has no such case: the same answer for another
 * tenant's run. Code {@code REPLAY_NOT_FOUND}.
 */
public final class ReplayNotFoundException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the message */
    public ReplayNotFoundException(String message) {
        super("REPLAY_NOT_FOUND", message);
    }
}
