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
 * @param outcome {@code OK} (also for no match, with no matched rule ids), {@code UNIQUE_HIT_VIOLATION} or
 *     {@code EVALUATION_ERROR}
 * @param errorCode the error code, or null
 * @param maskedInputsJson inputs as JSON, masked inputs replaced
 * @param outputsJson outputs as JSON, or null
 * @param matchedRuleIdsJson matched rule ids as a JSON array
 * @param callerId the caller, or null
 * @param correlationId the correlation id, or null
 * @param evalClock the evaluation clock
 * @param durationMicros evaluation time in microseconds
 * @param pinned whether the caller named the version instead of the clock selecting it; set by the engine
 * @param onBehalfOf the human an embedded engine evaluated for, or null: set only through the tenant-scoped entry
 *     point (ADR-0027), never from a request
 */
public record DecisionRecord(
        String tenantId, String id, Instant createdAt, String ruleSet, int version, String hitPolicy, String outcome,
        String errorCode, String maskedInputsJson, String outputsJson, String matchedRuleIdsJson, String callerId,
        String correlationId, Instant evalClock, long durationMicros, boolean pinned, String onBehalfOf) {

    /**
     * Creates the row of a decision made for its caller alone.
     *
     * @param tenantId see the record
     * @param id see the record
     * @param createdAt see the record
     * @param ruleSet see the record
     * @param version see the record
     * @param hitPolicy see the record
     * @param outcome see the record
     * @param errorCode see the record
     * @param maskedInputsJson see the record
     * @param outputsJson see the record
     * @param matchedRuleIdsJson see the record
     * @param callerId see the record
     * @param correlationId see the record
     * @param evalClock see the record
     * @param durationMicros see the record
     * @param pinned see the record
     */
    public DecisionRecord(String tenantId, String id, Instant createdAt, String ruleSet, int version, String hitPolicy,
            String outcome, String errorCode, String maskedInputsJson, String outputsJson, String matchedRuleIdsJson,
            String callerId, String correlationId, Instant evalClock, long durationMicros, boolean pinned) {
        this(tenantId, id, createdAt, ruleSet, version, hitPolicy, outcome, errorCode, maskedInputsJson, outputsJson,
                matchedRuleIdsJson, callerId, correlationId, evalClock, durationMicros, pinned, null);
    }

    /**
     * Creates the row of a decision of the version in force.
     *
     * @param tenantId see the record
     * @param id see the record
     * @param createdAt see the record
     * @param ruleSet see the record
     * @param version see the record
     * @param hitPolicy see the record
     * @param outcome see the record
     * @param errorCode see the record
     * @param maskedInputsJson see the record
     * @param outputsJson see the record
     * @param matchedRuleIdsJson see the record
     * @param callerId see the record
     * @param correlationId see the record
     * @param evalClock see the record
     * @param durationMicros see the record
     */
    public DecisionRecord(String tenantId, String id, Instant createdAt, String ruleSet, int version, String hitPolicy,
            String outcome, String errorCode, String maskedInputsJson, String outputsJson, String matchedRuleIdsJson,
            String callerId, String correlationId, Instant evalClock, long durationMicros) {
        this(tenantId, id, createdAt, ruleSet, version, hitPolicy, outcome, errorCode, maskedInputsJson, outputsJson,
                matchedRuleIdsJson, callerId, correlationId, evalClock, durationMicros, false);
    }

    /**
     * Tells whether the decision reasoned about another instant than the one it ran at (a chosen clock).
     *
     * @return true when {@code evalClock} differs from {@code createdAt}
     */
    public boolean clockChosen() {
        return !evalClock.equals(createdAt);
    }
}
