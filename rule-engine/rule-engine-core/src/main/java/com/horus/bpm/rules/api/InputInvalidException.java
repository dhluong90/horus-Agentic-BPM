package com.horus.bpm.rules.api;

/** {@code 400 INPUT_INVALID}: the request or the decision inputs are malformed. */
public final class InputInvalidException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param detail what is wrong, without input values
     */
    public InputInvalidException(String detail) {
        super("INPUT_INVALID", detail);
    }
}
