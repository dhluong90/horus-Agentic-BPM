package io.github.dhluong90.bpm.process.api;

/**
 * A migration is proposed or approved without a passing dry run of its plan, made now by the engine and, at approval,
 * no older than {@code migration.dry-run-validity} (PD-114, PD-131). Code {@code DRY_RUN_REQUIRED}; nothing is written.
 */
public final class DryRunRequiredException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message the detail, with the number of conflicts or the age of the dry run */
    public DryRunRequiredException(String message) {
        super("DRY_RUN_REQUIRED", message);
    }
}
