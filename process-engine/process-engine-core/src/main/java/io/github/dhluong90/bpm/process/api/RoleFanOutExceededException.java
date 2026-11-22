package io.github.dhluong90.bpm.process.api;

/**
 * A task list would read more role and group queues than {@code query.max-roles} allows (#408): the caller's own roles
 * and groups each own a queue the list reads, so a caller with a very long role list cannot make one request fan out
 * without bound. Narrow the request with {@code role} or {@code group}, or ask the host to lower the caller's role
 * list. Code {@code ROLE_FAN_OUT_EXCEEDED}, answered {@code 400}.
 */
public final class RoleFanOutExceededException extends ProcessException {

    private static final long serialVersionUID = 1L;

    private final int provided;
    private final int cap;

    /**
     * @param provided how many roles and groups the list would read
     * @param cap {@code query.max-roles}
     */
    public RoleFanOutExceededException(int provided, int cap) {
        super("ROLE_FAN_OUT_EXCEEDED", "The task list would read " + provided + " role and group queues, and "
                + "query.max-roles is " + cap + "; narrow it with role or group");
        this.provided = provided;
        this.cap = cap;
    }

    /** @return how many roles and groups the list would read */
    public int provided() {
        return provided;
    }

    /** @return the cap, {@code query.max-roles} */
    public int cap() {
        return cap;
    }
}
