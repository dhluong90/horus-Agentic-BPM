package io.github.dhluong90.bpm.rules.internal.eval;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.api.Violation;
import io.github.dhluong90.bpm.expr.internal.EvaluationException;
import io.github.dhluong90.bpm.expr.internal.EvaluationLimits;
import io.github.dhluong90.bpm.expr.internal.Evaluator;
import io.github.dhluong90.bpm.expr.internal.Expr;
import io.github.dhluong90.bpm.expr.internal.ExpressionError;
import io.github.dhluong90.bpm.expr.internal.ExpressionException;
import io.github.dhluong90.bpm.expr.internal.RegexLimits;
import io.github.dhluong90.bpm.expr.internal.Parser;
import io.github.dhluong90.bpm.expr.spi.FunctionLibrary;
import io.github.dhluong90.bpm.expr.internal.RequestReads;
import io.github.dhluong90.bpm.rules.internal.model.Cell;
import io.github.dhluong90.bpm.rules.internal.model.DecisionTable;
import io.github.dhluong90.bpm.rules.internal.model.Definitions;
import io.github.dhluong90.bpm.rules.internal.model.HitPolicy;
import io.github.dhluong90.bpm.rules.internal.model.InputClause;
import io.github.dhluong90.bpm.rules.internal.model.OutputClause;
import io.github.dhluong90.bpm.rules.internal.model.Rule;
import io.github.dhluong90.bpm.rules.internal.model.ValueType;
import io.github.dhluong90.bpm.rules.internal.model.When;
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

    /** Code of a {@code then} cell naming an output the table does not declare. */
    public static final String UNKNOWN_OUTPUT = "UNKNOWN_OUTPUT";

    /** Code of an output cell that reads a masked input (RLE-012). */
    public static final String MASKED_INPUT_IN_OUTPUT = "MASKED_INPUT_IN_OUTPUT";

    private final String name;
    private final HitPolicy hitPolicy;
    private final List<InputClause> inputs;
    private final List<OutputClause> outputs;
    private final List<CompiledRule> rules;
    /** Shared and immutable; each evaluation runs on a copy scoped to its own regex read budget. */
    private final Evaluator shared;
    private final int maxOutputBytes;
    private final java.time.ZoneId timeZone;
    private final Set<String> inputsInOutputs;
    private final Set<String> inputsRead;
    private final EvaluationLimits evaluationLimits;
    /** The host functions the cells call, with their version labels, by name (RLE-058). */
    private final java.util.SortedMap<String, String> hostFunctions;
    /** The most steps one decision on this table can take (no loops: a sum of node counts). */
    private final long worstCaseSteps;
    /** The first rule from which the worst case is over the step budget, or null when it never is. */
    private final String overBudgetFrom;

    private CompiledRuleSet(Definitions model, List<CompiledRule> rules, int maxOutputBytes, int maxStringLength,
            RegexLimits regexLimits, EvaluationLimits evaluationLimits) {
        DecisionTable table = model.decision().table();
        this.name = model.name();
        this.timeZone = model.timeZone();
        this.hitPolicy = table.hitPolicy();
        this.inputs = table.inputs();
        this.outputs = table.outputs();
        this.rules = List.copyOf(rules);
        Set<String> referenced = new java.util.TreeSet<>();
        rules.forEach(rule -> rule.thens().forEach(then -> collectInputs(then, referenced)));
        this.inputsInOutputs = java.util.Collections.unmodifiableSet(referenced);
        Set<String> read = new java.util.TreeSet<>(referenced);
        rules.forEach(rule -> collectInputs(rule.condition(), read));
        this.inputsRead = java.util.Collections.unmodifiableSet(read);
        java.util.SortedMap<String, String> called = new java.util.TreeMap<>();
        rules.forEach(rule -> {
            collectHostFunctions(rule.condition(), called);
            rule.thens().forEach(then -> collectHostFunctions(then, called));
        });
        this.hostFunctions = java.util.Collections.unmodifiableSortedMap(called);
        this.shared = new Evaluator(table.moneyRounding(), maxStringLength, regexLimits, evaluationLimits);
        this.maxOutputBytes = maxOutputBytes;
        this.evaluationLimits = evaluationLimits;
        // Worst case over the rules seen so far, by hit policy: first stops at the first match (or reads every
        // condition and matches none); unique reads every condition and the outputs of one rule; collect reads every
        // condition and the outputs of every rule.
        long conditions = 0;
        long collected = 0;
        long widestRow = 0;
        long firstMatch = 0;
        long worst = 0;
        String over = null;
        for (CompiledRule rule : this.rules) {
            long row = 0;
            for (Expr then : rule.thens()) {
                row += EvaluationLimits.worstCase(then);
            }
            conditions += EvaluationLimits.worstCase(rule.condition());
            collected += row;
            widestRow = Math.max(widestRow, row);
            firstMatch = Math.max(firstMatch, conditions + row);
            worst = switch (hitPolicy) {
                case FIRST -> Math.max(conditions, firstMatch);
                case UNIQUE -> conditions + widestRow;
                case COLLECT -> conditions + collected;
            };
            if (over == null && worst > evaluationLimits.stepBudget()) {
                over = rule.id();
            }
        }
        this.worstCaseSteps = worst;
        this.overBudgetFrom = over;
    }

    /**
     * Compiles a model. Every cell is checked; all problems are reported together, each on its rule and column.
     *
     * @param model the decision model
     * @return the compiled rule set
     * @throws ValidationFailedException when a cell does not parse or has the wrong type, listing every such cell
     */
    public static CompiledRuleSet compile(Definitions model) {
        return compile(model, io.github.dhluong90.bpm.rules.internal.service.EngineDefaults.OUTPUT_MAX_BYTES,
                io.github.dhluong90.bpm.rules.internal.service.EngineDefaults.STRING_MAX_LENGTH);
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
        return compile(model, maxOutputBytes, maxStringLength, RegexLimits.defaults());
    }

    /**
     * Compiles a model with size limits and the bounds of {@code matches} (TR-SEC-02).
     *
     * @param model the decision model
     * @param maxOutputBytes the largest estimated JSON size of one decision's outputs
     * @param maxStringLength the longest string {@code +} may build
     * @param regexLimits the length and time bounds of one regular expression match
     * @return the compiled rule set
     */
    public static CompiledRuleSet compile(Definitions model, int maxOutputBytes, int maxStringLength,
            RegexLimits regexLimits) {
        return compile(model, maxOutputBytes, maxStringLength, regexLimits, EvaluationLimits.defaults());
    }

    /**
     * Compiles a model with size limits, the bounds of {@code matches} (TR-SEC-02) and the step budget of one
     * decision (TR-SEC-01).
     *
     * @param model the decision model
     * @param maxOutputBytes the largest estimated JSON size of one decision's outputs
     * @param maxStringLength the longest string {@code +} may build
     * @param regexLimits the length and time bounds of one regular expression match
     * @param evaluationLimits the step budget and time backstop of one decision
     * @return the compiled rule set
     */
    public static CompiledRuleSet compile(Definitions model, int maxOutputBytes, int maxStringLength,
            RegexLimits regexLimits, EvaluationLimits evaluationLimits) {
        return compile(model, maxOutputBytes, maxStringLength, regexLimits, evaluationLimits, FunctionLibrary.empty());
    }

    /**
     * Compiles a model with size limits, the bounds of {@code matches}, the step budget of one decision and the
     * functions the host registered (RLE-055, RLE-056): a cell may call a registered function, with the number and
     * types of arguments it declares; any other call is {@code FORBIDDEN_CONSTRUCT}.
     *
     * @param model the decision model
     * @param maxOutputBytes the largest estimated JSON size of one decision's outputs
     * @param maxStringLength the longest string {@code +} may build
     * @param regexLimits the length and time bounds of one regular expression match
     * @param evaluationLimits the step budget and time backstop of one decision
     * @param functions the host's functions, never null
     * @return the compiled rule set
     */
    public static CompiledRuleSet compile(Definitions model, int maxOutputBytes, int maxStringLength,
            RegexLimits regexLimits, EvaluationLimits evaluationLimits, FunctionLibrary functions) {
        DecisionTable table = model.decision().table();
        for (OutputClause output : table.outputs()) {
            checkOutput(table.hitPolicy(), output);
        }
        Map<String, Integer> slots = new HashMap<>();
        for (int i = 0; i < table.inputs().size(); i++) {
            slots.put(table.inputs().get(i).name(), i);
        }
        // The frame is the inputs, then today() and now() (DESIGN 3.2): bound once here, read without a lookup.
        slots.put(Expr.ClockFunction.TODAY.slotName(), table.inputs().size());
        slots.put(Expr.ClockFunction.NOW.slotName(), table.inputs().size() + 1);
        Problems problems = new Problems(MAX_VIOLATIONS, functions);
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
                    problems.add(new Violation(rule.id(), cell.getKey(), "unknown output '" + cell.getKey() + "'",
                            UNKNOWN_OUTPUT));
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
        return new CompiledRuleSet(model, rules, maxOutputBytes, maxStringLength, regexLimits, evaluationLimits);
    }

    /**
     * Returns the most evaluation steps one decision on this table can take. The language has no loops, so this is
     * a sum of node counts: every condition, plus the outputs of the rules a hit policy can evaluate. The count of a
     * given table may change in a minor release; the guarantee is the step budget and the reason code.
     *
     * @return the worst-case steps
     */
    public long worstCaseSteps() {
        return worstCaseSteps;
    }

    /**
     * Refuses a table that could fail every evaluation with {@code EVALUATION_BUDGET_EXCEEDED}: one whose worst case
     * is over the step budget. Import and request-publish call it, so the failure shows at authoring time and not
     * in production. Evaluation does not: a version already in effect keeps the runtime bound.
     *
     * @throws ValidationFailedException with one violation, code {@code EVALUATION_BUDGET_EXCEEDED}, on the first rule
     *     from which the worst case is over the budget
     */
    public void requireWithinStepBudget() {
        if (overBudgetFrom == null) {
            return;
        }
        String policy = hitPolicy.name().toLowerCase(java.util.Locale.ROOT);
        throw new ValidationFailedException(List.of(new Violation(overBudgetFrom, null, String.format(
                java.util.Locale.ROOT, "the table can take up to %,d evaluation steps under hit policy %s, more than"
                        + " the step budget of %,d; from this rule on, a decision could fail with"
                        + " EVALUATION_BUDGET_EXCEEDED. Split the table or simplify its cells",
                worstCaseSteps, policy, evaluationLimits.stepBudget()), EvaluationLimits.EVALUATION_BUDGET_EXCEEDED)),
                false);
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
     * Returns the output clauses, in declaration order.
     *
     * @return the outputs
     */
    public List<OutputClause> outputs() {
        return outputs;
    }

    /**
     * Returns the zone of {@code today()}, the rule set's {@code timeZone} (UTC when the document names none).
     *
     * @return the zone
     */
    public java.time.ZoneId timeZone() {
        return timeZone;
    }

    /**
     * Evaluates the table without a clock: a rule that reads {@code today()} or {@code now()} fails with
     * {@code EVALUATION_ERROR}. The engine always passes the decision's clock (see {@link #evaluate(DecisionInput,
     * Instant)}); this form is for tables that do not read the clock.
     *
     * @param input the inputs
     * @return the outcome
     * @throws InputInvalidException when an input does not match its declared type
     */
    public DecisionResult.Outcome evaluate(DecisionInput input) {
        return evaluate(input, null);
    }

    /**
     * Evaluates the table at a clock. Input type errors throw; evaluation errors are returned as {@code Failed}.
     * {@code today()} is the clock's date in {@link #timeZone()} and {@code now()} the clock itself, so the same
     * version, inputs and clock always give the same outcome (TR-DET-01).
     *
     * @param input the inputs
     * @param clock the instant the decision reasons about, or null for none
     * @return the outcome
     * @throws InputInvalidException when an input does not match its declared type
     */
    public DecisionResult.Outcome evaluate(DecisionInput input, Instant clock) {
        return evaluateWithReason(input, clock).outcome();
    }

    /**
     * Evaluates the table without a clock and tells why a failed evaluation failed.
     *
     * @param input the inputs
     * @return the outcome, and the reason of an {@code EVALUATION_ERROR} that has one, else null
     * @throws InputInvalidException when an input does not match its declared type
     */
    public Evaluation evaluateWithReason(DecisionInput input) {
        return evaluateWithReason(input, null);
    }

    /**
     * Evaluates the table at a clock and tells why a failed evaluation failed, for the decision row's
     * {@code error_code}.
     *
     * @param input the inputs
     * @param clock the instant the decision reasons about, or null for none
     * @return the outcome, and the reason of an {@code EVALUATION_ERROR} that has one (for example
     *     {@code REGEX_TIMEOUT}), else null
     * @throws InputInvalidException when an input does not match its declared type
     */
    public Evaluation evaluateWithReason(DecisionInput input, Instant clock) {
        return evaluateWithReason(input, clock, null);
    }

    /**
     * Evaluates the table at a clock as one decision of a request of many decisions, charging its regular expression
     * reads to the request's budget too ({@code evaluation.regex-max-reads-per-request}, RLE-007). When the request's
     * budget stops a match, {@link RequestReads#spent()} turns true and the caller evaluates no further item.
     *
     * @param input the inputs
     * @param clock the instant the decision reasons about, or null for none
     * @param request the request's read budget, from {@code RegexLimits.newRequest()}, or null for a decision on its
     *     own
     * @return the outcome, and the reason of an {@code EVALUATION_ERROR} that has one, else null
     * @throws InputInvalidException when an input does not match its declared type
     */
    public Evaluation evaluateWithReason(DecisionInput input, Instant clock, RequestReads request) {
        Object[] slots = bind(input, clock);
        // One read budget for every matches() of this decision (evaluation.regex-max-reads-per-decision, RLE-007),
        // charged to the request's budget too when there is one (evaluation.regex-max-reads-per-request).
        Evaluator decision = shared.forDecision(request);
        try {
            return new Evaluation(switch (hitPolicy) {
                case FIRST -> first(decision, slots);
                case UNIQUE -> unique(decision, slots);
                case COLLECT -> collect(decision, slots);
            }, null);
        } catch (EvaluationException e) {
            return new Evaluation(new DecisionResult.Failed("EVALUATION_ERROR", e.getMessage(), List.of(), e.limit()),
                    e.reason(), e.getCause());
        } catch (ArithmeticException e) {
            // Exponent overflow or underflow in DECIMAL128 arithmetic: a deterministic failure, never a 500.
            return new Evaluation(new DecisionResult.Failed("EVALUATION_ERROR", "Arithmetic out of range"), null);
        }
    }

    /**
     * An outcome and, for a failure, its reason code.
     *
     * @param outcome the outcome
     * @param reason the reason of a failure that has one, else null
     * @param cause what a host function threw, for the server log only, never for a response; else null
     */
    public record Evaluation(DecisionResult.Outcome outcome, String reason, Throwable cause) {

        /**
         * An outcome with no failure behind it that the server log needs.
         *
         * @param outcome the outcome
         * @param reason the reason of a failure that has one, else null
         */
        public Evaluation(DecisionResult.Outcome outcome, String reason) {
            this(outcome, reason, null);
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

    private DecisionResult.Outcome first(Evaluator evaluator, Object[] slots) {
        for (CompiledRule rule : rules) {
            if (evaluator.matches(rule.condition(), slots)) {
                return new DecisionResult.Matched(List.of(rule.id()), rowOutputs(evaluator, rule, slots));
            }
        }
        return noMatch();
    }

    /**
     * Exactly one rule must match (RLE-002, DMN 1.5 as decided in PD-088): no match is {@code NoMatch}, as under
     * {@code first}; several matches are {@code UNIQUE_HIT_VIOLATION} with every matched rule id and no outputs.
     */
    private DecisionResult.Outcome unique(Evaluator evaluator, Object[] slots) {
        List<CompiledRule> matched = matching(evaluator, slots);
        if (matched.isEmpty()) {
            return noMatch();
        }
        if (matched.size() > 1) {
            return new DecisionResult.Failed("UNIQUE_HIT_VIOLATION",
                    matched.size() + " rules matched; a unique table must match exactly one rule",
                    matched.stream().map(CompiledRule::id).toList());
        }
        CompiledRule rule = matched.getFirst();
        return new DecisionResult.Matched(List.of(rule.id()), rowOutputs(evaluator, rule, slots));
    }

    /**
     * Every matching rule contributes (RLE-003, DMN 1.5): a list output keeps one entry per matched rule, null
     * included, so parallel lists stay aligned with each other and with the matched rule ids; {@code count} counts
     * every matched rule, nulls included (FEEL {@code count}); a null under {@code sum}, {@code min} or {@code max}
     * is {@code EVALUATION_ERROR} (errorMode strict, PD-066). Aggregates are over all values, not distinct ones.
     * With no match every output is null.
     */
    private DecisionResult.Outcome collect(Evaluator evaluator, Object[] slots) {
        List<CompiledRule> matched = matching(evaluator, slots);
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

    /**
     * No rule matched: every declared output is null, under every hit policy, except a {@code collect: count}
     * output, which is 0 as FEEL {@code count([])} is (RLE-002, RLE-003, RLE-227).
     */
    private DecisionResult.NoMatch noMatch() {
        Map<String, Object> values = new LinkedHashMap<>();
        outputs.forEach(output -> values.put(output.name(),
                output.aggregate() == OutputClause.Aggregate.COUNT ? java.math.BigDecimal.ZERO : null));
        return new DecisionResult.NoMatch(values);
    }

    private List<CompiledRule> matching(Evaluator evaluator, Object[] slots) {
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

    private Map<String, Object> rowOutputs(Evaluator evaluator, CompiledRule rule, Object[] slots) {
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
     * Binds inputs to slots, converting them to evaluation types, then {@code today()} and {@code now()}.
     *
     * @param input the inputs
     * @param clock the evaluation clock, or null for none
     * @return the slot array: the inputs in declaration order, then today and now
     */
    Object[] bind(DecisionInput input, Instant clock) {
        Object[] slots = new Object[inputs.size() + 2];
        for (int i = 0; i < inputs.size(); i++) {
            InputClause clause = inputs.get(i);
            slots[i] = normalize(input.get(clause.name()), clause.type(), clause.name());
        }
        if (clock != null) {
            slots[inputs.size()] = LocalDate.ofInstant(clock, timeZone);
            slots[inputs.size() + 1] = clock;
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
            problems.add(new Violation(ruleId, input, "unknown input '" + input + "'",
                    ExpressionError.UNKNOWN_INPUT.name()));
            return new Expr.Literal(Boolean.FALSE);
        }
        if (text == null) {
            problems.add(new Violation(ruleId, input, "the condition must be a non-empty string",
                    ExpressionError.SYNTAX_ERROR.name()));
            return new Expr.Literal(Boolean.FALSE);
        }
        String source = conditionSource(input, text);
        // A shorthand cell such as "< 18" is parsed as "age < 18"; positions are reported in the cell as written.
        int shift = source.equals(text.strip()) ? -leadingSpaces(text) : input.length() + 1 - leadingSpaces(text);
        Parser.Located located;
        try {
            located = Parser.parseLocated(source, name -> slots.getOrDefault(name, -1), input, problems.functions);
        } catch (ExpressionException e) {
            problems.add(located(ruleId, input, e.error(), e.problem(), e.position() - shift,
                    e.hint() == null ? null : e.hint().name()));
            return new Expr.Literal(Boolean.FALSE);
        }
        Expr expr = located.expr();
        ConditionTypes.TypeProblem operands = ConditionTypes.operandProblem(expr, inputs);
        if (operands != null) {
            problems.add(located(ruleId, input, ExpressionError.TYPE_MISMATCH, operands.message(),
                    located.position(operands.node()) - shift));
            return expr;
        }
        String problem = ConditionTypes.conditionProblem(expr, inputs);
        if (problem != null) {
            problems.add(new Violation(ruleId, input, problem, ExpressionError.TYPE_MISMATCH.name()));
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

    /**
     * Returns the inputs that output cells or output defaults read. A masked input may never be one of them, or its
     * plain value would reach the decision log through the outputs (RLE-012).
     *
     * @return the input names, sorted
     */
    public Set<String> inputsInOutputs() {
        return inputsInOutputs;
    }

    /**
     * Returns the inputs that any rule condition or output cell reads, as against the inputs the table merely
     * declares. An offline replay uses it to tell whether a field it could not keep makes a difference, and a logged
     * value of an input the table never reads (a masked one, say) does not stop the table being evaluated again
     * (RLE-076).
     *
     * @return the input names, sorted
     */
    public Set<String> inputsRead() {
        return inputsRead;
    }

    /**
     * Returns the host functions the rule set's cells call, each with the version label it was compiled against.
     *
     * @return function name to version label, in name order; empty when no cell calls one
     */
    public java.util.SortedMap<String, String> hostFunctions() {
        return hostFunctions;
    }

    private static void collectHostFunctions(Expr expr, java.util.Map<String, String> into) {
        if (expr instanceof Expr.HostCall call) {
            into.put(call.function().name(), call.function().version());
        }
        ConditionTypes.childrenOf(expr).forEach(child -> collectHostFunctions(child, into));
    }

    /** Adds the input names an expression reads. Exhaustive on purpose: a new node type must say what it reads. */
    static void collectInputs(Expr expr, Set<String> names) {
        switch (expr) {
            case Expr.Literal ignored -> { }
            case Expr.Var var -> names.add(var.name());
            case Expr.Unary unary -> collectInputs(unary.operand(), names);
            case Expr.Binary binary -> {
                collectInputs(binary.left(), names);
                collectInputs(binary.right(), names);
            }
            case Expr.InList inList -> {
                collectInputs(inList.target(), names);
                inList.elements().forEach(element -> collectInputs(element, names));
            }
            case Expr.Between between -> {
                collectInputs(between.target(), names);
                collectInputs(between.low(), names);
                collectInputs(between.high(), names);
            }
            case Expr.IsNull isNull -> collectInputs(isNull.target(), names);
            case Expr.Matches matches -> collectInputs(matches.target(), names);
            case Expr.Call call -> call.args().forEach(arg -> collectInputs(arg, names));
            case Expr.HostCall call -> call.args().forEach(arg -> collectInputs(arg, names));
            case Expr.Clock ignored -> { }   // the clock is not an input
        }
    }

    /**
     * A violation at a character of a cell. The position goes in the message too, as the parser has always written
     * it; a position that falls before the cell (the input name of a shorthand) is reported as the cell's start.
     */
    private static Violation located(String ruleId, String column, ExpressionError error, String problem, int position) {
        return located(ruleId, column, error, problem, position, null);
    }

    private static Violation located(String ruleId, String column, ExpressionError error, String problem, int position,
            String syntax) {
        int at = Math.max(0, position);
        return new Violation(ruleId, column, problem + " at position " + at, error.name(), at, syntax);
    }

    private static int leadingSpaces(String text) {
        int i = 0;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    /** Parses one output cell (or an output default, with no rule id) and checks it against the output's type. */
    private static Expr output(Cell cell, OutputClause output, Map<String, Integer> slots, List<InputClause> inputs,
            String ruleId, Problems problems) {
        Expr expr = switch (cell) {
            case Cell.Literal literal -> new Expr.Literal(literal.value());
            case Cell.Expression expression when expression.source() == null -> {
                problems.add(new Violation(ruleId, output.name(), "a cell must be a string, number, boolean or null",
                        ExpressionError.SYNTAX_ERROR.name()));
                yield new Expr.Literal(null);
            }
            case Cell.Expression expression -> {
                try {
                    Parser.Located located = Parser.parseLocated(expression.source(),
                            name -> slots.getOrDefault(name, -1), null, problems.functions);
                    ConditionTypes.TypeProblem operands = ConditionTypes.operandProblem(located.expr(), inputs);
                    if (operands != null) {
                        problems.add(located(ruleId, output.name(), ExpressionError.TYPE_MISMATCH,
                                "output " + output.name() + ": " + operands.message(),
                                located.position(operands.node())));
                        yield new Expr.Literal(null);
                    }
                    yield located.expr();
                } catch (ExpressionException e) {
                    problems.add(located(ruleId, output.name(), e.error(), e.problem(), e.position(),
                            e.hint() == null ? null : e.hint().name()));
                    yield new Expr.Literal(null);
                }
            }
        };
        String problem = ConditionTypes.outputProblem(expr, output, inputs);
        if (problem != null) {
            problems.add(new Violation(ruleId, output.name(), problem, ExpressionError.TYPE_MISMATCH.name()));
        }
        Set<String> read = new java.util.TreeSet<>();
        collectInputs(expr, read);
        for (InputClause input : inputs) {
            if (input.masked() && read.contains(input.name())) {
                problems.add(new Violation(ruleId, output.name(), "output " + output.name() + " uses input "
                        + input.name() + ", which is masked; a masked input can never be copied to an output",
                        MASKED_INPUT_IN_OUTPUT));
            }
        }
        return expr;
    }

    /** Collects violations up to a cap and remembers whether more were found. */
    private static final class Problems {
        private final int cap;
        private final List<Violation> kept = new ArrayList<>();
        private boolean truncated;

        private final FunctionLibrary functions;

        Problems(int cap, FunctionLibrary functions) {
            this.cap = cap;
            this.functions = functions;
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
