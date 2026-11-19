package com.horus.bpm.rules.api;

/** No decision with this id in the caller's tenant ({@code 404 DECISION_NOT_FOUND}). */
public final class DecisionNotFoundException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param decisionId the id asked for
     */
    public DecisionNotFoundException(String decisionId) {
        super("DECISION_NOT_FOUND", "Decision " + decisionId + " not found");
    }
}
