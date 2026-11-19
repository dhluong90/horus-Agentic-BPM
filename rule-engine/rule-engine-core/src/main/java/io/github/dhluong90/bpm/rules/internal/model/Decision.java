package io.github.dhluong90.bpm.rules.internal.model;

/**
 * A DMN decision. A schema version 1 rule set has exactly one, named after the rule set (DN-08).
 *
 * @param id the decision id, equal to the rule set name in schema version 1
 * @param name the decision name
 * @param table the decision logic
 */
public record Decision(String id, String name, DecisionTable table) {
}
