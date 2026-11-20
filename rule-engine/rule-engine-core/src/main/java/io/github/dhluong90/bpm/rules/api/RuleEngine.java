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
     * Evaluates a request with its optional idempotency key, correlation id, clock and pinned version.
     *
     * <p>The clock (default the engine clock) selects the version in force: the highest approved version whose
     * {@code effectiveFrom} is at or before it (DESIGN section 3.3.1). It also feeds {@code today()} and
     * {@code now()}, read in the rule set's {@code timeZone}. A pinned version is evaluated instead of the selected
     * one. The decision row records the version and the clock, so {@link #replay(String)} gives the same result.
     *
     * @param request the request
     * @return the logged result; on a replay of the same key and request, the original result
     * @throws ClockNotAllowedException when the request sets a clock and the caller lacks {@code rules:simulate}
     * @throws VersionNotFoundException when the pinned version does not exist
     * @throws InvalidStateException when the pinned version was never approved (a draft is simulated instead)
     * @throws IdempotencyKeyReuseException when the key was used with a different request
     * @throws IdempotencyInFlightException when a request with the same key is still running
     */
    DecisionResult evaluate(DecisionRequest request);

    /**
     * Re-evaluates a logged decision of the caller's tenant with the version, inputs and clock its row recorded
     * (TR-DET-05). The version is loaded by number, never selected, so a version superseded or retired since still
     * replays. Writes nothing. Needs {@code rules:log:read}, because the answer is built from logged inputs.
     *
     * @param decisionId the logged decision's id
     * @return the result, carrying the logged decision id and clock
     * @throws DecisionNotFoundException when no such decision exists in the caller's tenant
     * @throws ReplayNotPossibleException when a masked input had a value, which the log does not hold
     * @throws UnsupportedOperationException from an implementation that has no decision log, such as a test stub;
     *     the engine's own implementation always supports it. A default, so implementations written before replay
     *     existed still compile
     */
    default DecisionResult replay(String decisionId) {
        throw new UnsupportedOperationException("This RuleEngine does not replay decisions");
    }
}
