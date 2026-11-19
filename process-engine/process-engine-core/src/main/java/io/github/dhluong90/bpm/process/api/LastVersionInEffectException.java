package io.github.dhluong90.bpm.process.api;

/**
 * Retiring this version would leave its key with no version in effect, so new starts would fail until a new version is
 * approved (PD-101). Under {@code lifecycle.retire-last-version: confirm} the request needs
 * {@code confirmNoVersionInEffect}; under {@code refuse} it is always refused. Code {@code LAST_VERSION_IN_EFFECT}.
 */
public final class LastVersionInEffectException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail, naming the key and what stops working */
    public LastVersionInEffectException(String message) {
        super("LAST_VERSION_IN_EFFECT", message);
    }
}
