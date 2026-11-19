package com.horus.bpm.rules.api;

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
 */
public record DecisionRequest(String ruleSet, DecisionInput input, String idempotencyKey, String correlationId) {

    public DecisionRequest {
        Objects.requireNonNull(ruleSet, "ruleSet must not be null");
        Objects.requireNonNull(input, "input must not be null");
    }

    /**
     * Creates a request without idempotency key or correlation id.
     *
     * @param ruleSet the rule set name
     * @param input the inputs
     * @return the request
     */
    public static DecisionRequest of(String ruleSet, DecisionInput input) {
        return new DecisionRequest(ruleSet, input, null, null);
    }

    /**
     * Returns a copy with an idempotency key.
     *
     * @param key the key, at most 200 characters
     * @return the new request
     */
    public DecisionRequest withIdempotencyKey(String key) {
        return new DecisionRequest(ruleSet, input, key, correlationId);
    }

    /**
     * Returns a copy with a correlation id.
     *
     * @param id the correlation id
     * @return the new request
     */
    public DecisionRequest withCorrelationId(String id) {
        return new DecisionRequest(ruleSet, input, idempotencyKey, id);
    }
}
