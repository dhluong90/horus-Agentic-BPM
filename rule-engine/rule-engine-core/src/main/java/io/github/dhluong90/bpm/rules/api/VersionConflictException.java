package io.github.dhluong90.bpm.rules.api;

/**
 * {@code 409 VERSION_CONFLICT}: the expected lock version or the expected content hash is stale; reload and decide
 * again. A conflict on a version names what is stored now, so a client can say "changed since you opened it".
 */
public final class VersionConflictException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    private final String currentCanonicalHash;
    private final Long currentLockVersion;

    /**
     * Creates the exception.
     *
     * @param detail what conflicted
     */
    public VersionConflictException(String detail) {
        this(detail, null, null);
    }

    /**
     * Creates the exception for a version whose stored state differs from what the caller reviewed (RLE-084).
     *
     * @param detail what conflicted
     * @param currentCanonicalHash the {@code canonicalHash} of the version now stored
     * @param currentLockVersion the lock version now stored
     */
    public VersionConflictException(String detail, String currentCanonicalHash, Long currentLockVersion) {
        super("VERSION_CONFLICT", detail);
        this.currentCanonicalHash = currentCanonicalHash;
        this.currentLockVersion = currentLockVersion;
    }

    /**
     * Returns the hash of the content now stored.
     *
     * @return the hash, or null when the conflict does not concern one version
     */
    public String currentCanonicalHash() {
        return currentCanonicalHash;
    }

    /**
     * Returns the lock version now stored.
     *
     * @return the lock version, or null when the conflict does not concern one version
     */
    public Long currentLockVersion() {
        return currentLockVersion;
    }
}
