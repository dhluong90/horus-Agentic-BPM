package io.github.dhluong90.bpm.process.internal.exec;

import java.util.Map;

/**
 * How the kernel evaluates expressions until the rule side's {@code ExpressionLanguage} SPI is published; then this
 * port is implemented by a lookup of that SPI by language id. Implementations are pure and thread-safe.
 */
@FunctionalInterface
public interface ExpressionEvaluator {

    /**
     * @param expression the text
     * @param languageId the language id, or null for the definition's default
     * @param variables the visible variables
     * @return the value
     * @throws ExecutionException with code {@code CONDITION_ERROR} on a parse or type error
     */
    Object evaluate(String expression, String languageId, Map<String, Object> variables);
}
