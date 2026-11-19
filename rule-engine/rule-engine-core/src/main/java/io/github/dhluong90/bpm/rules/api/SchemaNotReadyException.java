package io.github.dhluong90.bpm.rules.api;

/**
 * {@code 503 SCHEMA_NOT_READY}: the engine's schema is not current, so a feature that depends on it (the list
 * cursor key) was not prepared at start-up. Run the engine migrations and restart.
 */
public final class SchemaNotReadyException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param detail the schema state and what to do, without personal data
     */
    public SchemaNotReadyException(String detail) {
        super("SCHEMA_NOT_READY", detail);
    }
}
