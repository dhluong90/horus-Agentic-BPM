package com.horus.bpm.rules.api;

import java.util.Objects;

/**
 * Root of every exception the engine throws. {@link #code()} equals the REST error code (ADR-0016).
 */
public class HorusBpmException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    /**
     * Creates an exception with an error code.
     *
     * @param code the REST error code, for example {@code SELF_APPROVAL_FORBIDDEN}
     * @param message a human-readable detail without personal data
     */
    public HorusBpmException(String code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code must not be null");
    }

    /**
     * Returns the error code clients branch on.
     *
     * @return the error code
     */
    public String code() {
        return code;
    }
}
