package io.github.dhluong90.bpm.process.internal.model.bpmn;

/**
 * BPMN {@code tExpression} or {@code tFormalExpression}: the text and its language, kept as written. A null language means the definitions-level {@code expressionLanguage}.
 *
 * @param text text
 * @param language language
 * @param evaluatesToTypeRef evaluatesToTypeRef
 */
public record Expression(
        String text,
        String language,
        String evaluatesToTypeRef) {
}
