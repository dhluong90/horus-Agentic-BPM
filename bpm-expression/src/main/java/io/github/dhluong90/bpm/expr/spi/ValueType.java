package io.github.dhluong90.bpm.expr.spi;

/** The static type of a name or an expression result. {@link #ANY} when it is known only at evaluation. */
public enum ValueType {
    STRING, BOOLEAN, NUMBER, DATE, DATETIME, MONEY, LIST, ANY
}
