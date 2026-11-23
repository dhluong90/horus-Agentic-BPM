package io.github.dhluong90.bpm.process.api;

/** The message matches more than one waiting instance. Code {@code MESSAGE_CORRELATION_AMBIGUOUS}; nothing runs. */
public final class MessageAmbiguousException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail, never the correlation key */
    public MessageAmbiguousException(String message) {
        super("MESSAGE_CORRELATION_AMBIGUOUS", message);
    }
}
