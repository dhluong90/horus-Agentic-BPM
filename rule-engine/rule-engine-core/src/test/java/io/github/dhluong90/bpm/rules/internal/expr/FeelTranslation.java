package io.github.dhluong90.bpm.rules.internal.expr;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.stream.Collectors;

/**
 * Writes the FEEL text of a {@code horus-expr-v1} tree, by the translation table of
 * {@code docs/architecture/standards-compliance.md} section 4.4. Test scope: the oracle side of the RLE-021
 * differential test. Every operation is parenthesised, so FEEL precedence never decides the meaning. Money has no
 * FEEL translation here: it is a HORUS extension, excluded from the differential test.
 */
final class FeelTranslation {

    private FeelTranslation() {
    }

    /**
     * Translates a tree.
     *
     * @param expr the v1 tree
     * @return the FEEL text
     * @throws IllegalArgumentException for a money value or a clock reading
     */
    static String feel(Expr expr) {
        return switch (expr) {
            case Expr.Literal literal -> literal(literal.value());
            case Expr.Var var -> var.name();
            case Expr.Unary unary -> switch (unary.op()) {
                case NOT -> "not(" + feel(unary.operand()) + ")";
                case NEGATE -> "(-" + feel(unary.operand()) + ")";
            };
            case Expr.Binary binary -> "((" + feel(binary.left()) + ") " + operator(binary.op()) + " ("
                    + feel(binary.right()) + "))";
            case Expr.InList inList -> {
                String in = "((" + feel(inList.target()) + ") in ["
                        + inList.elements().stream().map(FeelTranslation::feel).collect(Collectors.joining(", ")) + "])";
                yield inList.negated() ? "not" + in : in;
            }
            // operands in parentheses: a FEEL name may contain spaces, so "d1 between date" would read as one name
            case Expr.Between between -> "((" + feel(between.target()) + ") between (" + feel(between.low()) + ") and ("
                    + feel(between.high()) + "))";
            case Expr.IsNull isNull -> "((" + feel(isNull.target()) + ")" + (isNull.negated() ? " != null)" : " = null)");
            case Expr.Call call -> switch (call.function()) {
                // DMN 1.1 decimal(n, scale) rounds half-even, the same as DMN 1.5 round half even(n, scale)
                case ROUND -> "decimal(" + feel(call.args().get(0)) + ", " + feel(call.args().get(1)) + ")";
                case DAYS_BETWEEN -> "((" + feel(call.args().get(1)) + " - " + feel(call.args().get(0)) + ").days)";
            };
            // FEEL matches(input, pattern) is an unanchored XPath search, as v1's
            case Expr.Matches matches -> "matches(" + feel(matches.target()) + ", " + string(matches.pattern()) + ")";
            case Expr.Clock clock -> throw new IllegalArgumentException(
                    "no FEEL translation for " + clock.function().text() + "(): the differential test has no clock slot");
        };
    }

    private static String operator(Expr.BinaryOp op) {
        return switch (op) {
            case OR -> "or";
            case AND -> "and";
            case EQ -> "=";
            case NE -> "!=";
            case LT -> "<";
            case LE -> "<=";
            case GT -> ">";
            case GE -> ">=";
            case ADD -> "+";
            case SUB -> "-";
            case MUL -> "*";
            case DIV -> "/";
        };
    }

    private static String literal(Object value) {
        return switch (value) {
            case null -> "null";
            case String text -> string(text);
            case BigDecimal number -> number.signum() < 0 ? "(" + number.toPlainString() + ")" : number.toPlainString();
            case Boolean bool -> bool.toString();
            case LocalDate date -> "date(\"" + date + "\")";
            case Instant instant -> "date and time(\"" + instant + "\")";
            default -> throw new IllegalArgumentException("no FEEL translation for " + value.getClass().getSimpleName());
        };
    }

    /** A FEEL string literal: {@code "} and {@code \} escaped. */
    static String string(String text) {
        return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
