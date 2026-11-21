package io.github.dhluong90.bpm.rules.api;

/**
 * {@code 409 CONTENT_CHANGED}: the content of a version is not the content the approver reviewed (RLE-084). It is not
 * a {@code VERSION_CONFLICT}, so a client that refreshes and retries on a conflict never re-binds the decision to
 * content nobody reviewed: the reviewer must see the new content and decide again.
 */
public final class ContentChangedException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    private final String currentCanonicalHash;
    private final long currentLockVersion;

    /**
     * Creates the exception.
     *
     * @param detail what changed
     * @param currentCanonicalHash the {@code canonicalHash} of the version now stored
     * @param currentLockVersion the lock version now stored
     */
    public ContentChangedException(String detail, String currentCanonicalHash, long currentLockVersion) {
        super("CONTENT_CHANGED", detail);
        this.currentCanonicalHash = currentCanonicalHash;
        this.currentLockVersion = currentLockVersion;
    }

    /**
     * Returns the hash of the content now stored.
     *
     * @return the hash
     */
    public String currentCanonicalHash() {
        return currentCanonicalHash;
    }

    /**
     * Returns the lock version now stored.
     *
     * @return the lock version
     */
    public long currentLockVersion() {
        return currentLockVersion;
    }
}
