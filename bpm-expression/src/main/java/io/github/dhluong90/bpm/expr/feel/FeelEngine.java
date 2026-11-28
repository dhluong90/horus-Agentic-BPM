package io.github.dhluong90.bpm.expr.feel;

import io.github.dhluong90.bpm.expr.spi.CompiledExpression;
import io.github.dhluong90.bpm.expr.spi.EvaluationContext;
import java.util.Map;

/**
 * The FEEL type checker and evaluator (#198). It compiles a {@link FeelNode} once and evaluates it many times. Pure:
 * the clock, zone, budget and error mode come in through {@link FeelOptions} and {@link EvaluationContext}.
 * Oracle: the FEEL folders of the DMN TCK, and the differential test against the current language (TR-STD-02).
 */
public interface FeelEngine {

    /** @return the engine of this release; the stub until #198 */
    static FeelEngine create() {
        return (tree, names, builtins) -> {
            throw new FeelNotImplementedException("FeelEngine", "#198");
        };
    }

    /**
     * Type-checks and compiles. Refuses what is statically wrong (unknown name, wrong arity of a known function,
     * mixed literal currencies) and picks the profile: the compiled one when the tree is in the S-FEEL subset.
     *
     * @param tree the parsed expression
     * @param names the declared names and types, and BKM functions
     * @param builtins the built-ins visible to the expression
     * @return the compiled expression
     * @throws io.github.dhluong90.bpm.expr.api.ExpressionInvalidException with the offset
     */
    FeelCompiled compile(FeelNode tree, Map<String, FeelType> names, FeelBuiltins builtins);

    /** A compiled FEEL expression: the shared SPI plus the warnings of {@code spec} mode. Thread-safe. */
    interface FeelCompiled extends CompiledExpression {

        /** @return the static result type, {@link FeelType.Any} when it depends on the values */
        default FeelType feelResultType() {
            return new FeelType.Any();
        }

        /**
         * @param context values and clock
         * @param options error mode, zone and budgets of the model
         * @return the value with its warnings
         * @throws io.github.dhluong90.bpm.expr.api.ExpressionEvaluationException on a guardrail, or on any error in
         *     {@code strict} mode
         */
        default FeelOutcome evaluate(EvaluationContext context, FeelOptions options) {
            throw new FeelNotImplementedException("FeelCompiled.evaluate", "#198");
        }
    }
}
