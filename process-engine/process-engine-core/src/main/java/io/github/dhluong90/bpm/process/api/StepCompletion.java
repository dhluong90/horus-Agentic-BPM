package io.github.dhluong90.bpm.process.api;

import java.util.Objects;

/**
 * What the engine did with a step result.
 *
 * @param jobId the job
 * @param attemptId the attempt the result was for
 * @param outcome the outcome
 */
public record StepCompletion(String jobId, String attemptId, Outcome outcome) {

    public StepCompletion {
        Objects.requireNonNull(jobId, "jobId must not be null");
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
    }

    /** Outcome of a completion. */
    public enum Outcome {
        /** The result was recorded: the process moved on, or the attempt failed and the retry policy applied. */
        ACCEPTED,
        /** The attempt was already completed; this repeat wrote nothing. */
        DUPLICATE,
        /**
         * The attempt is no longer current: its lease ended, another attempt took the step over, or the instance was
         * cancelled. Nothing changed in the process; a cancelled step records {@code LATE_RESULT_DISCARDED}.
         */
        DISCARDED,
        /**
         * The result was checked and refused (#363), and the refusal is final: it broke the step's output schema
         * after its schema retries were used up, its guardrail said no, or the guardrail could not be evaluated.
         * Nothing of the result was accepted; the refusal is recorded, and the step goes to a four-eyes review task
         * or its job fails. A malformed result that still has a schema retry is {@link #ACCEPTED}: the attempt was
         * recorded as failed and the step gets a fresh one.
         */
        REFUSED
    }
}
