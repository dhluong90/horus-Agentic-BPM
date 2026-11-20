package io.github.dhluong90.bpm.process.api;

/** The caller's tenant has no such migration plan. Code {@code MIGRATION_NOT_FOUND}. */
public final class MigrationNotFoundException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail */
    public MigrationNotFoundException(String message) {
        super("MIGRATION_NOT_FOUND", message);
    }
}
