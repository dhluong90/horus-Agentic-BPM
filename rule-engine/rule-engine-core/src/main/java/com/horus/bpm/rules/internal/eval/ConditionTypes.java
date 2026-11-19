package com.horus.bpm.rules.internal.eval;

import com.horus.bpm.rules.api.Money;
import com.horus.bpm.rules.api.ValidationFailedException;
import com.horus.bpm.rules.internal.expr.Expr;
import com.horus.bpm.rules.internal.model.InputClause;
import com.horus.bpm.rules.internal.model.ValueType;
import java.math.BigDecimal;
import java.util.List;

/**
 * The type floor checked at import, so a mistyped condition fails closed instead of never matching and
 * letting a later row decide: a condition must produce a boolean, and a comparison of an input with a
 * literal must use the input's declared type. Full type checking is RLE-004.
 */
final class ConditionTypes {

    private enum Kind { STRING, NUMBER, BOOLEAN, MONEY, DATE, DATETIME, LIST, UNKNOWN, NULL }

    private ConditionTypes() {
    }

    static void requireBoolean(Expr expr, List<InputClause> inputs, String where) {
        switch (expr) {
            case Expr.Literal literal -> {
                if (!(literal.value() instanceof Boolean)) {
                    throw notBoolean(where);
                }
            }
            case Expr.Var var -> {
                if (kindOf(var, inputs) != Kind.BOOLEAN) {
                    throw notBoolean(where);
                }
            }
            case Expr.Unary unary -> {
                if (unary.op() != Expr.UnaryOp.NOT) {
                    throw notBoolean(where);
                }
                requireBoolean(unary.operand(), inputs, where);
            }
            case Expr.Binary binary -> {
                switch (binary.op()) {
                    case AND, OR -> {
                        requireBoolean(binary.left(), inputs, where);
                        requireBoolean(binary.right(), inputs, where);
                    }
                    case EQ, NE, LT, LE, GT, GE -> requireComparable(binary.left(), binary.right(), inputs, where);
                    case ADD, SUB, MUL, DIV -> throw notBoolean(where);
                }
            }
            case Expr.InList inList -> {
                for (Expr element : inList.elements()) {
                    requireComparable(inList.target(), element, inputs, where);
                }
            }
            case Expr.Between between -> {
                requireComparable(between.target(), between.low(), inputs, where);
                requireComparable(between.target(), between.high(), inputs, where);
            }
            case Expr.IsNull ignored -> {
                // always true or false
            }
        }
    }

    private static void requireComparable(Expr left, Expr right, List<InputClause> inputs, String where) {
        Kind a = kindOf(left, inputs);
        Kind b = kindOf(right, inputs);
        if (a == Kind.UNKNOWN || b == Kind.UNKNOWN || a == Kind.NULL || b == Kind.NULL) {
            return;
        }
        if (a != b) {
            throw new ValidationFailedException(where + ": compares a " + name(a) + " with a " + name(b));
        }
    }

    private static Kind kindOf(Expr expr, List<InputClause> inputs) {
        return switch (expr) {
            case Expr.Literal literal -> switch (literal.value()) {
                case null -> Kind.NULL;
                case String ignored -> Kind.STRING;
                case BigDecimal ignored -> Kind.NUMBER;
                case Boolean ignored -> Kind.BOOLEAN;
                case Money ignored -> Kind.MONEY;
                default -> Kind.UNKNOWN;
            };
            case Expr.Var var -> {
                ValueType type = inputs.get(var.slot()).type();
                if (type.list()) {
                    yield Kind.LIST;
                }
                yield switch (type.kind()) {
                    case STRING -> Kind.STRING;
                    case INTEGER, DECIMAL -> Kind.NUMBER;
                    case BOOLEAN -> Kind.BOOLEAN;
                    case MONEY -> Kind.MONEY;
                    case DATE -> Kind.DATE;
                    case DATETIME -> Kind.DATETIME;
                };
            }
            default -> Kind.UNKNOWN;
        };
    }

    private static String name(Kind kind) {
        return kind.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static ValidationFailedException notBoolean(String where) {
        return new ValidationFailedException(where + ": the condition does not produce true or false");
    }
}
