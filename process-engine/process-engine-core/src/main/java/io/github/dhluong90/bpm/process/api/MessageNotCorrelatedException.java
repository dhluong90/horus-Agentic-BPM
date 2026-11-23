package io.github.dhluong90.bpm.process.api;

/** No instance waits for the message and no process starts on it. Code {@code MESSAGE_NOT_CORRELATED}; nothing is stored. */
public final class MessageNotCorrelatedException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail, never the correlation key */
    public MessageNotCorrelatedException(String message) {
        super("MESSAGE_NOT_CORRELATED", message);
    }
}
