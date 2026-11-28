package io.github.dhluong90.bpm.expr.feel;

import io.github.dhluong90.bpm.expr.api.ExpressionInvalidException;

/**
 * The FEEL parser (#197). Pure: text and scope in, tree out, no clock and no I/O. Limits: 64 KB of source and nesting
 * depth 64, refused with {@link ExpressionInvalidException} carrying the offset, without backtracking beyond a
 * constant. Reference test: golden tree dumps per grammar rule, {@code parse(print(t)) == t}, and the FEEL texts of the DMN
 * TCK models (parse-only count).
 */
public interface FeelParser {

    /** @return the parser of this release; the stub until #197 */
    static FeelParser create() {
        return new StubFeelParser();
    }

    /**
     * @param source an expression
     * @param scope the names in scope
     * @return its tree
     * @throws ExpressionInvalidException with the offset
     */
    FeelNode parseExpression(String source, FeelNameScope scope);

    /**
     * @param source unary tests, as in a decision table input entry
     * @param scope the names in scope
     * @return {@link FeelNode.AnyTest}, {@link FeelNode.NotTest}, or a {@link FeelNode.ListLiteral} whose elements
     *     are the tests
     * @throws ExpressionInvalidException with the offset
     */
    FeelNode parseUnaryTests(String source, FeelNameScope scope);
}
