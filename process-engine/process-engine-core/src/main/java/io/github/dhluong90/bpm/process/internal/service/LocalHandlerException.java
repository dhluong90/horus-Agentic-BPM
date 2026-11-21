package io.github.dhluong90.bpm.process.internal.service;

/**
 * A local {@code ServiceTaskHandler} threw (PRE-071). It is not a {@code ProcessException}: the step's transaction
 * rolls back with everything the handler wrote, a request answers {@code 500 INTERNAL} with a correlation id only,
 * and a job firing is retried after {@code jobs.retry-backoff}. The message names the handler, the step and the
 * exception class, never the exception's own text, which may carry personal data; the cause is kept for the host's
 * log.
 */
public final class LocalHandlerException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String handler;
    private final String nodeId;

    /**
     * @param handler the handler name
     * @param nodeId the step id
     * @param cause what the handler threw
     */
    public LocalHandlerException(String handler, String nodeId, RuntimeException cause) {
        super("Local handler " + handler + " of step " + nodeId + " threw " + cause.getClass().getName(), cause);
        this.handler = handler;
        this.nodeId = nodeId;
    }

    /** @return the handler name */
    public String handler() {
        return handler;
    }

    /** @return the step id */
    public String nodeId() {
        return nodeId;
    }
}
