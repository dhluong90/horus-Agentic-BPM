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
 * @param evaluatedAt when the decision was made
 * @param durationMicros evaluation time in microseconds
 */
public record LoggedDecision(String decisionId, String ruleSet, int version, String hitPolicy, String outcome,
        String errorCode, String inputsJson, String outputsJson, List<String> matchedRuleIds, String callerId,
        String correlationId, Instant evaluatedAt, long durationMicros) {

    public LoggedDecision {
        Objects.requireNonNull(decisionId, "decisionId must not be null");
        Objects.requireNonNull(ruleSet, "ruleSet must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(inputsJson, "inputsJson must not be null");
        matchedRuleIds = List.copyOf(matchedRuleIds);
        Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");
    }
}
