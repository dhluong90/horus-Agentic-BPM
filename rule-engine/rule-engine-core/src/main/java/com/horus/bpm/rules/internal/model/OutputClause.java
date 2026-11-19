package com.horus.bpm.rules.internal.model;

import java.util.Objects;

/**
 * A DMN output clause.
 *
 * @param name the output name
 * @param type the declared type
 * @param defaultValue value used when a matching rule omits this output, or null
 * @param aggregate how a {@code collect} table combines the matched rows' values of this output
 */
public record OutputClause(String name, ValueType type, Cell defaultValue, Aggregate aggregate) {

    public OutputClause {
        Objects.requireNonNull(aggregate, "aggregate must not be null");
    }

    /** Aggregation of a {@code collect} output; {@link #NONE} keeps a list in rule order. */
    public enum Aggregate { NONE, SUM, MIN, MAX, COUNT }
}
