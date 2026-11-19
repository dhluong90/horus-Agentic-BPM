package io.github.dhluong90.bpm.process.internal.exec;

/** Status of a token (standards-compliance section 5.1). */
public enum TokenStatus {
    ACTIVE, WAITING_USER, WAITING_ASYNC, WAITING_TIMER, WAITING_EVENT, WAITING_SCOPE, JOINED, COMPLETED, CANCELLED,
    COMPENSATED;

    /** @return whether the token still takes part in execution */
    public boolean live() {
        return this != COMPLETED && this != CANCELLED && this != COMPENSATED && this != JOINED;
    }
}
