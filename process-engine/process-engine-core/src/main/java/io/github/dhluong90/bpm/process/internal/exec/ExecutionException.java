package io.github.dhluong90.bpm.process.internal.exec;

/**
 * A command does not apply to the state, or a step cannot continue (a condition error, a missing variable, no
 * outgoing flow). The shell rolls back the transaction and reports {@link #code()}.
 */
public final class ExecutionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final String nodeId;

    /**
     * @param code error code, for example {@code NO_OUTGOING_FLOW} or {@code CONDITION_ERROR}
     * @param nodeId the node, or null
     * @param message detail
     */
    public ExecutionException(String code, String nodeId, String message) {
        super(message);
        this.code = code;
        this.nodeId = nodeId;
    }

    /** @return the error code */
    public String code() {
        return code;
    }

    /** @return the node, or null */
    public String nodeId() {
        return nodeId;
    }
}
