package io.github.dhluong90.bpm.rules.internal.model;

/**
 * A DMN output clause.
 *
 * @param name the output name
 * @param type the declared type
 * @param defaultValue value used when a matching rule omits this output, or null
 */
public record OutputClause(String name, ValueType type, Cell defaultValue) {
}
