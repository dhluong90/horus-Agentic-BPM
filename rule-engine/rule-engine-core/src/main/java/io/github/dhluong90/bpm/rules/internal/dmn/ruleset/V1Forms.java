package io.github.dhluong90.bpm.rules.internal.dmn.ruleset;

import com.horus.bpm.rules.api.Money;
import io.github.dhluong90.bpm.rules.internal.dmn.ruleset.Feel.Op;
import io.github.dhluong90.bpm.rules.internal.dmn.ruleset.Feel.Test;
import io.github.dhluong90.bpm.rules.internal.dmn.ruleset.Feel.UnaryTests;
import io.github.dhluong90.bpm.rules.internal.dmn.ruleset.Feel.Value;
import com.horus.bpm.rules.internal.eval.CompiledRuleSet;
import com.horus.bpm.rules.internal.expr.Expr;
import com.horus.bpm.rules.internal.expr.ExpressionException;
import com.horus.bpm.rules.internal.expr.Parser;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * Translates between FEEL cells of the subset and {@code horus-expr-v1} cells (standards-compliance.md section
 * 4.4). Import writes one normal form per test, so a table imported, exported and imported again has the same
 * canonical document; export reads the v1 tree, so any spelling of a supported condition exports.
 *
 * <p>Normal forms for input {@code x}: {@code "GOLD"} is {@code == 'GOLD'}; {@code < 18} is {@code < 18};
 * {@code [18..65]} is {@code between 18 and 65}; {@code (18..65]} is {@code x > 18 and x <= 65}; {@code "VN", "SG"}
 * is {@code in ['VN', 'SG']}; {@code not("VN")} is {@code != 'VN'}; {@code not("VN", "SG")} is
 * {@code not in ['VN', 'SG']}; {@code null} is {@code is null}; {@code not(null)} is {@code is not null}; any other
 * list is the tests joined with {@code or}, negated with {@code not (...)}. The null semantics of both sides are the
 * FEEL ones (PD-058), so each pair gives the same result for every input, null included.
 */
final class V1Forms {

    private V1Forms() {
    }

    /**
     * Writes the v1 condition of an input entry.
     *
     * @param input the input name
     * @param tests the parsed unary tests, not {@code -}
     * @return the v1 condition text
     */
    static String condition(String input, UnaryTests tests) {
        List<Test> items = tests.tests();
        boolean allEquals = items.stream().allMatch(t -> t instanceof Test.Compare c && c.op() == Op.EQ
                && !(c.value() instanceof Value.Null));
        if (items.size() == 1) {
            Test only = items.getFirst();
            if (!tests.negated()) {
                return switch (only) {
                    case Test.Compare(Op op, Value value) when value instanceof Value.Null && op == Op.EQ -> "is null";
                    case Test.Compare(Op op, Value value) -> symbol(op) + " " + v1(value);
                    case Test.Interval(boolean lc, Value low, Value high, boolean hc) when lc && hc ->
                            "between " + v1(low) + " and " + v1(high);
                    case Test.Interval interval -> full(input, interval);
                    case Test.IsNull ignored -> "is null";
                };
            }
            if (only instanceof Test.IsNull || only instanceof Test.Compare(Op op, Value value)
                    && op == Op.EQ && value instanceof Value.Null) {
                return "is not null";
            }
            if (allEquals) {
                return "!= " + v1(((Test.Compare) only).value());
            }
            return "not (" + full(input, only) + ")";
        }
        if (allEquals) {
            List<String> values = items.stream().map(t -> v1(((Test.Compare) t).value())).toList();
            return (tests.negated() ? "not in [" : "in [") + String.join(", ", values) + "]";
        }
        List<String> parts = new ArrayList<>();
        for (Test item : items) {
            String part = full(input, item);
            parts.add(item instanceof Test.Interval i && !(i.lowClosed() && i.highClosed()) ? "(" + part + ")" : part);
        }
        String joined = String.join(" or ", parts);
        return tests.negated() ? "not (" + joined + ")" : joined;
    }

    /**
     * Reads a v1 condition back into unary tests, for export.
     *
     * @param input the input the cell belongs to
     * @param text the v1 condition text
     * @param slots maps input names to slot indexes
     * @return the unary tests
     * @throws Feel.UnsupportedException when the condition has no unary-test form
     */
    static UnaryTests fromCondition(String input, String text, ToIntFunction<String> slots) {
        Expr expr;
        try {
            expr = Parser.parse(CompiledRuleSet.conditionSource(input, text), slots);
        } catch (ExpressionException e) {
            throw new Feel.UnsupportedException("condition " + Quote.of(text) + " does not parse: " + Quote.text(e.getMessage()));
        }
        if (expr instanceof Expr.Unary(Expr.UnaryOp op, Expr operand) && op == Expr.UnaryOp.NOT) {
            return new UnaryTests(false, true, positives(input, operand, text));
        }
        if (expr instanceof Expr.Binary(Expr.BinaryOp op, Expr left, Expr right) && op == Expr.BinaryOp.NE
                && isInput(left, input)) {
            return new UnaryTests(false, true, List.of(equalsTest(right, text)));
        }
        if (expr instanceof Expr.InList(Expr target, List<Expr> elements, boolean negated) && negated) {
            return new UnaryTests(false, true, positives(input, new Expr.InList(target, elements, false), text));
        }
        if (expr instanceof Expr.IsNull(Expr target, boolean negated) && negated && isInput(target, input)) {
            return new UnaryTests(false, true, List.of(new Test.IsNull()));
        }
        return new UnaryTests(false, false, positives(input, expr, text));
    }

    /**
     * Reads a v1 output cell expression back into a FEEL literal, for export.
     *
     * @param text the v1 expression text
     * @return the literal
     * @throws Feel.UnsupportedException when the cell is not a literal
     */
    static Value literal(String text) {
        Expr expr;
        try {
            expr = Parser.parse(text, name -> -1);
        } catch (ExpressionException e) {
            throw new Feel.UnsupportedException("output " + Quote.of(text) + " is not a literal value: " + Quote.text(e.getMessage()));
        }
        if (expr instanceof Expr.Literal literal) {
            return value(literal.value(), text);
        }
        throw new Feel.UnsupportedException("output " + Quote.of(text) + " is not a literal value");
    }

    /**
     * Prints a value as v1 text.
     *
     * @param value the value
     * @return the v1 text
     */
    static String v1(Value value) {
        return switch (value) {
            case Value.Str(String s) -> "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
            case Value.Num(BigDecimal n) -> n.toPlainString();
            case Value.Bool(boolean b) -> Boolean.toString(b);
            case Value.Date(LocalDate d) -> "date('" + d + "')";
            case Value.DateTime(Instant i) -> "datetime('" + i + "')";
            case Value.Money(BigDecimal amount, String currency) -> amount.toPlainString() + " " + currency;
            case Value.Null() -> "null";
        };
    }

    private static List<Test> positives(String input, Expr expr, String text) {
        List<Expr> disjuncts = new ArrayList<>();
        flattenOr(expr, disjuncts);
        List<Test> tests = new ArrayList<>();
        for (Expr disjunct : disjuncts) {
            if (disjunct instanceof Expr.InList(Expr target, List<Expr> elements, boolean negated)
                    && !negated && isInput(target, input) && !elements.isEmpty()) {
                for (Expr element : elements) {
                    tests.add(equalsTest(element, text));
                }
            } else {
                tests.add(positive(input, disjunct, text));
            }
        }
        return tests;
    }

    private static void flattenOr(Expr expr, List<Expr> out) {
        if (expr instanceof Expr.Binary(Expr.BinaryOp op, Expr left, Expr right) && op == Expr.BinaryOp.OR) {
            flattenOr(left, out);
            flattenOr(right, out);
        } else {
            out.add(expr);
        }
    }

    private static Test positive(String input, Expr expr, String text) {
        switch (expr) {
            case Expr.Binary(Expr.BinaryOp op, Expr left, Expr right) when isInput(left, input) && compare(op) != null -> {
                Value value = value(right, text);
                if (value instanceof Value.Null) {
                    if (op == Expr.BinaryOp.EQ) {
                        return new Test.IsNull();
                    }
                    break;
                }
                return new Test.Compare(compare(op), value);
            }
            case Expr.Between(Expr target, Expr low, Expr high) when isInput(target, input) -> {
                return new Test.Interval(true, value(low, text), value(high, text), true);
            }
            case Expr.IsNull(Expr target, boolean negated) when isInput(target, input) && !negated -> {
                return new Test.IsNull();
            }
            case Expr.Binary(Expr.BinaryOp op, Expr left, Expr right) when op == Expr.BinaryOp.AND -> {
                Test.Compare low = boundOf(input, left, text);
                Test.Compare high = boundOf(input, right, text);
                if (low != null && high != null && isLower(low.op()) && !isLower(high.op())) {
                    return new Test.Interval(low.op() == Op.GE, low.value(), high.value(), high.op() == Op.LE);
                }
            }
            default -> {
                // refused below
            }
        }
        throw new Feel.UnsupportedException("condition " + Quote.of(text) + " has no DMN unary-test form");
    }

    private static Test.Compare boundOf(String input, Expr expr, String text) {
        if (expr instanceof Expr.Binary(Expr.BinaryOp op, Expr left, Expr right) && isInput(left, input)
                && compare(op) != null && compare(op) != Op.EQ) {
            Value value = value(right, text);
            return value instanceof Value.Null ? null : new Test.Compare(compare(op), value);
        }
        return null;
    }

    private static boolean isLower(Op op) {
        return op == Op.GT || op == Op.GE;
    }

    private static Test equalsTest(Expr expr, String text) {
        Value value = value(expr, text);
        return value instanceof Value.Null ? new Test.IsNull() : new Test.Compare(Op.EQ, value);
    }

    private static Op compare(Expr.BinaryOp op) {
        return switch (op) {
            case EQ -> Op.EQ;
            case LT -> Op.LT;
            case LE -> Op.LE;
            case GT -> Op.GT;
            case GE -> Op.GE;
            default -> null;
        };
    }

    private static boolean isInput(Expr expr, String input) {
        return expr instanceof Expr.Var var && var.name().equals(input);
    }

    private static Value value(Expr expr, String text) {
        if (expr instanceof Expr.Literal literal) {
            return value(literal.value(), text);
        }
        throw new Feel.UnsupportedException("condition " + Quote.of(text) + " compares with an expression, not a literal value");
    }

    private static Value value(Object literal, String text) {
        return switch (literal) {
            case null -> new Value.Null();
            case String s -> new Value.Str(s);
            case BigDecimal n -> new Value.Num(n);
            case Boolean b -> new Value.Bool(b);
            case LocalDate d -> new Value.Date(d);
            case Instant i -> new Value.DateTime(i);
            case Money m -> new Value.Money(m.toMajor(), m.currency());
            default -> throw new Feel.UnsupportedException(Quote.of(text) + " holds a value with no FEEL form");
        };
    }

    private static String symbol(Op op) {
        return switch (op) {
            case EQ -> "==";
            case LT -> "<";
            case LE -> "<=";
            case GT -> ">";
            case GE -> ">=";
        };
    }

    private static String full(String input, Test test) {
        return switch (test) {
            case Test.Compare(Op op, Value value) when value instanceof Value.Null -> input + " is null";
            case Test.Compare(Op op, Value value) -> input + " " + symbol(op) + " " + v1(value);
            case Test.Interval(boolean lc, Value low, Value high, boolean hc) when lc && hc ->
                    input + " between " + v1(low) + " and " + v1(high);
            case Test.Interval(boolean lc, Value low, Value high, boolean hc) ->
                    input + (lc ? " >= " : " > ") + v1(low) + " and " + input + (hc ? " <= " : " < ") + v1(high);
            case Test.IsNull ignored -> input + " is null";
        };
    }
}
