package com.horus.bpm.rules.api;

import java.util.List;

/**
 * {@code 422 REPLAY_NOT_POSSIBLE}: the decision cannot be re-evaluated from its log entry, because an input it used is
 * masked and its value was never stored. Names the inputs, never their values.
 */
public final class ReplayNotPossibleException extends HorusBpmException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param decisionId the decision id
     * @param maskedInputs the masked inputs that had a value
     */
    public ReplayNotPossibleException(String decisionId, List<String> maskedInputs) {
        super("REPLAY_NOT_POSSIBLE", "Decision " + decisionId + " cannot be replayed: the masked input(s) "
                + maskedInputs + " were not stored");
    }
}
