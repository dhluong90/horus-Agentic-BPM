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
         * The result was checked and refused (#363): it broke the step's output schema, its guardrail said no, or the
         * guardrail could not be evaluated. Nothing of the result was accepted. A malformed result counts as a failed
         * attempt and the retry policy applies; a refused one sends the step to a review task or fails its job.
         */
        REFUSED
    }
}
