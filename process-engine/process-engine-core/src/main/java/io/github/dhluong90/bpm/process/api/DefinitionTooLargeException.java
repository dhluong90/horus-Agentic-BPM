package io.github.dhluong90.bpm.process.api;

/** The document exceeds definitions.max-bytes or definitions.max-nodes. Code {@code DEFINITION_TOO_LARGE}. */
public final class DefinitionTooLargeException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail */
    public DefinitionTooLargeException(String message) {
        super("DEFINITION_TOO_LARGE", message);
    }
}
