package io.github.dhluong90.bpm.rules.api;

/**
 * Evaluates published rule sets. Safe for concurrent use.
 *
 * <p>Transactions: every method joins the caller's transaction when there is one, or runs in its own
 * otherwise. It reads the effective version and writes exactly one {@code rle_decision} row, which
 * commits or rolls back with the caller's work (RLE-013). It never calls the network.
 *
 * <p>Every evaluation needs a caller with {@code rules:evaluate}, resolved by the host's
 * {@code CallerResolver}; the caller is written to the decision log. Host code that evaluates on its own
 * behalf, such as a batch job, resolves to a service identity holding that permission. Otherwise the call
 * fails with {@link UnauthenticatedException} or {@link PermissionDeniedException}.
 */
public interface RuleEngine {

    /**
     * Evaluates the version of a rule set effective at the engine clock, in the caller's tenant.
     *
     * @param ruleSet the rule set name
     * @param input the inputs
     * @return the logged result
     * @throws RuleSetNotFoundException when the tenant has no such rule set ({@code RULE_SET_NOT_FOUND})
     * @throws NoEffectiveVersionException when no published version is effective ({@code NO_EFFECTIVE_VERSION})
     * @throws InputInvalidException when an input has the wrong type ({@code INPUT_INVALID})
     */
    DecisionResult evaluate(String ruleSet, DecisionInput input);

    /**
     * Evaluates a request with its optional idempotency key and correlation id.
     *
     * @param request the request
     * @return the logged result; on a replay of the same key and request, the original result
     * @throws IdempotencyKeyReuseException when the key was used with a different request
     * @throws IdempotencyInFlightException when a request with the same key is still running
     */
    DecisionResult evaluate(DecisionRequest request);
}
