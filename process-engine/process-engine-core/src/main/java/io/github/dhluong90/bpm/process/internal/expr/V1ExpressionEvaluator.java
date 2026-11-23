package io.github.dhluong90.bpm.process.internal.expr;

import io.github.dhluong90.bpm.expr.languages.ExpressionLanguages;
import io.github.dhluong90.bpm.process.internal.exec.ExecutionException;
import io.github.dhluong90.bpm.process.internal.exec.ExpressionEvaluator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code urn:horus-bpm:expr:v1} and FEEL conditions through {@link Condition}, which compiles them with the
 * {@code ExpressionLanguage} SPI of {@code bpm-expression} (ADR-0027), with compiled conditions cached by text. Other
 * languages are refused with {@code EXPRESSION_LANGUAGE_UNSUPPORTED}.
 */
public final class V1ExpressionEvaluator implements ExpressionEvaluator {

    /** The language id of the HORUS expression language; FEEL is accepted under its DMN URIs. */
    public static final String LANGUAGE = ExpressionLanguages.V1_ID;

    private final Map<String, Condition> cache = new ConcurrentHashMap<>();
    private final int maxCacheEntries;

    /** @param maxCacheEntries parsed conditions kept; a full cache is cleared */
    public V1ExpressionEvaluator(int maxCacheEntries) {
        this.maxCacheEntries = maxCacheEntries;
    }

    @Override
    public Object evaluate(String expression, String languageId, Map<String, Object> variables) {
        if (languageId != null && !ExpressionLanguages.supports(languageId)) {
            throw new ExecutionException("EXPRESSION_LANGUAGE_UNSUPPORTED", null, "Expression language " + languageId
                    + " is not supported; use " + LANGUAGE + " or FEEL (" + ExpressionLanguages.FEEL_ID + ")");
        }
        // One cache entry per language and text: the same text means different things in the two languages.
        String key = (ExpressionLanguages.isFeel(languageId) ? "feel:" : "v1:") + expression;
        try {
            Condition condition = cache.get(key);
            if (condition == null) {
                if (cache.size() >= maxCacheEntries) {
                    cache.clear();
                }
                condition = Condition.parse(expression, languageId);
                cache.put(key, condition);
            }
            return condition.test(variables);
        } catch (ConditionException e) {
            throw new ExecutionException("CONDITION_ERROR", null, e.getMessage());
        }
    }
}
