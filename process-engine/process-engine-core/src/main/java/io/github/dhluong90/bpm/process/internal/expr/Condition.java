package io.github.dhluong90.bpm.process.internal.expr;

import io.github.dhluong90.bpm.expr.api.ExpressionEvaluationException;
import io.github.dhluong90.bpm.expr.api.ExpressionInvalidException;
import io.github.dhluong90.bpm.expr.languages.ExpressionLanguages;
import io.github.dhluong90.bpm.expr.spi.CompiledExpression;
import io.github.dhluong90.bpm.expr.spi.EvaluationContext;
import io.github.dhluong90.bpm.expr.spi.TypeEnvironment;
import java.util.Map;
import java.util.Objects;

/**
 * A compiled sequence-flow condition in {@code urn:horus-bpm:expr:v1} or FEEL, compiled and evaluated through the
 * {@link ExpressionLanguage} SPI of {@code bpm-expression} (ADR-0027): the same parser and evaluator as the Rule
 * Engine's decision tables. FEEL conditions ({@code amount > 1000 and tier = "gold"}) parse into the same tree, so the
 * rules below hold in both languages.
 *
 * <p>Process variables are typed only at evaluation, so a condition compiles in an
 * {@linkplain TypeEnvironment#untyped() untyped} environment: names may be dotted paths ({@code customer.tier}) that
 * read into nested maps, and {@code 50000 VND} is a money literal in major units. Semantics are the Rule Engine's
 * (RLE-006, PD-058): null follows FEEL three-valued logic, so an ordering with a null operand is null, {@code ==} and
 * {@code !=} are two-valued ({@code null != 'GOLD'} is true), {@code and}, {@code or} and {@code not} follow the FEEL
 * truth tables, and {@code x is null} is always true or false. A condition holds only when it is {@code true}.
 * Numbers are {@code BigDecimal} in {@code DECIMAL128}; money compares only with money of the same currency. A value
 * of the wrong type for an operator (text against a number, two currencies) is a {@link ConditionException} at run
 * time, never a silent {@code false}. Compiled once at import; thread-safe.
 */
public final class Condition {

    /** Longest condition text accepted. */
    public static final int MAX_LENGTH = 1000;

    private final CompiledExpression compiled;

    /**
     * @param languageId a language id from a BPMN file
     * @return true when conditions and scripts can be written in it: the HORUS language or FEEL
     */
    public static boolean isSupportedLanguage(String languageId) {
        return ExpressionLanguages.supports(languageId);
    }

    private Condition(CompiledExpression compiled) {
        this.compiled = compiled;
    }

    /**
     * Parses a condition.
     *
     * @param source the text
     * @return the compiled condition
     * @throws ConditionException with the position when the text is not valid
     */
    public static Condition parse(String source) {
        return parse(source, null);
    }

    /**
     * Parses a condition in a given language.
     *
     * @param source the text
     * @param languageId {@code urn:horus-bpm:expr:v1} or a FEEL URI; null is {@code urn:horus-bpm:expr:v1}
     * @return the compiled condition
     * @throws ConditionException with the position when the text is not valid, or the language is not supported
     */
    public static Condition parse(String source, String languageId) {
        Objects.requireNonNull(source, "source must not be null");
        String id = languageId == null ? ExpressionLanguages.V1_ID : languageId;
        if (!ExpressionLanguages.supports(id)) {
            throw new ConditionException("Expression language " + id + " is not supported");
        }
        if (source.isBlank() || source.length() > MAX_LENGTH) {
            throw new ConditionException("A condition must have 1 to " + MAX_LENGTH + " characters");
        }
        try {
            return new Condition(ExpressionLanguages.of(id).compile(source, TypeEnvironment.untyped()));
        } catch (ExpressionInvalidException e) {
            throw new ConditionException(e.getMessage());
        }
    }

    /**
     * Evaluates the condition.
     *
     * @param variables the visible variables
     * @return whether it holds: only {@code true} holds
     * @throws ConditionException when an operand has the wrong type or the result is not a boolean
     */
    public boolean test(Map<String, Object> variables) {
        try {
            return compiled.holds(EvaluationContext.of(variables, null));
        } catch (ExpressionEvaluationException e) {
            throw new ConditionException(e.getMessage());
        }
    }

    @Override
    public String toString() {
        return compiled.source();
    }
}
