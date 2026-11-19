package com.horus.bpm.rules.internal.eval;

import com.horus.bpm.rules.api.DecisionInput;
import com.horus.bpm.rules.api.DecisionResult;
import com.horus.bpm.rules.api.InputInvalidException;
import com.horus.bpm.rules.api.Money;
import com.horus.bpm.rules.api.ValidationFailedException;
import com.horus.bpm.rules.api.Violation;
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

    /**
     * Most violations one {@code 422 VALIDATION_FAILED} lists; further problems only set the truncated flag, so a
     * document of many small mistakes cannot make the answer larger than the request. A safety bound, not a setting.
     */
    public static final int MAX_VIOLATIONS = 100;

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
     * Compiles a model. Every cell is checked; all problems are reported together, each on its rule and column.
     *
     * @param model the decision model
     * @return the compiled rule set
     * @throws ValidationFailedException when a cell does not parse or has the wrong type, listing every such cell
     */
    public static CompiledRuleSet compile(Definitions model) {
        DecisionTable table = model.decision().table();
        if (table.hitPolicy() != HitPolicy.FIRST) {
            throw new ValidationFailedException("Hit policy " + table.hitPolicy() + " is not available in this release");
        }
        Map<String, Integer> slots = new HashMap<>();
        for (int i = 0; i < table.inputs().size(); i++) {
            slots.put(table.inputs().get(i).name(), i);
        }
        Problems problems = new Problems(MAX_VIOLATIONS);
        Map<String, Expr> defaults = new LinkedHashMap<>();
        for (OutputClause output : table.outputs()) {
            defaults.put(output.name(), output.defaultValue() == null
                    ? new Expr.Literal(null) : output(output.defaultValue(), output, slots, table.inputs(), null, problems));
        }
        List<CompiledRule> rules = new ArrayList<>();
        for (Rule rule : table.rules()) {
            Expr condition = when(rule.when(), slots, table.inputs(), rule.id(), problems);
            List<Expr> thens = new ArrayList<>();
            for (OutputClause output : table.outputs()) {
                Cell cell = rule.then().get(output.name());
                thens.add(cell == null ? defaults.get(output.name())
                        : output(cell, output, slots, table.inputs(), rule.id(), problems));
            }
            rules.add(new CompiledRule(rule.id(), condition, thens));
        }
        if (!problems.kept.isEmpty()) {
            throw new ValidationFailedException(problems.kept, problems.truncated);
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
            for (CompiledRule rule : rules) {
                if (evaluator.matches(rule.condition(), slots)) {
                    Map<String, Object> values = new LinkedHashMap<>();
                    for (int i = 0; i < outputs.size(); i++) {
                        OutputClause output = outputs.get(i);
                        values.put(output.name(), output(output, evaluator.evaluate(rule.thens().get(i), slots), rule.id()));
                    }
                    return new DecisionResult.Matched(List.of(rule.id()), values);
                }
            }
            return new DecisionResult.NoMatch();
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

    private static Object output(OutputClause output, Object value, String ruleId) {
        if (value == null) {
            return null;
        }
        boolean ok = switch (output.type().kind()) {
            case STRING -> value instanceof String;
            case INTEGER -> value instanceof BigDecimal number && isIntegral(number);
            case DECIMAL -> value instanceof BigDecimal;
            case BOOLEAN -> value instanceof Boolean;
            case MONEY -> value instanceof Money money && Math.abs(money.minorUnits()) <= Money.MAX_JSON_SAFE;
            case DATE -> value instanceof LocalDate;
            case DATETIME -> value instanceof Instant;
        };
        if (output.type().list() || !ok) {
            throw new EvaluationException("Rule " + ruleId + ": output " + output.name() + " is not of type " + output.type());
        }
        return value;
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

    private static Expr when(When when, Map<String, Integer> slots, List<InputClause> inputs, String ruleId,
            Problems problems) {
        List<Expr> parts = new ArrayList<>();
        for (Map.Entry<String, String> cell : when.cells().entrySet()) {
            parts.add(condition(cell.getKey(), cell.getValue(), slots, inputs, ruleId, problems));
        }
        if (!when.any().isEmpty()) {
            Expr any = null;
            for (When nested : when.any()) {
                Expr next = when(nested, slots, inputs, ruleId, problems);
                any = any == null ? next : new Expr.Binary(Expr.BinaryOp.OR, any, next);
            }
            parts.add(any);
        }
        for (When nested : when.all()) {
            parts.add(when(nested, slots, inputs, ruleId, problems));
        }
        Expr result = new Expr.Literal(Boolean.TRUE);
        for (Expr part : parts) {
            result = result instanceof Expr.Literal ? part : new Expr.Binary(Expr.BinaryOp.AND, result, part);
        }
        return result;
    }

    /** Parses and type-checks one condition cell; a problem is recorded on its cell and a placeholder returned. */
    private static Expr condition(String input, String text, Map<String, Integer> slots, List<InputClause> inputs,
            String ruleId, Problems problems) {
        String trimmed = text.strip();
        boolean shorthand = SHORTHAND_PREFIXES.stream().anyMatch(trimmed::startsWith);
        String source = shorthand ? input + " " + trimmed : trimmed;
        Expr expr;
        try {
            expr = Parser.parse(source, name -> slots.getOrDefault(name, -1));
        } catch (ExpressionException e) {
            problems.add(new Violation(ruleId, input, e.getMessage()));
            return new Expr.Literal(Boolean.FALSE);
        }
        String problem = ConditionTypes.conditionProblem(expr, inputs);
        if (problem != null) {
            problems.add(new Violation(ruleId, input, problem));
        }
        return expr;
    }

    /** Parses one output cell (or an output default, with no rule id) and checks it against the output's type. */
    private static Expr output(Cell cell, OutputClause output, Map<String, Integer> slots, List<InputClause> inputs,
            String ruleId, Problems problems) {
        Expr expr = switch (cell) {
            case Cell.Literal literal -> new Expr.Literal(literal.value());
            case Cell.Expression expression -> {
                try {
                    yield Parser.parse(expression.source(), name -> slots.getOrDefault(name, -1));
                } catch (ExpressionException e) {
                    problems.add(new Violation(ruleId, output.name(), e.getMessage()));
                    yield new Expr.Literal(null);
                }
            }
        };
        String problem = ConditionTypes.outputProblem(expr, output, inputs);
        if (problem != null) {
            problems.add(new Violation(ruleId, output.name(), problem));
        }
        return expr;
    }

    /** Collects violations up to a cap and remembers whether more were found. */
    private static final class Problems {
        private final int cap;
        private final List<Violation> kept = new ArrayList<>();
        private boolean truncated;

        Problems(int cap) {
            this.cap = cap;
        }

        void add(Violation violation) {
            if (kept.size() < cap) {
                kept.add(violation);
            } else {
                truncated = true;
            }
        }
    }

    private record CompiledRule(String id, Expr condition, List<Expr> thens) {
    }
}
