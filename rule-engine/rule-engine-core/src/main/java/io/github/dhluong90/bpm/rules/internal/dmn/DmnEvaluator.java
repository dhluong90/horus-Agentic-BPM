package io.github.dhluong90.bpm.rules.internal.dmn;

import io.github.dhluong90.bpm.expr.feel.FeelOptions;
import io.github.dhluong90.bpm.expr.feel.FeelOutcome;
import io.github.dhluong90.bpm.rules.internal.dmn.model.DmnModel;
import java.time.Instant;
import java.util.Map;

/**
 * The DMN evaluation entry point (#202): compiles the decision requirements graph once (cycle check, requirement
 * order, knowledge models as functions, decision services) and evaluates a decision or service by name. One budget
 * covers the whole evaluation. A decision service is the unit a {@code businessRuleTask} calls later. Reference test: the
 * TCK level 3 folders; a cycle is refused at compile time.
 */
public interface DmnEvaluator {

    /** @return the evaluator of this release; the stub until #202 */
    static DmnEvaluator create() {
        return (model, target, inputs, clock, options) -> {
            throw new UnsupportedOperationException("DMN evaluator is not yet implemented (#202)");
        };
    }

    /**
     * @param model the model, read or built
     * @param target the name of a decision or decision service
     * @param inputs input data by name, in host value form
     * @param clock the evaluation instant, supplied by the caller
     * @param options error mode, zone and budgets of the model
     * @return the FEEL value and the warnings
     */
    FeelOutcome evaluate(DmnModel.Definitions model, String target, Map<String, Object> inputs, Instant clock,
            FeelOptions options);
}
