package io.github.dhluong90.bpm.expr.internal;

import io.github.dhluong90.bpm.expr.api.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.stream.Collectors;

/**
 * Writes a tree as {@code urn:horus-bpm:expr:v1} text in one normal form: single-quoted strings, minimal
 * parentheses, one space around operators, lists with {@code ", "}. A FEEL condition and its v1 spelling print to the
 * same text, so a definition hashes once per meaning (ADR-0026). Money prints at the currency's scale, so
 * {@code money(12.5, "USD")} and {@code 12.50 USD} are one text.
 */
public final class NormalForm {

    private NormalForm() {
    }

    /**
     * @param expr a parsed tree
     * @return its v1 text
     */
    public static String print(Expr expr) {
        return print(expr, 0);
    }

    private static int precedence(Expr expr) {
        return switch (expr) {
            case Expr.Binary b -> switch (b.op()) {
                case OR -> 1;
                case AND -> 2;
                case EQ, NE, LT, LE, GT, GE -> 4;
                case ADD, SUB -> 5;
                case MUL, DIV -> 6;
            };
            case Expr.Unary u -> u.op() == Expr.UnaryOp.NOT ? 3 : 7;
            case Expr.InList ignored -> 4;
            case Expr.Between ignored -> 4;
            case Expr.IsNull ignored -> 4;
            default -> 8;
        };
    }

    private static String print(Expr expr, int minimum) {
        String text = switch (expr) {
            case Expr.Literal literal -> literal(literal.value());
            case Expr.Var var -> var.name();
            case Expr.Clock clock -> clock.function().text() + "()";
            case Expr.Unary unary -> unary.op() == Expr.UnaryOp.NOT
                    ? "not " + print(unary.operand(), 3)
                    : "-" + print(unary.operand(), 7);
            case Expr.Binary binary -> {
                int own = precedence(expr);
                boolean comparison = own == 4;
                yield print(binary.left(), comparison ? 5 : own) + " " + operator(binary.op()) + " "
                        + print(binary.right(), comparison ? 5 : own + 1);
            }
            case Expr.InList in -> print(in.target(), 5) + (in.negated() ? " not in [" : " in [")
                    + in.elements().stream().map(e -> print(e, 1)).collect(Collectors.joining(", ")) + "]";
            case Expr.Between between -> print(between.target(), 5) + " between " + print(between.low(), 5) + " and "
                    + print(between.high(), 5);
            case Expr.IsNull isNull -> print(isNull.target(), 5) + (isNull.negated() ? " is not null" : " is null");
            case Expr.Call call -> call.function().text() + "("
                    + call.args().stream().map(e -> print(e, 1)).collect(Collectors.joining(", ")) + ")";
            case Expr.HostCall call -> call.function().name() + "("
                    + call.args().stream().map(e -> print(e, 1)).collect(Collectors.joining(", ")) + ")";
            case Expr.Matches matches -> "matches(" + print(matches.target(), 1) + ", " + string(matches.pattern()) + ")";
        };
        return precedence(expr) < minimum ? "(" + text + ")" : text;
    }

    private static String operator(Expr.BinaryOp op) {
        return switch (op) {
            case OR -> "or";
            case AND -> "and";
            case EQ -> "==";
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
            case BigDecimal number -> number.toPlainString();
            case Boolean bool -> bool.toString();
            case Money money -> money.toString();
            case LocalDate date -> "date('" + date + "')";
            case Instant instant -> "datetime('" + instant + "')";
            default -> throw new IllegalStateException("No normal form for a " + value.getClass().getSimpleName());
        };
    }

    private static String string(String text) {
        StringBuilder out = new StringBuilder("'");
        text.codePoints().forEach(cp -> {
            int type = Character.getType(cp);
            if (cp == '\'' || cp == '\\') {
                out.append('\\').append((char) cp);
            } else if (type == Character.CONTROL || type == Character.FORMAT) {
                out.append(String.format("\\u%04x", cp));
            } else {
                out.appendCodePoint(cp);
            }
        });
        return out.append('\'').toString();
    }
}
