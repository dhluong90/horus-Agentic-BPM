package io.github.dhluong90.bpm.rules.api;

/** {@code 413 PAYLOAD_TOO_LARGE}: an imported document is larger than the import limit. */
public final class PayloadTooLargeException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param limit the largest accepted size, in bytes
     */
    public PayloadTooLargeException(long limit) {
        super("PAYLOAD_TOO_LARGE", "The document is larger than " + limit + " bytes");
    }
}
