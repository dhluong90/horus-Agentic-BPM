package io.github.dhluong90.bpm.rules.api;

import java.util.List;

/**
 * {@code 422 REPLAY_NOT_POSSIBLE}: the decision cannot be re-evaluated from its log entry, because an input it used is
 * masked and its value was never stored, or because a host function it called has another version now (RLE-058).
 * Names the inputs or the function and its two version labels, never a value.
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

    private ReplayNotPossibleException(String message) {
        super("REPLAY_NOT_POSSIBLE", message);
    }

    /**
     * A decision that called a host function whose version is not the one now registered: the same inputs could give
     * another result, so a replay would not show what was decided (RLE-058).
     *
     * @param decisionId the decision id
     * @param function the function name
     * @param loggedVersion the version label the decision used
     * @param currentVersion the version label now registered, or null when the function is no longer registered
     * @return the exception
     */
    public static ReplayNotPossibleException functionChanged(String decisionId, String function, String loggedVersion,
            String currentVersion) {
        return new ReplayNotPossibleException("Decision " + decisionId + " cannot be replayed: it used function "
                + function + " version " + loggedVersion + ", and "
                + (currentVersion == null ? "the function is no longer registered" : "version " + currentVersion
                + " is registered now"));
    }
}
