package io.github.dhluong90.bpm.process.api;

import java.util.Objects;

/**
 * Root of every Process Engine exception. {@link #code()} equals the REST error code (ADR-0016).
 */
public class ProcessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    /**
     * Creates the exception.
     *
     * @param code the error code, as on the REST problem body
     * @param message a message for the caller; never contains masked or secret values
     */
    public ProcessException(String code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code must not be null");
    }

    /** @return the error code, equal to the REST {@code code} member */
    public String code() {
        return code;
    }
}
