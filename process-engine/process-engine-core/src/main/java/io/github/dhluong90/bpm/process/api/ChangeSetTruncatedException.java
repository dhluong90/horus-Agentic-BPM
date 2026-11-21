package io.github.dhluong90.bpm.process.api;

/**
 * {@code 409 CHANGE_SET_TRUNCATED}: the change list from the effective version to the version approved is cut off at
 * {@link GraphDiff#MAX_CHANGES}, so no approver can have reviewed the complete change set and no approval may rest on
 * it (PRE-177, PD-139). Nothing was written.
 */
public final class ChangeSetTruncatedException extends ProcessException {

    private static final long serialVersionUID = 1L;

    /** @param message which versions were compared */
    public ChangeSetTruncatedException(String message) {
        super("CHANGE_SET_TRUNCATED", message);
    }
}
