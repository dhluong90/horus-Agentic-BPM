package io.github.dhluong90.bpm.rules.internal.expr;

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
     * A call of a whitelisted pure function (rule-engine DESIGN 3.2 item 3). A {@code null} argument gives
     * {@code null}, as in FEEL.
     *
     * @param function the function
     * @param args the arguments, as many as the function takes
     */
    record Call(Function function, List<Expr> args) implements Expr {
        public Call {
            args = List.copyOf(args);
        }
    }

    /** The pure functions of {@code horus-expr-v1}, with their FEEL translation. */
    enum Function {
        /** {@code round(x, scale)}, half-even; FEEL {@code decimal(x, scale)}, the same as {@code round half even}. */
        ROUND("round"),
        /** {@code daysBetween(d1, d2)}, calendar days from d1 to d2; FEEL {@code (d2 - d1).days}. */
        DAYS_BETWEEN("daysBetween");

        private final String text;

        Function(String text) {
            this.text = text;
        }

        /** The name as written in an expression. */
        public String text() {
            return text;
        }

        /** The number of arguments. */
        public int arity() {
            return 2;
        }
    }

    /** Unary operators. */
    enum UnaryOp { NEGATE, NOT }

    /** Binary operators. */
    enum BinaryOp { OR, AND, EQ, NE, LT, LE, GT, GE, ADD, SUB, MUL, DIV }
}
