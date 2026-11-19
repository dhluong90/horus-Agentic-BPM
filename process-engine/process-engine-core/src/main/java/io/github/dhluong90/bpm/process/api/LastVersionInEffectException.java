package io.github.dhluong90.bpm.process.api;

import java.time.Instant;

/**
 * Retiring this version would leave its key with no version in effect, so new starts would fail until a new version is
 * approved (PD-101, PRE-053). Under {@code lifecycle.retire-last-version: confirm} the request needs
 * {@code confirmNoVersionInEffect}; under {@code refuse} it is always refused. Code {@code LAST_VERSION_IN_EFFECT}.
 */
public final class LastVersionInEffectException extends ProcessException {

    private static final long serialVersionUID = 1L;

    private final String definitionKey;
    private final int version;
    private final String setting;
    private final transient Instant nextEffectiveFrom;

    /**
     * @param message the detail, naming the key and what stops working
     * @param definitionKey the key
     * @param version the version that was to be retired
     * @param setting the {@code lifecycle.retire-last-version} value in force, {@code confirm} or {@code refuse}
     * @param nextEffectiveFrom when the next approved version of the key takes effect, or null when none is approved
     */
    public LastVersionInEffectException(String message, String definitionKey, int version, String setting,
            Instant nextEffectiveFrom) {
        super("LAST_VERSION_IN_EFFECT", message);
        this.definitionKey = definitionKey;
        this.version = version;
        this.setting = setting;
        this.nextEffectiveFrom = nextEffectiveFrom;
    }

    /** @return the key */
    public String definitionKey() {
        return definitionKey;
    }

    /** @return the version */
    public int version() {
        return version;
    }

    /** @return {@code confirm} or {@code refuse} */
    public String setting() {
        return setting;
    }

    /** @return the next effective date of the key, or null */
    public Instant nextEffectiveFrom() {
        return nextEffectiveFrom;
    }
}
