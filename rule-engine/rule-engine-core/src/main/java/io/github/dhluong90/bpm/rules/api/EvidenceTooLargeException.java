package io.github.dhluong90.bpm.rules.api;

/** An evidence file would be larger than the export limit ({@code 422 EVIDENCE_TOO_LARGE}); choose fewer decisions. */
public final class EvidenceTooLargeException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param limit the largest file accepted, in bytes
     */
    public EvidenceTooLargeException(int limit) {
        super("EVIDENCE_TOO_LARGE", "The evidence file would be larger than " + limit
                + " bytes; export fewer decisions or versions at a time");
    }
}
