package io.github.dhluong90.bpm.process.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One offline replay run (PRE-092) and its report summary. A report is a test aid, not an audit record: it is
 * deleted {@code replay.retention} after it was created.
 *
 * @param id the run id
 * @param status {@code QUEUED}, {@code RUNNING}, {@code COMPLETED} or {@code FAILED}
 * @param definitionKey the process definition key
 * @param nodeId the agent step
 * @param from first day of the period, inclusive
 * @param to last day of the period, inclusive
 * @param candidateRuleSet the candidate guardrail rule set
 * @param candidateVersion the candidate version
 * @param candidateOutputSchema whether the run checks a candidate output schema instead of the recorded ones
 * @param summary the counts so far; final once the status is {@code COMPLETED}
 * @param failureCode why a {@code FAILED} run stopped, else null
 * @param createdBy who started it
 * @param createdAt when it was started
 * @param completedAt when it completed or failed, else null
 * @param expiresAt when the run and its report are deleted
 */
public record AgentReplayRun(String id, String status, String definitionKey, String nodeId, LocalDate from,
        LocalDate to, String candidateRuleSet, int candidateVersion, boolean candidateOutputSchema, Summary summary,
        String failureCode, String createdBy, Instant createdAt, Instant completedAt, Instant expiresAt) {

    public AgentReplayRun {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(summary, "summary must not be null");
    }

    /**
     * The counts of a report.
     *
     * @param cases recorded answers replayed so far
     * @param unchanged cases whose candidate outcome equals the recorded one
     * @param newlyAllowed cases the candidate would allow and the recorded guardrail did not
     * @param newlyRefused cases the candidate would refuse with no review, and the recorded check did not
     * @param newlyRoutedToReview cases the candidate would send to a reviewer, and the recorded check did not
     * @param overriddenByReviewer newly allowed cases whose answer a reviewer corrected: the candidate would have let
     *     through an answer a person changed
     * @param notReplayed cases that could not be replayed, for example without a guard spec; not counted again in
     *     {@code maskedInputRequired}
     * @param withMaskedFields cases with at least one masked field, left out of the check because its value was never
     *     kept
     * @param maskedInputRequired cases not replayed because the candidate guardrail or schema needs the value of a
     *     masked field ({@code MASKED_INPUT_REQUIRED}); counted apart from {@code notReplayed}
     */
    public record Summary(int cases, int unchanged, int newlyAllowed, int newlyRefused, int newlyRoutedToReview,
            int overriddenByReviewer, int notReplayed, int withMaskedFields,
            int maskedInputRequired) {

        /** An empty summary. */
        public static final Summary EMPTY = new Summary(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
