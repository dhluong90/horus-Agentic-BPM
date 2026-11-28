package io.github.dhluong90.bpm.expr.feel;

import java.time.ZoneId;

/**
 * The per-evaluation settings that are part of a versioned decision model, so replay is deterministic.
 *
 * @param errorMode how errors are treated; {@code strict} for v1 rule sets, {@code spec} for imported DMN models
 * @param zone the model's time zone
 * @param stepBudget counted steps, default 100,000
 * @param maxDepth recursion depth, default 64
 */
public record FeelOptions(ErrorMode errorMode, ZoneId zone, int stepBudget, int maxDepth) {

    /** Error handling of a decision model (PD-066). */
    public enum ErrorMode {
        /** FEEL semantics: a type or argument error gives null and a warning on the outcome. */
        SPEC,
        /** The error ends the evaluation with {@code EVALUATION_ERROR}. */
        STRICT
    }

    /** The class of a recoverable FEEL error; guardrails are not in this list because they never recover. */
    public enum ErrorKind {
        TYPE_MISMATCH, DIVISION_BY_ZERO, UNKNOWN_NAME, NOT_A_FUNCTION, WRONG_ARGUMENTS, INVALID_ARGUMENT,
        OUT_OF_RANGE, NOT_A_LIST, INVALID_TEMPORAL, UNSUPPORTED
    }

    /**
     * Guardrails fail closed in both modes and are never null-valued: step budget, recursion depth, regular
     * expression budget, input size, and a currency mismatch of money.
     */
    public enum Guardrail {
        STEP_BUDGET, RECURSION_DEPTH, REGEX_BUDGET, INPUT_TOO_LARGE, CURRENCY_MISMATCH
    }
}
