package io.github.dhluong90.bpm.process.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One line of a replay report (PRE-092): one recorded answer, identified by its attempt id, the key of the model call.
 * Ids, outcomes and rule ids only, never an output value.
 *
 * @param attemptId the recorded attempt
 * @param instanceId the instance
 * @param recordedAt when the answer was checked; the candidate is evaluated at this instant
 * @param recordedOutcome {@code ALLOWED}, {@code REVIEW} or {@code REFUSED}
 * @param candidateOutcome {@code ALLOWED}, {@code REVIEW}, {@code REFUSED}, or {@code NOT_REPLAYED}
 * @param change {@code UNCHANGED}, {@code NEWLY_ALLOWED}, {@code NEWLY_REFUSED}, {@code NEWLY_ROUTED_TO_REVIEW} or
 *     {@code NOT_REPLAYED}
 * @param recordedRuleIds the guardrail rules that matched when the answer was recorded
 * @param candidateRuleIds the candidate rules that matched
 * @param code the candidate's refusal code ({@code GUARDRAIL_VIOLATION}, {@code GUARDRAIL_EVALUATION_ERROR},
 *     {@code AGENT_SCHEMA_INVALID}) or why the case was not replayed ({@code GUARD_SPEC_INVALID}); null when allowed
 * @param overriddenByReviewer whether a reviewer changed this answer
 * @param maskedFields how many masked fields were left out of the check
 */
public record AgentReplayCase(String attemptId, String instanceId, Instant recordedAt, String recordedOutcome,
        String candidateOutcome, String change, List<String> recordedRuleIds, List<String> candidateRuleIds,
        String code, boolean overriddenByReviewer, int maskedFields) {

    public AgentReplayCase {
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        recordedRuleIds = recordedRuleIds == null ? List.of() : List.copyOf(recordedRuleIds);
        candidateRuleIds = candidateRuleIds == null ? List.of() : List.copyOf(candidateRuleIds);
    }
}
