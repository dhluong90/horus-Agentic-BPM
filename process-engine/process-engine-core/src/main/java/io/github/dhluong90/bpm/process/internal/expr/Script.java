package io.github.dhluong90.bpm.process.internal.expr;

import io.github.dhluong90.bpm.expr.api.ExpressionEvaluationException;
import io.github.dhluong90.bpm.expr.api.ExpressionInvalidException;
import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.expr.internal.Evaluator;
import io.github.dhluong90.bpm.expr.internal.EvaluationLimits;
import io.github.dhluong90.bpm.expr.internal.HorusExprV1;
import io.github.dhluong90.bpm.expr.internal.RegexLimits;
import io.github.dhluong90.bpm.expr.spi.CompiledExpression;
import io.github.dhluong90.bpm.expr.spi.EvaluationContext;
import io.github.dhluong90.bpm.expr.spi.ExpressionLanguage;
import io.github.dhluong90.bpm.expr.spi.TypeEnvironment;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The body of a {@code scriptTask} in {@code urn:horus-bpm:expr:v1}: one expression whose value is the step's result.
 * It is the expression language of the conditions, not a programming language: no host objects, no reflection, no
 * loops, no clock other than the instance's, so a script cannot reach beyond the variables it is given. A script has at
 * most {@link #MAX_LENGTH} characters and runs within {@link #STEP_BUDGET} evaluation steps and
 * {@link #TIMEOUT_MILLIS} ms; past either the step fails with a {@link ConditionException}. Compiled once at import;
 * thread-safe.
 */
public final class Script {

    /** Longest script text accepted. */
    public static final int MAX_LENGTH = 1000;

    /** Evaluation steps one run may take; each node of the expression costs one. */
    public static final int STEP_BUDGET = 500;

    /** Wall-clock backstop of one run. */
    public static final int TIMEOUT_MILLIS = 1000;

    private static final ExpressionLanguage LANGUAGE = new HorusExprV1(
            new Evaluator(RoundingMode.HALF_EVEN, Evaluator.STRING_MAX_LENGTH, RegexLimits.defaults(),
                    new EvaluationLimits(STEP_BUDGET, TIMEOUT_MILLIS)),
            ZoneOffset.UTC);

    private final CompiledExpression compiled;

    private Script(CompiledExpression compiled) {
        this.compiled = compiled;
    }

    /**
     * @param source the script text
     * @return the compiled script
     * @throws ConditionException with the position when the text is blank, too long or not valid
     */
    public static Script parse(String source) {
        Objects.requireNonNull(source, "source must not be null");
        String text = source.strip();
        if (text.isEmpty() || text.length() > MAX_LENGTH) {
            throw new ConditionException("A script must have 1 to " + MAX_LENGTH + " characters");
        }
        try {
            return new Script(LANGUAGE.compile(text, TypeEnvironment.untyped()));
        } catch (ExpressionInvalidException e) {
            throw new ConditionException(e.getMessage());
        }
    }

    /**
     * Runs the script over the visible variables.
     *
     * @param variables the variables it may read
     * @return the value in a form a process variable can hold: text, a boolean, a number or a list of those
     * @throws ConditionException on a type error or when the budget is spent
     */
    public Object run(Map<String, Object> variables) {
        try {
            return storable(compiled.evaluate(EvaluationContext.of(variables, null)));
        } catch (ExpressionEvaluationException e) {
            throw new ConditionException(e.getMessage());
        }
    }

    /** Money, dates and instants are kept as their text; the rest is already a plain value. */
    private static Object storable(Object value) {
        return switch (value) {
            case Money m -> m.toString();
            case LocalDate d -> d.toString();
            case Instant i -> i.toString();
            case List<?> list -> {
                List<Object> out = new ArrayList<>(list.size());
                list.forEach(e -> out.add(storable(e)));
                yield List.copyOf(out);
            }
            case null, default -> value;
        };
    }
}
