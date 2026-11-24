package io.github.dhluong90.bpm.process.api;

import java.util.Optional;

/**
 * Why the engine cancelled a token. It is recorded as {@code details.cause} on every {@code TOKEN_CANCELLED} history
 * entry, and decides whether a step reads {@link InstanceActivities.State#CANCELLED} (an event interrupted it) or
 * {@link InstanceActivities.State#TERMINATED} (the engine or an operator ended it). History is append-only: an entry
 * written before the cause existed has none, and reads as {@code CANCELLED} with no reason.
 */
public enum CancelCause {
    /** An interrupting error, message, signal, escalation or timer boundary event took the step over. */
    BOUNDARY(false),
    /** Another catch of the same event-based gateway fired first. */
    EVENT_GATEWAY(false),
    /** An interrupting event sub-process started in the scope. */
    INTERRUPTING_EVENT_SUBPROCESS(false),
    /** Another definition of a multiple catch event fired first. */
    MULTIPLE_EVENT(false),
    /** A multi-instance activity finished (its completion condition held) and its running repetitions were cut off. */
    MULTI_INSTANCE_COMPLETED(false),
    /** A terminate end event ended the scope. */
    TERMINATE_END(true),
    /** An ad-hoc sub-process met its completion condition and cancelled the remaining instances. */
    ADHOC_CANCEL(true),
    /** The instance was cancelled, by a caller or with its parent. */
    INSTANCE_CANCELLED(true),
    /** A failed step started the undo (compensation) and the other live work was stopped. */
    COMPENSATION(true),
    /** A cancel end event of a transaction stopped the other work of the scope. */
    TRANSACTION_CANCEL(true);

    private final boolean terminating;

    CancelCause(boolean terminating) {
        this.terminating = terminating;
    }

    /** @return true when the step reads {@code TERMINATED}, false when it reads {@code CANCELLED} */
    public boolean terminating() {
        return terminating;
    }

    /** @return the cause named by a history value, empty for null or a value this version does not know */
    public static Optional<CancelCause> of(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(value));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }
}
