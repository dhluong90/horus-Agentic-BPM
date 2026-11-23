package io.github.dhluong90.bpm.expr.languages;

import io.github.dhluong90.bpm.expr.api.ExpressionInvalidException;
import io.github.dhluong90.bpm.expr.internal.EvaluationLimits;
import io.github.dhluong90.bpm.expr.internal.Evaluator;
import io.github.dhluong90.bpm.expr.internal.ExpressionException;
import io.github.dhluong90.bpm.expr.internal.HorusExprV1;
import io.github.dhluong90.bpm.expr.internal.RegexLimits;
import io.github.dhluong90.bpm.expr.spi.ExpressionLanguage;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.util.Set;

/**
 * The expression languages this library ships, found by the language id a BPMN or DMN file carries, and the factory
 * a host uses to bound them. The Process Engine reaches the languages only through here and the
 * {@link ExpressionLanguage} SPI, never through parser or evaluator classes (ADR-0027).
 *
 * <p>Two languages: {@value #V1_ID}, and FEEL under the OMG URI of any DMN version ({@link #isFeel}). Both read
 * process variables untyped and share one evaluator, so null, number and money rules are the same in either.
 */
public final class ExpressionLanguages {

    /** The id of the HORUS expression language. */
    public static final String V1_ID = HorusExprV1.ID;

    /** The id FEEL is known by here; the other DMN versions' URIs are accepted as aliases. */
    public static final String FEEL_ID = HorusExprV1.FEEL_ID;

    private static final Set<String> FEEL_ALIASES = Set.of(FEEL_ID,
            "http://www.omg.org/spec/DMN/20180521/FEEL/", "https://www.omg.org/spec/DMN/20180521/FEEL/",
            "https://www.omg.org/spec/DMN/20230324/FEEL/", "feel");

    private static final ExpressionLanguage V1 = new HorusExprV1();
    private static final ExpressionLanguage FEEL = HorusExprV1.feel(new Evaluator(RoundingMode.HALF_EVEN),
            ZoneOffset.UTC);

    private ExpressionLanguages() {
    }

    /**
     * @param languageId a language id from a file
     * @return true when it names FEEL of any DMN version
     */
    public static boolean isFeel(String languageId) {
        return languageId != null && FEEL_ALIASES.contains(languageId);
    }

    /**
     * @param languageId a language id from a file
     * @return true when this library has the language
     */
    public static boolean supports(String languageId) {
        return V1_ID.equals(languageId) || isFeel(languageId);
    }

    /**
     * Returns a language with the default bounds.
     *
     * @param languageId {@value #V1_ID} or a FEEL URI
     * @return the language
     * @throws IllegalArgumentException when the id names no language of this library
     */
    public static ExpressionLanguage of(String languageId) {
        if (V1_ID.equals(languageId)) {
            return V1;
        }
        if (isFeel(languageId)) {
            return FEEL;
        }
        throw new IllegalArgumentException("Expression language " + languageId + " is not supported");
    }

    /**
     * Returns a language that stops an evaluation after a step budget and a time limit: what a host sets on a script
     * step, so a script cannot run away. Steps are counted, not timed, so the budget fails the same way on every
     * machine; the timeout is the backstop. Money rounds half-even and {@code today()} is read in UTC.
     *
     * @param languageId {@value #V1_ID} or a FEEL URI
     * @param stepBudget evaluation steps one run may take, each node of the expression costing one
     * @param timeoutMillis wall-clock backstop of one run, in milliseconds
     * @return the bounded language
     * @throws IllegalArgumentException when the id names no language of this library or a bound is out of range
     */
    public static ExpressionLanguage withBudget(String languageId, int stepBudget, int timeoutMillis) {
        if (!supports(languageId)) {
            throw new IllegalArgumentException("Expression language " + languageId + " is not supported");
        }
        Evaluator evaluator = new Evaluator(RoundingMode.HALF_EVEN, Evaluator.STRING_MAX_LENGTH, RegexLimits.defaults(),
                new EvaluationLimits(stepBudget, timeoutMillis));
        return isFeel(languageId) ? HorusExprV1.feel(evaluator, ZoneOffset.UTC) : new HorusExprV1(evaluator, ZoneOffset.UTC);
    }

    /**
     * Returns the normal form of an expression: {@value #V1_ID} text with minimal parentheses and one spelling for
     * strings, lists and money, so that a FEEL text and its {@value #V1_ID} spelling give the same string. A
     * definition hashes this string, so the hash follows the meaning and not the language it was typed in.
     *
     * @param languageId {@value #V1_ID} or a FEEL URI
     * @param source the text
     * @return the normal form
     * @throws ExpressionInvalidException when the text does not compile
     * @throws IllegalArgumentException when the id names no language of this library
     */
    public static String normalForm(String languageId, String source) {
        if (!supports(languageId)) {
            throw new IllegalArgumentException("Expression language " + languageId + " is not supported");
        }
        try {
            var tree = isFeel(languageId)
                    ? io.github.dhluong90.bpm.expr.internal.Parser.parseFeelWithPaths(source, name -> 0)
                    : io.github.dhluong90.bpm.expr.internal.Parser.parseWithPaths(source, name -> name.matches("[A-Z]{3}") ? -1 : 0);
            return io.github.dhluong90.bpm.expr.internal.NormalForm.print(tree);
        } catch (ExpressionException e) {
            throw new ExpressionInvalidException(e.getMessage(), e.position());
        }
    }
}
