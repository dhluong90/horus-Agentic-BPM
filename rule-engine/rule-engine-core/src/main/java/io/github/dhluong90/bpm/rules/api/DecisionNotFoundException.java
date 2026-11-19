package io.github.dhluong90.bpm.rules.api;

/**
 * {@code 404 DECISION_NOT_FOUND}: no logged decision with that id in the caller's tenant. A decision of another
 * tenant answers the same, so ids do not leak.
 */
public final class DecisionNotFoundException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param decisionId the requested id
     */
    public DecisionNotFoundException(String decisionId) {
        super("DECISION_NOT_FOUND", "No decision " + decisionId);
    }
}
