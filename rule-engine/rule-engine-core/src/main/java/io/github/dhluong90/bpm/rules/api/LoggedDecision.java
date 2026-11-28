package io.github.dhluong90.bpm.rules.api;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One row of the decision log as stored. Masked inputs appear as {@code hmac:<hex>} fingerprints; the plain value is
 * not available anywhere in the engine after evaluation (RLE-012).
 *
 * @param decisionId the decision id (ULID)
 * @param ruleSet the rule set name
 * @param version the version evaluated
 * @param hitPolicy the hit policy of that version
 * @param outcome {@code OK}, {@code UNIQUE_HIT_VIOLATION} or {@code EVALUATION_ERROR}
 * @param errorCode the reason of a failed decision (for example {@code EVALUATION_ERROR} or
 *     {@code MASKING_KEY_MISSING}), or null
 * @param inputsJson the logged inputs as a JSON object, masked inputs replaced by their fingerprints
 * @param outputsJson the outputs as a JSON object, or null for a failed decision
 * @param matchedRuleIds the matched rule ids
 * @param callerId the caller, or null
 * @param correlationId the correlation id, or null
 * @param evalClock the instant the decision reasoned about: the selection of the version in force and
 *     {@code today()} and {@code now()}; differs from {@code evaluatedAt} when the caller chose the clock
 * @param evaluatedAt when the decision was made
 * @param durationMicros evaluation time in microseconds
 * @param pinned true when the caller named the version ({@code rules:pin}) instead of the engine selecting the
 *     version in force; set by the engine, never by a request
 * @param onBehalfOf the human an embedded engine evaluated for, for example the user who completed the task before a
 *     process decision step; null for a decision made for its caller alone or after a timer (ADR-0027)
 * @param limit the evaluation limit the decision reached, {@code STEPS} or {@code TIME}, when it failed with
 *     {@code EVALUATION_BUDGET_EXCEEDED}; null otherwise. New values may be added (RLE-233)
 */
public record LoggedDecision(String decisionId, String ruleSet, int version, String hitPolicy, String outcome,
        String errorCode, String inputsJson, String outputsJson, List<String> matchedRuleIds, String callerId,
        String correlationId, Instant evalClock, Instant evaluatedAt, long durationMicros, boolean pinned,
        String onBehalfOf, String limit) {

    /**
     * A decision that reached no evaluation limit.
     *
     * @param decisionId see the record
     * @param ruleSet see the record
     * @param version see the record
     * @param hitPolicy see the record
     * @param outcome see the record
     * @param errorCode see the record
     * @param inputsJson see the record
     * @param outputsJson see the record
     * @param matchedRuleIds see the record
     * @param callerId see the record
     * @param correlationId see the record
     * @param evalClock see the record
     * @param evaluatedAt see the record
     * @param durationMicros see the record
     * @param pinned see the record
     * @param onBehalfOf see the record
     */
    public LoggedDecision(String decisionId, String ruleSet, int version, String hitPolicy, String outcome,
            String errorCode, String inputsJson, String outputsJson, List<String> matchedRuleIds, String callerId,
            String correlationId, Instant evalClock, Instant evaluatedAt, long durationMicros, boolean pinned,
            String onBehalfOf) {
        this(decisionId, ruleSet, version, hitPolicy, outcome, errorCode, inputsJson, outputsJson, matchedRuleIds,
                callerId, correlationId, evalClock, evaluatedAt, durationMicros, pinned, onBehalfOf, null);
    }

    /**
     * A decision made for its caller alone, with no {@code onBehalfOf}.
     *
     * @param decisionId see the record
     * @param ruleSet see the record
     * @param version see the record
     * @param hitPolicy see the record
     * @param outcome see the record
     * @param errorCode see the record
     * @param inputsJson see the record
     * @param outputsJson see the record
     * @param matchedRuleIds see the record
     * @param callerId see the record
     * @param correlationId see the record
     * @param evalClock see the record
     * @param evaluatedAt see the record
     * @param durationMicros see the record
     * @param pinned see the record
     */
    public LoggedDecision(String decisionId, String ruleSet, int version, String hitPolicy, String outcome,
            String errorCode, String inputsJson, String outputsJson, List<String> matchedRuleIds, String callerId,
            String correlationId, Instant evalClock, Instant evaluatedAt, long durationMicros, boolean pinned) {
        this(decisionId, ruleSet, version, hitPolicy, outcome, errorCode, inputsJson, outputsJson, matchedRuleIds,
                callerId, correlationId, evalClock, evaluatedAt, durationMicros, pinned, null, null);
    }

    public LoggedDecision {
        Objects.requireNonNull(decisionId, "decisionId must not be null");
        Objects.requireNonNull(ruleSet, "ruleSet must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(inputsJson, "inputsJson must not be null");
        matchedRuleIds = List.copyOf(matchedRuleIds);
        Objects.requireNonNull(evalClock, "evalClock must not be null");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");
    }

    /**
     * Tells whether the decision was back-dated or forward-dated: it reasoned about another instant than the one it
     * ran at, because the caller chose the clock ({@code rules:simulate}). Derived from two server-set timestamps,
     * so no caller can unset it.
     *
     * @return true when {@code evalClock} differs from {@code evaluatedAt}
     */
    public boolean clockChosen() {
        return !evalClock.equals(evaluatedAt);
    }
}
