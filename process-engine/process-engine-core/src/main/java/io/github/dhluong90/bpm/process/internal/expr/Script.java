package io.github.dhluong90.bpm.process.internal.expr;

import io.github.dhluong90.bpm.expr.api.ExpressionEvaluationException;
import io.github.dhluong90.bpm.expr.api.ExpressionInvalidException;
import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.expr.languages.ExpressionLanguages;
import io.github.dhluong90.bpm.expr.spi.CompiledExpression;
import io.github.dhluong90.bpm.expr.spi.EvaluationContext;
import io.github.dhluong90.bpm.expr.spi.ExpressionLanguage;
import io.github.dhluong90.bpm.expr.spi.TypeEnvironment;
import java.time.LocalDate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The body of a {@code scriptTask} in {@code urn:horus-bpm:expr:v1} or FEEL: one expression whose value is the step's result.
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

    private static final ExpressionLanguage V1 = ExpressionLanguages.withBudget(ExpressionLanguages.V1_ID, STEP_BUDGET,
            TIMEOUT_MILLIS);
    private static final ExpressionLanguage FEEL = ExpressionLanguages.withBudget(ExpressionLanguages.FEEL_ID,
            STEP_BUDGET, TIMEOUT_MILLIS);

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
        return parse(source, null);
    }

    /**
     * @param source the script text
     * @param languageId {@code urn:horus-bpm:expr:v1} or a FEEL URI; null is {@code urn:horus-bpm:expr:v1}
     * @return the compiled script
     * @throws ConditionException with the position when the text is blank, too long or not valid, or the language is
     *     not supported
     */
    public static Script parse(String source, String languageId) {
        Objects.requireNonNull(source, "source must not be null");
        String text = source.strip();
        if (text.isEmpty() || text.length() > MAX_LENGTH) {
            throw new ConditionException("A script must have 1 to " + MAX_LENGTH + " characters");
        }
        if (languageId != null && !ExpressionLanguages.supports(languageId)) {
            throw new ConditionException("Expression language " + languageId + " is not supported");
        }
        ExpressionLanguage language = ExpressionLanguages.isFeel(languageId) ? FEEL : V1;
        try {
            return new Script(language.compile(text, TypeEnvironment.untyped()));
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
