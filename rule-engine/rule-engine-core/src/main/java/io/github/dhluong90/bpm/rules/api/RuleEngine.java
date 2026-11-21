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

    /**
     * Runs a stored version of a rule set over sample inputs, beside the version effective at the engine clock,
     * without logging anything (RLE-016). The caller needs {@code rules:simulate}; the tenant is the caller's.
     * Inputs a sample names but the effective version does not declare are left out of that side's evaluation.
     *
     * @param ruleSet the rule set name
     * @param version the version to simulate, in any status
     * @param samples 1 to {@code simulation.max-inputs} input sets (at most 1,000)
     * @return one result per sample, in order, with the samples whose outcome changed flagged
     * @throws VersionNotFoundException when the caller's tenant has no such version ({@code VERSION_NOT_FOUND})
     * @throws ValidationFailedException when the stored version does not compile ({@code VALIDATION_FAILED})
     * @throws InputInvalidException when there are no samples or too many, or a sample is invalid
     *     ({@code INPUT_INVALID}, naming the sample index)
     * @throws PermissionDeniedException when the caller lacks {@code rules:simulate}
     * @throws UnsupportedOperationException from an implementation other than the engine's, such as a host's test
     *     double written before this method existed; the engine's own implementation always supports it
     */
    default SimulationResult simulate(String ruleSet, int version, java.util.List<DecisionInput> samples) {
        throw new UnsupportedOperationException("simulate is not supported by " + getClass().getName());
    }

    /**
     * Evaluates up to {@code evaluation.max-batch} items of one rule set in one call (RLE-067). One version is
     * selected for the whole batch at the request clock. Every evaluated item is a logged decision with its own id; an
     * item that fails does not stop the others. The rows commit or roll back with the caller's transaction, or all
     * together in the call's own.
     *
     * @param request the rule set and items
     * @return one entry per item, in order
     * @throws InputInvalidException when there are no items
     * @throws BatchTooLargeException when there are more items than {@code evaluation.max-batch}
     * @throws ClockNotAllowedException when the request sets a clock and the caller lacks {@code rules:simulate}
     * @throws RuleSetNotFoundException when the tenant has no such rule set
     * @throws NoEffectiveVersionException when no published version is effective at the clock
     * @throws IdempotencyKeyReuseException when the key was used with a different batch
     * @throws IdempotencyInFlightException when a request with the same key is still running
     * @throws UnsupportedOperationException from an implementation other than the engine's
     */
    default BatchResult evaluateBatch(BatchRequest request) {
        throw new UnsupportedOperationException("evaluateBatch is not supported by " + getClass().getName());
    }

    /**
     * Evaluates a batch at the engine clock with no correlation ids or idempotency key.
     *
     * @param ruleSet the rule set name
     * @param inputs the items' inputs
     * @return one entry per item, in order
     */
    default BatchResult evaluateBatch(String ruleSet, java.util.List<DecisionInput> inputs) {
        return evaluateBatch(BatchRequest.of(ruleSet, inputs.stream().map(BatchRequest.Item::of).toList()));
    }

    /**
     * Replays real logged decisions of a rule set through a stored version, without logging anything (RLE-015). Each
     * decision the caller's tenant made in the period is evaluated twice at its logged clock: with the version it was
     * made with, loaded by number as {@link #replay(String)} does, and with the simulated version. The result counts
     * and flags the decisions whose outcome would change. A decision whose masked input had a value cannot be
     * evaluated again and is counted as not replayable, naming the inputs and never their values. All the decisions
     * of one run share one regular expression read budget ({@code evaluation.regex-max-reads-per-request}).
     *
     * <p>Needs {@code rules:simulate} and {@code rules:log:read}, because the answer is built from logged inputs. The
     * period is at most {@code query.max-range} (31 days) and the run at most {@code simulation.max-inputs} (1,000)
     * decisions, the newest first; both may be lowered by the host.
     *
     * @param ruleSet the rule set name
     * @param version the version to simulate, in any status
     * @param request the period and the most decisions to replay
     * @return one item per logged decision, newest first
     * @throws VersionNotFoundException when the caller's tenant has no such version ({@code VERSION_NOT_FOUND})
     * @throws ValidationFailedException when the stored version does not compile ({@code VALIDATION_FAILED})
     * @throws QueryInvalidException when the period or the limit is missing or out of bounds ({@code QUERY_INVALID})
     * @throws PermissionDeniedException when the caller lacks {@code rules:simulate} or {@code rules:log:read}
     * @throws UnsupportedOperationException from an implementation other than the engine's, such as a host's test
     *     double written before this method existed; the engine's own implementation always supports it
     */
    default HistorySimulationResult simulateHistory(String ruleSet, int version, HistorySimulation request) {
        throw new UnsupportedOperationException("simulateHistory is not supported by " + getClass().getName());
    }
}
