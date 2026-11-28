package io.github.dhluong90.bpm.expr.feel;

import java.time.Instant;
import java.time.ZoneId;

/**
 * What a function or built-in may see of the evaluation: the clock, the zone, the error mode and the counted budget.
 * Nothing else, so a function cannot read the system time or locale (ADR-0028 determinism rules).
 */
public interface FeelCall {

    /** @return the evaluation instant given by the caller; never the system clock */
    Instant clock();

    /** @return the zone of the decision model, in which {@code today()} and {@code now()} are read */
    ZoneId zone();

    /** @return how errors are treated */
    FeelOptions.ErrorMode errorMode();

    /**
     * Charges steps against the one budget of the evaluation.
     *
     * @param steps the cost, at least 1
     * @throws io.github.dhluong90.bpm.expr.api.ExpressionEvaluationException when the budget or the depth is spent
     */
    void charge(int steps);

    /**
     * Records a FEEL error. In {@code spec} mode the caller returns null; in {@code strict} mode this throws.
     *
     * @param kind the error class
     * @param message what failed; never input values
     * @return null, so that a built-in can write {@code return call.error(...)}
     */
    Object error(FeelOptions.ErrorKind kind, String message);
}
