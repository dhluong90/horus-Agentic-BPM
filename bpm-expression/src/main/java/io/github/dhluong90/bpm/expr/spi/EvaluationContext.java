package io.github.dhluong90.bpm.expr.spi;

import io.github.dhluong90.bpm.expr.api.ExpressionValues;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * The values and the clock of one evaluation. The clock is explicit: an expression never reads the system time, so
 * {@code today()} and {@code now()} give the same result when the evaluation is replayed (TR-DET-05).
 */
public interface EvaluationContext {

    /**
     * @param name a name or dotted path, as compiled
     * @return its value in the expression value mapping, or null when absent
     */
    Object value(String name);

    /** @return the evaluation instant, or null when the caller gives none ({@code today()} then fails) */
    Instant clock();

    /**
     * A context over a map of variables. A dotted path reads into nested maps; a missing step gives null. Values
     * are mapped by {@link ExpressionValues#toExpressionValue(Object)}.
     *
     * @param variables the variables
     * @param clock the evaluation instant, or null
     * @return the context
     */
    static EvaluationContext of(Map<String, ?> variables, Instant clock) {
        Objects.requireNonNull(variables, "variables must not be null");
        return new EvaluationContext() {
            @Override
            public Object value(String name) {
                Object current = variables;
                int from = 0;
                while (true) {
                    int dot = name.indexOf('.', from);
                    String part = dot < 0 ? name.substring(from) : name.substring(from, dot);
                    if (!(current instanceof Map<?, ?> map)) {
                        return null;
                    }
                    current = map.get(part);
                    if (dot < 0) {
                        return ExpressionValues.toExpressionValue(current);
                    }
                    from = dot + 1;
                }
            }

            @Override
            public Instant clock() {
                return clock;
            }
        };
    }
}
