package io.github.dhluong90.bpm.rules.internal.expr;

/**
 * Bounds of one decision's evaluation (TR-SEC-01, TR-STD-23): every node of the tree the evaluator visits costs one
 * step, and a decision may take at most {@code stepBudget} steps; past it the decision fails with
 * {@code EVALUATION_ERROR}, reason {@value #EVALUATION_BUDGET_EXCEEDED}. Steps are counted, not timed, so a decision
 * that stops replays the same on every machine. The wall-clock {@code timeoutMillis} is the backstop for a slow
 * machine. A host may tighten each bound; neither can be switched off. The regular expression budgets of
 * {@link RegexLimits} apply on top.
 *
 * @param stepBudget steps one decision may take, 1 to {@value #STEP_BUDGET_MAX}
 * @param timeoutMillis longest time one decision may evaluate, 1 to {@value #TIMEOUT_MILLIS_MAX}
 */
public record EvaluationLimits(int stepBudget, int timeoutMillis) {

    /** Reason of a decision that ran past its step budget or its time backstop. */
    public static final String EVALUATION_BUDGET_EXCEEDED = "EVALUATION_BUDGET_EXCEEDED";

    /** Default step budget: about 10 to 30 ms of the slowest nodes, far above a 200-row table (about 1,000 steps). */
    public static final int STEP_BUDGET = 100_000;

    /** Ceiling of the step budget. */
    public static final int STEP_BUDGET_MAX = 1_000_000;

    /** Default time backstop. */
    public static final int TIMEOUT_MILLIS = 1000;

    /** Ceiling of the time backstop: one decision never holds a thread longer than ten seconds. */
    public static final int TIMEOUT_MILLIS_MAX = 10_000;

    public EvaluationLimits {
        if (stepBudget < 1 || stepBudget > STEP_BUDGET_MAX) {
            throw new IllegalArgumentException("the evaluation step budget must be 1 to " + STEP_BUDGET_MAX + ", was "
                    + stepBudget);
        }
        if (timeoutMillis < 1 || timeoutMillis > TIMEOUT_MILLIS_MAX) {
            throw new IllegalArgumentException("the evaluation timeout must be 1 to " + TIMEOUT_MILLIS_MAX
                    + " ms, was " + timeoutMillis);
        }
    }

    /**
     * Returns the defaults: 100,000 steps and 1,000 ms.
     *
     * @return the limits
     */
    public static EvaluationLimits defaults() {
        return new EvaluationLimits(STEP_BUDGET, TIMEOUT_MILLIS);
    }

    /**
     * Starts the budget of one decision; the backstop's clock starts now.
     *
     * @return a fresh counter
     */
    DecisionSteps newDecision() {
        return new DecisionSteps(this, System.nanoTime());
    }

    /** The steps one decision has left. One per decision, used by the evaluating thread only. */
    static final class DecisionSteps {

        /** Steps between two looks at the clock. */
        private static final int CHECK_EVERY = 1024;

        private final EvaluationLimits limits;
        private final long deadline;
        private int used;

        private DecisionSteps(EvaluationLimits limits, long startNanos) {
            this.limits = limits;
            this.deadline = startNanos + limits.timeoutMillis() * 1_000_000L;
        }

        /** Charges one step; throws once the budget or the backstop is spent. */
        void charge() {
            if (++used > limits.stepBudget()) {
                throw new EvaluationException(EVALUATION_BUDGET_EXCEEDED, "the decision took more than "
                        + limits.stepBudget() + " evaluation steps");
            }
            if (used % CHECK_EVERY == 0 && System.nanoTime() - deadline > 0) {
                throw new EvaluationException(EVALUATION_BUDGET_EXCEEDED, "the decision ran longer than "
                        + limits.timeoutMillis() + " ms");
            }
        }

    }
}
