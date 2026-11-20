package io.github.dhluong90.bpm.process.internal.model.horus;

/**
 * {@code horus:ruleSet} of a business rule task: the rule set (decision) key.
 *
 * @param ruleSet ruleSet
 */
public record DecisionRef(
        String ruleSet) implements HorusExtension {
}
