package io.github.dhluong90.bpm.rules.api;

import java.time.Instant;
import java.util.Objects;

/**
 * One evaluation request with its optional fields. Build with {@link #of(String, DecisionInput)} and
 * the {@code with...} methods. The tenant is never part of the request: it comes from the
 * {@code TenantResolver} (TR-SEC-03).
 *
 * @param ruleSet the rule set name
 * @param input the inputs
 * @param idempotencyKey optional key; a replay returns the logged result without a second decision row
 * @param correlationId optional correlation id written to the decision log
 * @param clock optional instant the decision reasons about; it selects the version in force and feeds
 *     {@code today()} and {@code now()}. Null means the engine clock. A caller-chosen clock needs
 *     {@code rules:simulate} ({@code CLOCK_NOT_ALLOWED} otherwise)
 * @param version optional version number to evaluate instead of selecting one; it must have been approved
 *     ({@code PUBLISHED} or {@code RETIRED}). Null means the version in force at the clock
 * @param decision optional id of the decision to evaluate within the rule set. A rule set is a DMN model with one
 *     decision named after the rule set, so the only accepted value is {@code ruleSet}; null means that decision.
 *     Reserved so that rule sets with several decisions (stage S1) need no new request type
 */
public record DecisionRequest(String ruleSet, DecisionInput input, String idempotencyKey, String correlationId,
        Instant clock, Integer version, String decision) {

    public DecisionRequest {
        Objects.requireNonNull(ruleSet, "ruleSet must not be null");
        Objects.requireNonNull(input, "input must not be null");
        if (version != null && version < 1) {
            throw new IllegalArgumentException("version must be at least 1, was " + version);
        }
    }

    /**
     * Creates a request for the rule set's own decision.
     *
     * @param ruleSet the rule set name
     * @param input the inputs
     * @param idempotencyKey optional key
     * @param correlationId optional correlation id
     * @param clock optional instant the decision reasons about
     * @param version optional approved version number
     */
    public DecisionRequest(String ruleSet, DecisionInput input, String idempotencyKey, String correlationId,
            Instant clock, Integer version) {
        this(ruleSet, input, idempotencyKey, correlationId, clock, version, null);
    }

    /**
     * Creates a request at the engine clock, selecting the version in force.
     *
     * @param ruleSet the rule set name
     * @param input the inputs
     * @param idempotencyKey optional key
     * @param correlationId optional correlation id
     */
    public DecisionRequest(String ruleSet, DecisionInput input, String idempotencyKey, String correlationId) {
        this(ruleSet, input, idempotencyKey, correlationId, null, null, null);
    }

    /**
     * Creates a request without idempotency key or correlation id.
     *
     * @param ruleSet the rule set name
     * @param input the inputs
     * @return the request
     */
    public static DecisionRequest of(String ruleSet, DecisionInput input) {
        return new DecisionRequest(ruleSet, input, null, null, null, null, null);
    }

    /**
     * Returns a copy with an idempotency key.
     *
     * @param key the key, at most 200 characters
     * @return the new request
     */
    public DecisionRequest withIdempotencyKey(String key) {
        return new DecisionRequest(ruleSet, input, key, correlationId, clock, version, decision);
    }

    /**
     * Returns a copy with a correlation id.
     *
     * @param id the correlation id
     * @return the new request
     */
    public DecisionRequest withCorrelationId(String id) {
        return new DecisionRequest(ruleSet, input, idempotencyKey, id, clock, version, decision);
    }

    /**
     * Returns a copy that reasons about another instant, for example {@code 2026-11-20T10:00:00Z}.
     *
     * @param at the instant, or null for the engine clock
     * @return the new request
     */
    public DecisionRequest withClock(Instant at) {
        return new DecisionRequest(ruleSet, input, idempotencyKey, correlationId, at, version, decision);
    }

    /**
     * Returns a copy pinned to one approved version instead of the version in force.
     *
     * @param number the version number, at least 1, or null to select
     * @return the new request
     */
    public DecisionRequest withVersion(Integer number) {
        return new DecisionRequest(ruleSet, input, idempotencyKey, correlationId, clock, number, decision);
    }

    /**
     * Returns a copy that names the decision to evaluate, for example {@code card-fee} in rule set {@code card-fee}.
     * Until rule sets hold several decisions, any other id is refused with {@code INPUT_INVALID}.
     *
     * @param id the decision id, or null for the rule set's own decision
     * @return the new request
     */
    public DecisionRequest withDecision(String id) {
        return new DecisionRequest(ruleSet, input, idempotencyKey, correlationId, clock, version, id);
    }
}
