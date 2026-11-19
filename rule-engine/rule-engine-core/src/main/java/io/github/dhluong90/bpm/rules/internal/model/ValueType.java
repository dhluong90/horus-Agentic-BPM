package io.github.dhluong90.bpm.rules.internal.model;

import java.util.Locale;

/**
 * A declared input or output type: a scalar kind, or a list of one.
 *
 * @param kind the scalar kind
 * @param list whether the value is a list of {@code kind}
 */
public record ValueType(Kind kind, boolean list) {

    /** Scalar kinds of schema version 1. */
    public enum Kind { STRING, INTEGER, DECIMAL, BOOLEAN, DATE, DATETIME, MONEY }

    /**
     * Parses {@code string}, {@code money} or {@code list<string>}.
     *
     * @param text the type text
     * @return the type
     * @throws IllegalArgumentException for an unknown type
     */
    public static ValueType parse(String text) {
        boolean list = text.startsWith("list<") && text.endsWith(">");
        String scalar = list ? text.substring(5, text.length() - 1) : text;
        return new ValueType(Kind.valueOf(scalar.toUpperCase(Locale.ROOT)), list);
    }

    @Override
    public String toString() {
        String scalar = kind.name().toLowerCase(Locale.ROOT);
        return list ? "list<" + scalar + ">" : scalar;
    }
}
