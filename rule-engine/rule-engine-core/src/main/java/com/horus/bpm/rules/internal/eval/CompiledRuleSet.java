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
    private final int maxOutputBytes;

    private CompiledRuleSet(Definitions model, List<CompiledRule> rules, int maxOutputBytes, int maxStringLength) {
        DecisionTable table = model.decision().table();
        this.name = model.name();
        this.hitPolicy = table.hitPolicy();
        this.inputs = table.inputs();
        this.outputs = table.outputs();
        this.rules = List.copyOf(rules);
        this.evaluator = new Evaluator(table.moneyRounding(), maxStringLength);
        this.maxOutputBytes = maxOutputBytes;
    }

    /**
     * Compiles a model. Every cell is checked; all problems are reported together, each on its rule and column.
     *
     * @param model the decision model
     * @return the compiled rule set
     * @throws ValidationFailedException when a cell does not parse or has the wrong type, listing every such cell
     */
    public static CompiledRuleSet compile(Definitions model) {
        return compile(model, com.horus.bpm.rules.internal.service.EngineDefaults.OUTPUT_MAX_BYTES,
                com.horus.bpm.rules.internal.service.EngineDefaults.STRING_MAX_LENGTH);
    }

    /**
     * Compiles a model with size limits on the outputs of one decision and on built strings.
     *
     * @param model the decision model
     * @param maxOutputBytes the largest estimated JSON size of one decision's outputs
     * @param maxStringLength the longest string {@code +} may build
     * @return the compiled rule set
     */
    public static CompiledRuleSet compile(Definitions model, int maxOutputBytes, int maxStringLength) {
        DecisionTable table = model.decision().table();
        for (OutputClause output : table.outputs()) {
            checkOutput(table.hitPolicy(), output);
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
        Map<String, OutputClause> declared = new LinkedHashMap<>();
        table.outputs().forEach(output -> declared.put(output.name(), output));
        for (Rule rule : table.rules()) {
            Expr condition = when(rule.when(), slots, table.inputs(), rule.id(), problems);
            // Output cells are checked in the order they are written, so violations follow the document (RLE-041),
            // and placed by declaration order for evaluation.
            Map<String, Expr> written = new HashMap<>();
            for (Map.Entry<String, Cell> cell : rule.then().entrySet()) {
                OutputClause output = declared.get(cell.getKey());
                if (output == null) {
                    problems.add(new Violation(rule.id(), cell.getKey(), "unknown output '" + cell.getKey() + "'"));
                } else {
                    written.put(output.name(), output(cell.getValue(), output, slots, table.inputs(), rule.id(), problems));
                }
            }
            List<Expr> thens = new ArrayList<>();
            for (OutputClause output : table.outputs()) {
                thens.add(written.getOrDefault(output.name(), defaults.get(output.name())));
            }
            rules.add(new CompiledRule(rule.id(), condition, thens));
        }
        if (!problems.kept.isEmpty()) {
            throw new ValidationFailedException(problems.kept, problems.truncated);
        }
        return new CompiledRuleSet(model, rules, maxOutputBytes, maxStringLength);
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
        return noMatch();
    }

    /**
     * Exactly one rule must match (RLE-002, DMN 1.5 as decided in PD-088): no match is {@code NoMatch}, as under
     * {@code first}; several matches are {@code UNIQUE_HIT_VIOLATION} with every matched rule id and no outputs.
     */
    private DecisionResult.Outcome unique(Object[] slots) {
        List<CompiledRule> matched = matching(slots);
        if (matched.isEmpty()) {
            return noMatch();
        }
        if (matched.size() > 1) {
            return new DecisionResult.Failed("UNIQUE_HIT_VIOLATION",
                    matched.size() + " rules matched; a unique table must match exactly one rule",
                    matched.stream().map(CompiledRule::id).toList());
        }
        CompiledRule rule = matched.getFirst();
        return new DecisionResult.Matched(List.of(rule.id()), rowOutputs(rule, slots));
    }

    /**
     * Every matching rule contributes (RLE-003, DMN 1.5): a list output keeps one entry per matched rule, null
     * included, so parallel lists stay aligned with each other and with the matched rule ids; {@code count} counts
     * every matched rule, nulls included (FEEL {@code count}); a null under {@code sum}, {@code min} or {@code max}
     * is {@code EVALUATION_ERROR} (errorMode strict, PD-066). Aggregates are over all values, not distinct ones.
     * With no match every output is null.
     */
    private DecisionResult.Outcome collect(Object[] slots) {
        List<CompiledRule> matched = matching(slots);
        if (matched.isEmpty()) {
            return noMatch();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        long[] bytes = {0};
        for (int i = 0; i < outputs.size(); i++) {
            OutputClause output = outputs.get(i);
            ValueType element = new ValueType(output.type().kind(), false);
            boolean list = output.aggregate() == OutputClause.Aggregate.NONE;
            // A list keeps one entry per matched rule and counts against max-output-bytes as it grows; an
            // aggregate folds each value in at once, so no column of values is ever held (security review of #18).
            List<Object> column = list ? new ArrayList<>() : null;
            Aggregates.Accumulator accumulator = list ? null : new Aggregates.Accumulator(output.aggregate(), output.name());
            for (CompiledRule rule : matched) {
                Object value = evaluator.evaluate(rule.thens().get(i), slots);
                if (value != null && output.aggregate() != OutputClause.Aggregate.COUNT) {
                    value = checkType(output.name(), element, value, rule.id());
                }
                if (list) {
                    column.add(value);
                    addBytes(bytes, value);
                } else if (value == null && output.aggregate() != OutputClause.Aggregate.COUNT) {
                    // null is outside the domain of FEEL sum, min and max; v1 evaluates in errorMode strict
                    // (PD-066), so the decision fails, with the matched rules logged (RLE-003 (d)).
                    return new DecisionResult.Failed("EVALUATION_ERROR", "Output " + output.name() + ": rule "
                            + rule.id() + " gives null, which collect: "
                            + output.aggregate().name().toLowerCase(java.util.Locale.ROOT)
                            + " cannot take (errorMode strict)", matched.stream().map(CompiledRule::id).toList());
                } else {
                    accumulator.add(value);
                }
            }
            Object result = list ? java.util.Collections.unmodifiableList(column) : accumulator.result();
            if (!list) {
                addBytes(bytes, result);
            }
            values.put(output.name(), result);
        }
        return new DecisionResult.Matched(matched.stream().map(CompiledRule::id).toList(), values);
    }

    /** No rule matched: every declared output is null, under every hit policy (RLE-002, RLE-003). */
    private DecisionResult.NoMatch noMatch() {
        Map<String, Object> values = new LinkedHashMap<>();
        outputs.forEach(output -> values.put(output.name(), null));
        return new DecisionResult.NoMatch(values);
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

    /** Adds a value's estimated JSON size and fails once the decision's outputs exceed {@code max-output-bytes}. */
    private void addBytes(long[] total, Object value) {
        total[0] += estimatedBytes(value);
        if (total[0] > maxOutputBytes) {
            throw new EvaluationException("Outputs larger than " + maxOutputBytes + " bytes");
        }
    }

    private static long estimatedBytes(Object value) {
        return switch (value) {
            case null -> 4;
            // UTF-8 needs at most 3 bytes per UTF-16 code unit; 2 for the quotes.
            case String s -> 2L + 3L * s.length();
            case java.util.List<?> list -> {
                long sum = 2;
                for (Object element : list) {
                    sum += 1 + estimatedBytes(element);
                }
                yield sum;
            }
            default -> 48;
        };
    }

    private Map<String, Object> rowOutputs(CompiledRule rule, Object[] slots) {
        Map<String, Object> values = new LinkedHashMap<>();
        long[] bytes = {0};
        for (int i = 0; i < outputs.size(); i++) {
            OutputClause output = outputs.get(i);
            Object value = evaluator.evaluate(rule.thens().get(i), slots);
            if (output.type().list()) {
                throw new EvaluationException("Rule " + rule.id() + ": list output " + output.name()
                        + " is available under the collect hit policy only");
            }
            values.put(output.name(), value == null ? null : checkType(output.name(), output.type(), value, rule.id()));
            addBytes(bytes, value);
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
        if (!slots.containsKey(input)) {
            problems.add(new Violation(ruleId, input, "unknown input '" + input + "'"));
            return new Expr.Literal(Boolean.FALSE);
        }
        if (text == null) {
            problems.add(new Violation(ruleId, input, "the condition must be a non-empty string"));
            return new Expr.Literal(Boolean.FALSE);
        }
        String source = conditionSource(input, text);
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

    /**
     * Returns the full expression of a condition cell: a shorthand such as {@code "< 18"} is prefixed with its
     * input name, a full expression is returned as written.
     *
     * @param input the input the cell is keyed by
     * @param text the cell text
     * @return the expression source
     */
    public static String conditionSource(String input, String text) {
        String trimmed = text.strip();
        boolean shorthand = SHORTHAND_PREFIXES.stream().anyMatch(trimmed::startsWith);
        return shorthand ? input + " " + trimmed : trimmed;
    }

    /** Parses one output cell (or an output default, with no rule id) and checks it against the output's type. */
    private static Expr output(Cell cell, OutputClause output, Map<String, Integer> slots, List<InputClause> inputs,
            String ruleId, Problems problems) {
        Expr expr = switch (cell) {
            case Cell.Literal literal -> new Expr.Literal(literal.value());
            case Cell.Expression expression when expression.source() == null -> {
                problems.add(new Violation(ruleId, output.name(), "a cell must be a string, number, boolean or null"));
                yield new Expr.Literal(null);
            }
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
