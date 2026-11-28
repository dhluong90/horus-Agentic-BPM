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
 * @param functionVersions the host functions the rule set calls, each as {@code name@version} in name order, comma
 *     separated, with the version label registered when the decision ran (RLE-058); null when it calls none
 * @param budgetLimit the evaluation limit the decision reached, {@code STEPS} or {@code TIME}, for an
 *     {@code EVALUATION_BUDGET_EXCEEDED} failure; null otherwise (RLE-229). With the two limits below this is the
 *     stored outcome that a replay uses instead of the clock
 * @param stepBudget the step budget the decision ran with, or null for a row written before it was recorded (RLE-229)
 * @param timeoutMillis the time backstop in milliseconds the decision ran with, or null as above
 */
public record DecisionRecord(
        String tenantId, String id, Instant createdAt, String ruleSet, int version, String hitPolicy, String outcome,
        String errorCode, String maskedInputsJson, String outputsJson, String matchedRuleIdsJson, String callerId,
        String correlationId, Instant evalClock, long durationMicros, boolean pinned, String onBehalfOf,
        String functionVersions, String budgetLimit, Integer stepBudget, Integer timeoutMillis) {

    /**
     * Creates the row of a decision that did not record the limits it ran with.
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
     * @param onBehalfOf see the record
     * @param functionVersions see the record
     * @param budgetLimit see the record
     */
    public DecisionRecord(String tenantId, String id, Instant createdAt, String ruleSet, int version, String hitPolicy,
            String outcome, String errorCode, String maskedInputsJson, String outputsJson, String matchedRuleIdsJson,
            String callerId, String correlationId, Instant evalClock, long durationMicros, boolean pinned,
            String onBehalfOf, String functionVersions, String budgetLimit) {
        this(tenantId, id, createdAt, ruleSet, version, hitPolicy, outcome, errorCode, maskedInputsJson, outputsJson,
                matchedRuleIdsJson, callerId, correlationId, evalClock, durationMicros, pinned, onBehalfOf,
                functionVersions, budgetLimit, null, null);
    }

    /**
     * Creates the row of a decision that reached no evaluation limit.
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
     * @param onBehalfOf see the record
     * @param functionVersions see the record
     */
    public DecisionRecord(String tenantId, String id, Instant createdAt, String ruleSet, int version, String hitPolicy,
            String outcome, String errorCode, String maskedInputsJson, String outputsJson, String matchedRuleIdsJson,
            String callerId, String correlationId, Instant evalClock, long durationMicros, boolean pinned,
            String onBehalfOf, String functionVersions) {
        this(tenantId, id, createdAt, ruleSet, version, hitPolicy, outcome, errorCode, maskedInputsJson, outputsJson,
                matchedRuleIdsJson, callerId, correlationId, evalClock, durationMicros, pinned, onBehalfOf,
                functionVersions, null);
    }

    /**
     * Creates the row of a decision that calls no host function.
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
     * @param onBehalfOf see the record
     */
    public DecisionRecord(String tenantId, String id, Instant createdAt, String ruleSet, int version, String hitPolicy,
            String outcome, String errorCode, String maskedInputsJson, String outputsJson, String matchedRuleIdsJson,
            String callerId, String correlationId, Instant evalClock, long durationMicros, boolean pinned,
            String onBehalfOf) {
        this(tenantId, id, createdAt, ruleSet, version, hitPolicy, outcome, errorCode, maskedInputsJson, outputsJson,
                matchedRuleIdsJson, callerId, correlationId, evalClock, durationMicros, pinned, onBehalfOf, null, null);
    }


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
