package io.github.dhluong90.bpm.rules.internal.model;

/**
 * A DMN input clause: one declared input of the decision table.
 *
 * @param name the input name
 * @param type the declared type
 * @param masked whether the value is kept out of the decision log in plain text
 */
public record InputClause(String name, ValueType type, boolean masked) {
}
