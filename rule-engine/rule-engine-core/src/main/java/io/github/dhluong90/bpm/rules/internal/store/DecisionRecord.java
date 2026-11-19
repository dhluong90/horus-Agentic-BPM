package io.github.dhluong90.bpm.rules.internal.store;

import java.time.Instant;

/**
 * One row of the append-only decision log {@code rle_decision}.
 *
 * @param tenantId the tenant
 * @param id the decision id (ULID)
 * @param createdAt the row time; the partition key
 * @param ruleSet the rule set name
 * @param version the version number
 * @param hitPolicy the hit policy name
 * @param outcome {@code OK}, {@code UNIQUE_HIT_VIOLATION} or {@code EVALUATION_ERROR}
 * @param errorCode the error code, or null
 * @param maskedInputsJson inputs as JSON, masked inputs replaced
 * @param outputsJson outputs as JSON, or null
 * @param matchedRuleIdsJson matched rule ids as a JSON array
 * @param callerId the caller, or null
 * @param correlationId the correlation id, or null
 * @param evalClock the evaluation clock
 * @param durationMicros evaluation time in microseconds
 */
public record DecisionRecord(
        String tenantId, String id, Instant createdAt, String ruleSet, int version, String hitPolicy, String outcome,
        String errorCode, String maskedInputsJson, String outputsJson, String matchedRuleIdsJson, String callerId,
        String correlationId, Instant evalClock, long durationMicros) {
}
