package io.github.dhluong90.bpm.expr.internal;

/**
 * Bounds of one decision's evaluation (TR-SEC-01, TR-STD-23): every node of the tree the evaluator visits costs one
 * step, and a decision may take at most {@code stepBudget} steps; past it the decision fails with
 * {@code EVALUATION_ERROR}, reason {@value #EVALUATION_BUDGET_EXCEEDED}, limit {@link Limit#STEPS}. Steps are
 * counted, not timed, so a decision that stops replays the same on every machine. The wall-clock
 * {@code timeoutMillis} is the backstop for a slow machine (limit {@link Limit#TIME}). A host may tighten each bound;
 * neither can be switched off. The regular expression budgets of {@link RegexLimits} apply on top.
 *
 * <p>The unit is "expression nodes visited". The contract is the default, the ceiling and the reason code; the exact
 * count of a given expression may change in a minor release (constant folding, FEEL). One budget covers one top-level
 * evaluation, shared by every decision that evaluation requires (ADR-0027 decision port); today a top-level evaluation
 * is one decision table, and each item of a batch is a top-level evaluation of its own.
 *
 * <p>The language has no loops, so a table's worst case is bounded by its node count ({@link #worstCase}); import and
 * request-publish refuse a table whose worst case is over the budget, so the failure shows at authoring time.
 *
 * @param stepBudget steps one decision may take, 1 to {@value #STEP_BUDGET_MAX}
 * @param timeoutMillis longest time one decision may evaluate, 1 to {@value #TIMEOUT_MILLIS_MAX}
 * @param timed whether the wall-clock backstop is checked; false only for {@link #deterministic}, which a replay of a
 *     logged decision uses so that the clock of the replaying machine never decides the outcome (RLE-229)
 */
public record EvaluationLimits(int stepBudget, int timeoutMillis, boolean timed) {

    /** Reason of a decision that ran past its step budget or its time backstop. */
    public static final String EVALUATION_BUDGET_EXCEEDED = "EVALUATION_BUDGET_EXCEEDED";

    /** Reason of a decision whose host function threw, or returned a value of another type than it declared (RLE-057). */
    public static final String FUNCTION_FAILED = "FUNCTION_FAILED";

    /** Which bound an {@value #EVALUATION_BUDGET_EXCEEDED} decision reached: a stable field, not only text. */
    public enum Limit {
        /** The step budget: deterministic, the same input fails the same way on every machine. */
        STEPS,
        /** The wall-clock backstop: depends on the machine. */
        TIME
    }

    /** Default step budget: about 10 to 30 ms of the slowest nodes, far above a 200-row table (about 1,000 steps). */
    public static final int STEP_BUDGET = 100_000;

    /** Ceiling of the step budget. */
    public static final int STEP_BUDGET_MAX = 1_000_000;

    /**
     * Default time backstop. Generous on purpose: steps do not weigh the length of the strings {@code +} builds, so a
     * legitimate large {@code collect} table on a slow machine must stay well inside it; the step budget is the bound
     * that decides, the same on every machine.
     */
    public static final int TIMEOUT_MILLIS = 10_000;

    /** Ceiling of the time backstop: one decision never holds a thread longer than thirty seconds. */
    public static final int TIMEOUT_MILLIS_MAX = 30_000;

    /**
     * Creates limits with the wall-clock backstop on.
     *
     * @param stepBudget steps one decision may take
     * @param timeoutMillis longest time one decision may evaluate
     */
    public EvaluationLimits(int stepBudget, int timeoutMillis) {
        this(stepBudget, timeoutMillis, true);
    }

    public EvaluationLimits {
        if (stepBudget < 1 || stepBudget > STEP_BUDGET_MAX) {
            throw new IllegalArgumentException("horus.bpm.rules.evaluation.step-budget must be 1 to " + STEP_BUDGET_MAX + ", was "
                    + stepBudget);
        }
        if (timeoutMillis < 1 || timeoutMillis > TIMEOUT_MILLIS_MAX) {
            throw new IllegalArgumentException("horus.bpm.rules.evaluation.timeout-ms must be 1 to " + TIMEOUT_MILLIS_MAX
                    + " ms, was " + timeoutMillis);
        }
    }

    /**
     * Returns the defaults: 100,000 steps and 10,000 ms.
     *
     * @return the limits
     */
    public static EvaluationLimits defaults() {
        return new EvaluationLimits(STEP_BUDGET, TIMEOUT_MILLIS);
    }

    /**
     * Returns the same bounds with the wall-clock backstop off: only the step count can stop the decision, so the
     * outcome is the same on every machine. A replay of a logged decision uses it, with the step budget the decision
     * ran with; a decision that the clock stopped is replayed from its stored outcome, not run again.
     *
     * @return the limits without the time check
     */
    public EvaluationLimits deterministic() {
        return new EvaluationLimits(stepBudget, timeoutMillis, false);
    }

    /**
     * Returns the most steps an expression can take: its node count, as the evaluator charges one step per node it
     * visits and visits each node at most once (there are no loops).
     *
     * @param expr the tree
     * @return the worst-case steps
     */
    public static long worstCase(Expr expr) {
        return switch (expr) {
            case Expr.Literal ignored -> 1;
            case Expr.Var ignored -> 1;
            case Expr.Clock ignored -> 1;
            case Expr.Unary unary -> 1 + worstCase(unary.operand());
            case Expr.Binary binary -> 1 + worstCase(binary.left()) + worstCase(binary.right());
            case Expr.InList inList -> {
                long sum = 1 + worstCase(inList.target());
                for (Expr element : inList.elements()) {
                    sum += worstCase(element);
                }
                yield sum;
            }
            case Expr.Between between -> 1 + worstCase(between.target()) + worstCase(between.low())
                    + worstCase(between.high());
            case Expr.IsNull isNull -> 1 + worstCase(isNull.target());
            case Expr.Matches matches -> 1 + worstCase(matches.target());
            case Expr.Call call -> {
                long sum = 1;
                for (Expr arg : call.args()) {
                    sum += worstCase(arg);
                }
                yield sum;
            }
            // The call node costs the function's declared step cost (RLE-055), its arguments cost what they cost.
            case Expr.HostCall call -> {
                long sum = call.function().stepCost();
                for (Expr arg : call.args()) {
                    sum += worstCase(arg);
                }
                yield sum;
            }
        };
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
            charge(1);
        }

        /**
         * Charges several steps at once, for a host function's declared step cost (RLE-055); throws once the budget
         * or the backstop is spent.
         *
         * @param count the steps, at least 1
         */
        void charge(int count) {
            int before = used;
            used += count;
            if (used < 0 || used > limits.stepBudget()) {
                throw new EvaluationException(EVALUATION_BUDGET_EXCEEDED, Limit.STEPS.name(),
                        "the decision took more than "
                        + limits.stepBudget() + " evaluation steps");
            }
            if (limits.timed() && used / CHECK_EVERY != before / CHECK_EVERY && System.nanoTime() - deadline > 0) {
                throw new EvaluationException(EVALUATION_BUDGET_EXCEEDED, Limit.TIME.name(),
                        "the decision ran longer than "
                        + limits.timeoutMillis() + " ms");
            }
        }

    }
}
