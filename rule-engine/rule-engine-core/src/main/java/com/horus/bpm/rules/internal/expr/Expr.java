package com.horus.bpm.rules.internal.expr;

import java.util.List;

/**
 * The typed tree of a compiled expression. Immutable; no node executes host code (TR-SEC-01).
 */
public sealed interface Expr {

    /**
     * A constant: {@code String}, {@code BigDecimal}, {@code Boolean}, {@code Money} or null.
     *
     * @param value the constant
     */
    record Literal(Object value) implements Expr {
    }

    /**
     * An input, bound to its slot index at compile time.
     *
     * @param slot the slot index
     * @param name the input name
     */
    record Var(int slot, String name) implements Expr {
    }

    /**
     * {@code -x} or {@code not(x)}.
     *
     * @param op the operator
     * @param operand the operand
     */
    record Unary(UnaryOp op, Expr operand) implements Expr {
    }

    /**
     * A binary operation.
     *
     * @param op the operator
     * @param left the left operand
     * @param right the right operand
     */
    record Binary(BinaryOp op, Expr left, Expr right) implements Expr {
    }

    /**
     * {@code x in [a, b]} or {@code x not in [a, b]}.
     *
     * @param target the tested value
     * @param elements the list elements
     * @param negated true for {@code not in}
     */
    record InList(Expr target, List<Expr> elements, boolean negated) implements Expr {
        public InList {
            elements = List.copyOf(elements);
        }
    }

    /**
     * {@code x between low and high}, both bounds inclusive.
     *
     * @param target the tested value
     * @param low the lower bound
     * @param high the upper bound
     */
    record Between(Expr target, Expr low, Expr high) implements Expr {
    }

    /**
     * {@code x is null} or {@code x is not null}.
     *
     * @param target the tested value
     * @param negated true for {@code is not null}
     */
    record IsNull(Expr target, boolean negated) implements Expr {
    }

    /**
     * {@code today()} or {@code now()}: the evaluation clock, never the system time (TR-DET-01). The compiler binds
     * both to slots after the inputs, filled for each evaluation from the decision's clock in the rule set's
     * {@code timeZone}; a parse without a clock (printing, translation) leaves the slot at -1.
     *
     * @param function which reading of the clock
     * @param slot the frame slot holding the value, or -1 when the tree is not bound to a clock
     */
    record Clock(ClockFunction function, int slot) implements Expr {
    }

    /** The clock functions: {@code today()} is a {@code date}, {@code now()} a {@code datetime}. */
    enum ClockFunction {
        TODAY("today"),
        NOW("now");

        private final String text;

        ClockFunction(String text) {
            this.text = text;
        }

        /**
         * Returns the function name as written, without parentheses.
         *
         * @return {@code today} or {@code now}
         */
        public String text() {
            return text;
        }

        /**
         * Returns the name a slot resolver answers with the frame slot of this function, for example
         * {@code today()}. No input can have it: input names hold no parentheses.
         *
         * @return the slot name
         */
        public String slotName() {
            return text + "()";
        }
    }

    /** Unary operators. */
    enum UnaryOp { NEGATE, NOT }

    /** Binary operators. */
    enum BinaryOp { OR, AND, EQ, NE, LT, LE, GT, GE, ADD, SUB, MUL, DIV }
}
