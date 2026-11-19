package io.github.dhluong90.bpm.process.api;

/**
 * A synchronous step failed, so the whole transaction of the call rolls back: no engine row and no host row
 * written in the same transaction survives. Code {@code STEP_FAILED}.
 */
public final class StepFailedException extends ProcessException {

    private static final long serialVersionUID = 1L;

    private final String nodeId;
    private final String reason;

    /**
     * @param nodeId the step that failed
     * @param reason the handler's or the decision's error code
     * @param message the detail
     */
    public StepFailedException(String nodeId, String reason, String message) {
        super("STEP_FAILED", "Step " + nodeId + " failed (" + reason + "): " + message);
        this.nodeId = nodeId;
        this.reason = reason;
    }

    /** @return the step id */
    public String nodeId() {
        return nodeId;
    }

    /** @return the handler's or decision's error code */
    public String reason() {
        return reason;
    }
}
