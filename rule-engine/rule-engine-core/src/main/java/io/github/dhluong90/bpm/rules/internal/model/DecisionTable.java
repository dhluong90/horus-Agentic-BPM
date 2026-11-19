package io.github.dhluong90.bpm.rules.internal.model;

import java.math.RoundingMode;
import java.util.List;

/**
 * A DMN decision table.
 *
 * @param hitPolicy the hit policy
 * @param moneyRounding rounding of money multiplication and division
 * @param inputs the input clauses in document order
 * @param outputs the output clauses in document order
 * @param rules the rules in document order
 */
public record DecisionTable(
        HitPolicy hitPolicy, RoundingMode moneyRounding, List<InputClause> inputs, List<OutputClause> outputs,
        List<Rule> rules) {

    public DecisionTable {
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        rules = List.copyOf(rules);
    }
}
