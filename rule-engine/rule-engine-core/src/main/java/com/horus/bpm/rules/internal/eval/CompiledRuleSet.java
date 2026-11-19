package com.horus.bpm.rules.internal.eval;

import com.horus.bpm.rules.api.DecisionInput;
import com.horus.bpm.rules.api.DecisionResult;
import com.horus.bpm.rules.api.InputInvalidException;
import com.horus.bpm.rules.api.Money;
import com.horus.bpm.rules.api.ValidationFailedException;
import com.horus.bpm.rules.internal.expr.EvaluationException;
import com.horus.bpm.rules.internal.expr.Evaluator;
import com.horus.bpm.rules.internal.expr.Expr;
import com.horus.bpm.rules.internal.expr.ExpressionException;
import com.horus.bpm.rules.internal.expr.Parser;
import com.horus.bpm.rules.internal.model.Cell;
import com.horus.bpm.rules.internal.model.DecisionTable;
import com.horus.bpm.rules.internal.model.Definitions;
import com.horus.bpm.rules.internal.model.HitPolicy;
import com.horus.bpm.rules.internal.model.InputClause;
import com.horus.bpm.rules.internal.model.OutputClause;
import com.horus.bpm.rules.internal.model.Rule;
import com.horus.bpm.rules.internal.model.ValueType;
import com.horus.bpm.rules.internal.model.When;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A decision table compiled once per version: inputs bound to slot indexes, every condition and
 * output cell parsed into a tree. Immutable and thread-safe; evaluation allocates only the slot array
 * and the result.
 */
public final class CompiledRuleSet {

    private static final Set<String> SHORTHAND_PREFIXES = Set.of("==", "!=", "<", ">", "in ", "in[", "not in",
            "is ", "between ");

    private final String name;
    private final HitPolicy hitPolicy;
    private final List<InputClause> inputs;
    private final List<OutputClause> outputs;
    private final List<CompiledRule> rules;
    private final Evaluator evaluator;

    private CompiledRuleSet(Definitions model, List<CompiledRule> rules) {
        DecisionTable table = model.decision().table();
        this.name = model.name();
        this.hitPolicy = table.hitPolicy();
        this.inputs = table.inputs();
        this.outputs = table.outputs();
        this.rules = List.copyOf(rules);
        this.evaluator = new Evaluator(table.moneyRounding());
    }

    /**
     * Compiles a model.
     *
     * @param model the decision model
     * @return the compiled rule set
     * @throws ValidationFailedException when a cell does not parse, with the rule id
     */
    public static CompiledRuleSet compile(Definitions model) {
        DecisionTable table = model.decision().table();
        for (OutputClause output : table.outputs()) {
            checkOutput(table.hitPolicy(), output);
        }
        Map<String, Integer> slots = new HashMap<>();
        for (int i = 0; i < table.inputs().size(); i++) {
            slots.put(table.inputs().get(i).name(), i);
        }
        Map<String, Expr> defaults = new LinkedHashMap<>();
        for (OutputClause output : table.outputs()) {
            defaults.put(output.name(), output.defaultValue() == null
                    ? new Expr.Literal(null) : cell(output.defaultValue(), slots, "output " + output.name()));
        }
        List<CompiledRule> rules = new ArrayList<>();
        for (Rule rule : table.rules()) {
            Expr condition = when(rule.when(), slots, table.inputs(), rule.id());
            List<Expr> thens = new ArrayList<>();
            for (OutputClause output : table.outputs()) {
                Cell cell = rule.then().get(output.name());
                thens.add(cell == null ? defaults.get(output.name()) : cell(cell, slots, "rule " + rule.id()));
            }
            rules.add(new CompiledRule(rule.id(), condition, thens));
        }
        return new CompiledRuleSet(model, rules);
    }

    /**
     * Returns the rule set name.
     *
     * @return the name
     */
    public String name() {
        return name;
    }

    /**
     * Returns the hit policy.
     *
     * @return the hit policy
     */
    public HitPolicy hitPolicy() {
        return hitPolicy;
    }

    /**
     * Returns the input clauses, in slot order.
     *
     * @return the inputs
     */
    public List<InputClause> inputs() {
        return inputs;
    }

    /**
     * Evaluates the table. Input type errors throw; evaluation errors are returned as {@code Failed}.
     *
     * @param input the inputs
     * @return the outcome
     * @throws InputInvalidException when an input does not match its declared type
     */
    public DecisionResult.Outcome evaluate(DecisionInput input) {
        Object[] slots = bind(input);
        try {
            return switch (hitPolicy) {
                case FIRST -> first(slots);
                case UNIQUE -> unique(slots);
                case COLLECT -> collect(slots);
            };
        } catch (EvaluationException e) {
            return new DecisionResult.Failed("EVALUATION_ERROR", e.getMessage());
        } catch (ArithmeticException e) {
            // Exponent overflow or underflow in DECIMAL128 arithmetic: a deterministic failure, never a 500.
            return new DecisionResult.Failed("EVALUATION_ERROR", "Arithmetic out of range");
        }
    }

    /**
     * Refuses input names the table does not declare, so a misspelt name cannot silently fall to a default row.
     *
     * @param input the inputs
     * @throws InputInvalidException listing the undeclared names
     */
    public void requireOnlyDeclared(DecisionInput input) {
        java.util.Set<String> declared = new java.util.HashSet<>();
        inputs.forEach(clause -> declared.add(clause.name()));
        List<String> unknown = input.values().keySet().stream().filter(name -> !declared.contains(name)).sorted().toList();
        if (!unknown.isEmpty()) {
            throw new InputInvalidException("Rule set " + name + " does not declare the input(s) " + unknown);
        }
    }

    private DecisionResult.Outcome first(Object[] slots) {
        for (CompiledRule rule : rules) {
            if (evaluator.matches(rule.condition(), slots)) {
                return new DecisionResult.Matched(List.of(rule.id()), rowOutputs(rule, slots));
            }
        }
        return new DecisionResult.NoMatch();
    }

    /** Exactly one rule must match; zero or several is {@code UNIQUE_HIT_VIOLATION} with no outputs (RLE-002). */
    private DecisionResult.Outcome unique(Object[] slots) {
        List<CompiledRule> matched = matching(slots);
        if (matched.size() != 1) {
            List<String> ids = matched.stream().map(CompiledRule::id).toList();
            String detail = matched.isEmpty()
                    ? "No rule matched; a unique table must match exactly one rule"
                    : matched.size() + " rules matched " + ids + "; a unique table must match exactly one rule";
            return new DecisionResult.Failed("UNIQUE_HIT_VIOLATION", detail, ids);
        }
        CompiledRule rule = matched.getFirst();
        return new DecisionResult.Matched(List.of(rule.id()), rowOutputs(rule, slots));
    }

    /** Every matching rule contributes: a list in rule order, or the output's aggregate (RLE-003). */
    private DecisionResult.Outcome collect(Object[] slots) {
        List<CompiledRule> matched = matching(slots);
        if (matched.isEmpty()) {
            return new DecisionResult.NoMatch();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < outputs.size(); i++) {
            OutputClause output = outputs.get(i);
            ValueType element = new ValueType(output.type().kind(), false);
            List<Object> column = new ArrayList<>();
            for (CompiledRule rule : matched) {
                Object value = evaluator.evaluate(rule.thens().get(i), slots);
                if (value != null) {
                    column.add(output.aggregate() == OutputClause.Aggregate.COUNT
                            ? value : checkType(output.name(), element, value, rule.id()));
                }
            }
            values.put(output.name(), Aggregates.apply(output.aggregate(), column, output.name()));
        }
        return new DecisionResult.Matched(matched.stream().map(CompiledRule::id).toList(), values);
    }

    private List<CompiledRule> matching(Object[] slots) {
        List<CompiledRule> matched = new ArrayList<>();
        for (CompiledRule rule : rules) {
            if (evaluator.matches(rule.condition(), slots)) {
                matched.add(rule);
            }
        }
        return matched;
    }

    private Map<String, Object> rowOutputs(CompiledRule rule, Object[] slots) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < outputs.size(); i++) {
            OutputClause output = outputs.get(i);
            Object value = evaluator.evaluate(rule.thens().get(i), slots);
            if (output.type().list()) {
                throw new EvaluationException("Rule " + rule.id() + ": list output " + output.name()
                        + " is available under the collect hit policy only");
            }
            values.put(output.name(), value == null ? null : checkType(output.name(), output.type(), value, rule.id()));
        }
        return values;
    }

    /**
     * Binds inputs to slots, converting them to evaluation types.
     *
     * @param input the inputs
     * @return the slot array
     */
    Object[] bind(DecisionInput input) {
        Object[] slots = new Object[inputs.size()];
        for (int i = 0; i < inputs.size(); i++) {
            InputClause clause = inputs.get(i);
            slots[i] = normalize(input.get(clause.name()), clause.type(), clause.name());
        }
        return slots;
    }

    private static Object checkType(String name, ValueType type, Object value, String ruleId) {
        boolean ok = switch (type.kind()) {
            case STRING -> value instanceof String;
            case INTEGER -> value instanceof BigDecimal number && isIntegral(number);
            case DECIMAL -> value instanceof BigDecimal;
            case BOOLEAN -> value instanceof Boolean;
            case MONEY -> value instanceof Money money && Math.abs(money.minorUnits()) <= Money.MAX_JSON_SAFE;
            case DATE -> value instanceof LocalDate;
            case DATETIME -> value instanceof Instant;
        };
        if (type.list() || !ok) {
            throw new EvaluationException("Rule " + ruleId + ": output " + name + " is not of type " + type);
        }
        return value;
    }

    /** Publish-time checks of an output against the hit policy and its aggregate. */
    private static void checkOutput(HitPolicy policy, OutputClause output) {
        OutputClause.Aggregate aggregate = output.aggregate();
        ValueType type = output.type();
        if (policy != HitPolicy.COLLECT) {
            if (aggregate != OutputClause.Aggregate.NONE) {
                throw new ValidationFailedException("Output " + output.name() + ": collect is allowed only with hitPolicy collect");
            }
            if (type.list()) {
                throw new ValidationFailedException("Output " + output.name() + ": a list output needs hitPolicy collect");
            }
            return;
        }
        boolean ok = switch (aggregate) {
            case NONE -> type.list();
            case SUM -> !type.list() && (type.kind() == ValueType.Kind.INTEGER || type.kind() == ValueType.Kind.DECIMAL
                    || type.kind() == ValueType.Kind.MONEY);
            case MIN, MAX -> !type.list() && type.kind() != ValueType.Kind.BOOLEAN;
            case COUNT -> !type.list() && type.kind() == ValueType.Kind.INTEGER;
        };
        if (!ok) {
            throw new ValidationFailedException("Output " + output.name() + ": type " + type + " does not fit "
                    + (aggregate == OutputClause.Aggregate.NONE ? "a collected list; declare list<T>"
                    : "collect: " + aggregate.name().toLowerCase(java.util.Locale.ROOT)));
        }
    }

    private static Object normalize(Object value, ValueType type, String name) {
        if (value == null) {
            return null;
        }
        if (type.list()) {
            if (!(value instanceof List<?> list)) {
                throw new InputInvalidException("Input " + name + " must be a " + type);
            }
            List<Object> converted = new ArrayList<>(list.size());
            for (Object element : list) {
                converted.add(normalize(element, new ValueType(type.kind(), false), name));
            }
            return List.copyOf(converted);
        }
        Object converted = switch (type.kind()) {
            case STRING -> value instanceof String ? value : null;
            case BOOLEAN -> value instanceof Boolean ? value : null;
            case MONEY -> value instanceof Money ? value : null;
            case INTEGER -> {
                BigDecimal number = number(value);
                yield number != null && isIntegral(number) ? number : null;
            }
            case DECIMAL -> number(value);
            case DATE -> value instanceof LocalDate ? value : value instanceof String s ? parseDate(s, name) : null;
            case DATETIME -> value instanceof Instant ? value : value instanceof String s ? parseInstant(s, name) : null;
        };
        if (converted == null) {
            throw new InputInvalidException("Input " + name + " must be of type " + type);
        }
        return converted;
    }

    private static BigDecimal number(Object value) {
        return switch (value) {
            case BigDecimal d -> d;
            case BigInteger i -> new BigDecimal(i);
            case Long l -> BigDecimal.valueOf(l);
            case Integer i -> BigDecimal.valueOf(i);
            case Short s -> BigDecimal.valueOf(s);
            // Floating-point inputs are refused: they cannot carry exact decimal values.
            default -> null;
        };
    }

    private static boolean isIntegral(BigDecimal number) {
        return number.signum() == 0 || number.stripTrailingZeros().scale() <= 0;
    }

    private static LocalDate parseDate(String text, String name) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeException e) {
            throw new InputInvalidException("Input " + name + " must be an ISO 8601 date");
        }
    }

    private static Instant parseInstant(String text, String name) {
        try {
            return Instant.parse(text);
        } catch (DateTimeException e) {
            throw new InputInvalidException("Input " + name + " must be an ISO 8601 instant");
        }
    }

    private static Expr when(When when, Map<String, Integer> slots, List<InputClause> inputs, String ruleId) {
        List<Expr> parts = new ArrayList<>();
        for (Map.Entry<String, String> cell : when.cells().entrySet()) {
            parts.add(condition(cell.getKey(), cell.getValue(), slots, inputs, ruleId));
        }
        if (!when.any().isEmpty()) {
            Expr any = null;
            for (When nested : when.any()) {
                Expr next = when(nested, slots, inputs, ruleId);
                any = any == null ? next : new Expr.Binary(Expr.BinaryOp.OR, any, next);
            }
            parts.add(any);
        }
        for (When nested : when.all()) {
            parts.add(when(nested, slots, inputs, ruleId));
        }
        Expr result = new Expr.Literal(Boolean.TRUE);
        for (Expr part : parts) {
            result = result instanceof Expr.Literal ? part : new Expr.Binary(Expr.BinaryOp.AND, result, part);
        }
        return result;
    }

    private static Expr condition(String input, String text, Map<String, Integer> slots, List<InputClause> inputs,
            String ruleId) {
        String trimmed = text.strip();
        boolean shorthand = SHORTHAND_PREFIXES.stream().anyMatch(trimmed::startsWith);
        String source = shorthand ? input + " " + trimmed : trimmed;
        Expr expr;
        try {
            expr = Parser.parse(source, name -> slots.getOrDefault(name, -1));
        } catch (ExpressionException e) {
            throw new ValidationFailedException("Rule " + ruleId + ", input " + input + ": " + e.getMessage());
        }
        ConditionTypes.requireBoolean(expr, inputs, "Rule " + ruleId + ", input " + input);
        return expr;
    }

    private static Expr cell(Cell cell, Map<String, Integer> slots, String where) {
        return switch (cell) {
            case Cell.Literal literal -> new Expr.Literal(literal.value());
            case Cell.Expression expression -> {
                try {
                    yield Parser.parse(expression.source(), name -> slots.getOrDefault(name, -1));
                } catch (ExpressionException e) {
                    throw new ValidationFailedException(where + ": " + e.getMessage());
                }
            }
        };
    }

    private record CompiledRule(String id, Expr condition, List<Expr> thens) {
    }
}
