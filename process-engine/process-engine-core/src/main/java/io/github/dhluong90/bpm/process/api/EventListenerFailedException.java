package io.github.dhluong90.bpm.process.api;

/**
 * A listener of an engine event threw inside the transaction, so the state change was rolled back. Code
 * {@code EVENT_LISTENER_FAILED}.
 */
public final class EventListenerFailedException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail; never the payload */
    public EventListenerFailedException(String message) {
        super("EVENT_LISTENER_FAILED", message);
    }
}
