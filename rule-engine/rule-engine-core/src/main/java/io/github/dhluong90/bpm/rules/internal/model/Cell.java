package io.github.dhluong90.bpm.rules.internal.model;

import java.math.BigDecimal;

/** One {@code then} cell or output default: a JSON literal or an expression text. */
public sealed interface Cell {

    /**
     * A JSON boolean, number or null.
     *
     * @param value {@code Boolean}, {@code BigDecimal} or null
     */
    record Literal(Object value) implements Cell {
        public Literal {
            if (value != null && !(value instanceof Boolean) && !(value instanceof BigDecimal)) {
                throw new IllegalArgumentException("Literal cells are booleans, numbers or null");
            }
        }
    }

    /**
     * An expression text, such as {@code "0 VND"} or {@code "'GOLD'"}.
     *
     * @param source the text
     */
    record Expression(String source) implements Cell {
    }
}
