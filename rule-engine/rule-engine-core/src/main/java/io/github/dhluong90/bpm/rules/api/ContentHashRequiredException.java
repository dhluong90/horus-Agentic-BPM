package io.github.dhluong90.bpm.rules.api;

/**
 * {@code 400 CONTENT_HASH_REQUIRED}: {@code horus.bpm.rules.approval.require-content-hash} is on and an approval or
 * rejection did not name {@code expectedCanonicalHash}, the hash of the content the reviewer saw (RLE-084).
 */
public final class ContentHashRequiredException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception. */
    public ContentHashRequiredException() {
        super("CONTENT_HASH_REQUIRED", "expectedCanonicalHash is required: send the canonicalHash of the version the"
                + " reviewer saw (horus.bpm.rules.approval.require-content-hash)");
    }
}
