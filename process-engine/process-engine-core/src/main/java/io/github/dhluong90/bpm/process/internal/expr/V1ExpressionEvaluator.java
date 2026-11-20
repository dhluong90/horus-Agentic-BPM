package io.github.dhluong90.bpm.process.internal.expr;

import io.github.dhluong90.bpm.expr.internal.HorusExprV1;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionException;
import io.github.dhluong90.bpm.process.internal.exec.ExpressionEvaluator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code urn:horus-bpm:expr:v1} conditions through {@link Condition}, which compiles them with the
 * {@code ExpressionLanguage} SPI of {@code bpm-expression} (ADR-0027), with compiled conditions cached by text. Other
 * languages are refused.
 */
public final class V1ExpressionEvaluator implements ExpressionEvaluator {

    /** The language id this evaluator accepts. */
    public static final String LANGUAGE = HorusExprV1.ID;

    private final Map<String, Condition> cache = new ConcurrentHashMap<>();
    private final int maxCacheEntries;

    /** @param maxCacheEntries parsed conditions kept; a full cache is cleared */
    public V1ExpressionEvaluator(int maxCacheEntries) {
        this.maxCacheEntries = maxCacheEntries;
    }

    @Override
    public Object evaluate(String expression, String languageId, Map<String, Object> variables) {
        if (languageId != null && !LANGUAGE.equals(languageId)) {
            throw new ExecutionException("EXPRESSION_LANGUAGE_UNSUPPORTED", null, "Expression language " + languageId
                    + " is not supported; use " + LANGUAGE);
        }
        try {
            Condition condition = cache.get(expression);
            if (condition == null) {
                if (cache.size() >= maxCacheEntries) {
                    cache.clear();
                }
                condition = Condition.parse(expression);
                cache.put(expression, condition);
            }
            return condition.test(variables);
        } catch (ConditionException e) {
            throw new ExecutionException("CONDITION_ERROR", null, e.getMessage());
        }
    }
}
