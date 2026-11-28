package io.github.dhluong90.bpm.rules.internal.dmn;

import io.github.dhluong90.bpm.expr.feel.FeelNameScope;
import io.github.dhluong90.bpm.expr.feel.FeelType;
import io.github.dhluong90.bpm.rules.internal.dmn.model.DmnModel;
import java.util.Map;

/**
 * Compiles one boxed expression, including decision tables with all eleven hit policies, to a function of a scope
 * (#201 for table, literal, list, relation, conditional, iterator, filter; #202 adds context, invocation and function
 * definition). It reaches FEEL only through the {@code feel} contract: the parser for text, the engine for
 * evaluation. Reference test: the TCK level 2 folders and a unit test per hit policy including priority ties.
 */
public interface BoxedExpressionCompiler {

    /** @return the compiler of this release; the stub until #201 */
    static BoxedExpressionCompiler create() {
        return (logic, names, types) -> {
            throw new UnsupportedOperationException("Boxed expression compiler is not yet implemented (#201)");
        };
    }

    /**
     * @param logic the boxed expression
     * @param names the names in scope, for multi-word names
     * @param types the types of the names
     * @return the compiled expression
     */
    CompiledBoxed compile(DmnModel.BoxedExpression logic, FeelNameScope names, Map<String, FeelType> types);

    /** A compiled boxed expression. Immutable and thread-safe. */
    interface CompiledBoxed {
        /**
         * @param scope names to FEEL values, including the results of required decisions
         * @param run the evaluation state: clock, zone, error mode and the budget shared by the whole evaluation
         * @return the FEEL value, possibly null
         */
        Object evaluate(Map<String, Object> scope, io.github.dhluong90.bpm.expr.feel.FeelCall run);
    }
}
