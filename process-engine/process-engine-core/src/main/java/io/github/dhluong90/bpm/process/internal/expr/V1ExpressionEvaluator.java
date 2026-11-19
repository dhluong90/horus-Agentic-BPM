package io.github.dhluong90.bpm.process.internal.expr;

import io.github.dhluong90.bpm.process.internal.exec.ExecutionException;
import io.github.dhluong90.bpm.process.internal.exec.ExpressionEvaluator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code urn:horus-bpm:expr:v1} conditions through {@link Condition}, with parsed conditions cached by text. Other
 * languages are refused. Replaced by a lookup of the rule side's {@code ExpressionLanguage} SPI when it lands.
 */
public final class V1ExpressionEvaluator implements ExpressionEvaluator {

    /** The language id this evaluator accepts. */
    public static final String LANGUAGE = "urn:horus-bpm:expr:v1";

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
