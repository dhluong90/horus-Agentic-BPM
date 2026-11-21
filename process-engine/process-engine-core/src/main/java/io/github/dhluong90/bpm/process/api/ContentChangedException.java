package io.github.dhluong90.bpm.process.api;

/**
 * {@code 409 CONTENT_CHANGED}: the content of a definition version is not the content the approver reviewed
 * (PRE-177, PD-139). Nothing was written. The exception names the hash and lock version stored now, so a client can
 * say "changed since you opened it" and reload.
 */
public final class ContentChangedException extends ProcessException {

    private static final long serialVersionUID = 1L;

    private final String currentDefinitionHash;
    private final long currentLockVersion;

    /**
     * @param message what changed
     * @param currentDefinitionHash the {@code definitionHash} of the version now stored
     * @param currentLockVersion the lock version now stored
     */
    public ContentChangedException(String message, String currentDefinitionHash, long currentLockVersion) {
        super("CONTENT_CHANGED", message);
        this.currentDefinitionHash = currentDefinitionHash;
        this.currentLockVersion = currentLockVersion;
    }

    /** @return the hash of the content now stored */
    public String currentDefinitionHash() {
        return currentDefinitionHash;
    }

    /** @return the lock version now stored */
    public long currentLockVersion() {
        return currentLockVersion;
    }
}
