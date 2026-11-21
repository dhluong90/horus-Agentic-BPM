package io.github.dhluong90.bpm.process.spi;

import java.time.Duration;

/**
 * Receives the engine's operational measurements (PRE-084). The engine calls it only after the transaction that did
 * the work has committed, so a rolled-back start or step is never counted. A call carries engine values only: a
 * step kind, an outcome, a job kind, an engine error code. It never carries a tenant, a business key, an instance id,
 * a user or a variable, so an implementation cannot leak one into a metric tag. Implementations must be thread-safe
 * and must not throw or block.
 *
 * <p>The starter binds an implementation to the host's {@code MeterRegistry}; the default does nothing.
 */
public interface ProcessMetrics {

    /** Does nothing. */
    ProcessMetrics NONE = new ProcessMetrics() {
    };

    /**
     * An instance start committed.
     *
     * @param definitionKey the process key; an implementation makes it a tag only when the host asked for it
     *     ({@code metrics.definition-key-tag}), because the number of keys is the host's
     */
    default void instanceStarted(String definitionKey) {
    }

    /**
     * An instance completed.
     *
     * @param definitionKey the process key, see {@link #instanceStarted}
     */
    default void instanceCompleted(String definitionKey) {
    }

    /**
     * An instance ended {@code FAILED}.
     *
     * @param definitionKey the process key, see {@link #instanceStarted}
     */
    default void instanceFailed(String definitionKey) {
    }

    /**
     * An incident was opened.
     *
     * @param errorCode the engine error code; a host business code arrives as {@code UNHANDLED_BUSINESS_ERROR}
     */
    default void incidentRaised(String errorCode) {
    }

    /**
     * One engine command was applied by the execution kernel.
     *
     * @param stepKind the command kind, for example {@code START} or {@code ASYNC_RESULT}
     * @param outcome {@code OK} or {@code FAILED}
     * @param elapsed time the kernel spent
     */
    default void stepApplied(String stepKind, String outcome, Duration elapsed) {
    }

    /**
     * An async attempt result was handled.
     *
     * @param kind the job kind, {@code ASYNC_SERVICE} or {@code ASYNC_AGENT}
     * @param outcome {@code ACCEPTED}, {@code REFUSED}, {@code FAILED}, {@code DISCARDED} or {@code DUPLICATE}
     */
    default void asyncAttempt(String kind, String outcome) {
    }

    /**
     * A timer fired.
     *
     * @param lag how long after its due time it fired, never negative
     */
    default void timerLag(Duration lag) {
    }
}
