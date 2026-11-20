package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.spi.OutputSchemaValidator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The result check of agent steps ({@code ASYNC_AGENT}, #363, DESIGN section 4.1), run in the result transaction.
 * The step's {@code guard} spec travels in the job payload, written when the step parks:
 * <pre>
 * "guard": {
 *   "outputSchema": { JSON Schema subset },
 *   "decision": "agent-output-guardrails",
 *   "onViolation": "route-to-human" | "fail",
 *   "reviewer": "role:compliance-reviewer"
 * }
 * </pre>
 * A review task always follows the four-eyes rule. A spec that sets {@code fourEyes} to anything but {@code true} is
 * malformed ({@code GUARD_SPEC_INVALID}): the payload cannot switch four-eyes off.
 * <ol>
 *   <li>The output is checked against {@code outputSchema}; a violation is {@link Verdict.Invalid}, retried up to the
 *       runner's {@code schema-retries} and then refused ({@code AGENT_SCHEMA_INVALID}).</li>
 *   <li>The guardrail rule set {@code decision} is evaluated through the in-process {@link DecisionPort}, in the
 *       instance's tenant, as the engine. Its contract: output {@code allow: boolean}, optional
 *       {@code reasons: list<string>}. {@code allow == true} accepts the result.</li>
 *   <li>Everything else refuses it and fails closed: {@code allow == false} ({@code GUARDRAIL_VIOLATION}); no match,
 *       a decision error, a missing Rule Engine, an {@code allow} that is not a boolean, an exception, or a check that
 *       took longer than the runner's {@code guard-budget} ({@code GUARDRAIL_EVALUATION_ERROR}); a missing or
 *       malformed spec ({@code GUARD_SPEC_INVALID}). The budget is checked after the schema check, so a slow schema
 *       check never reaches the decision, and again after the decision. An in-process decision cannot be
 *       interrupted while it runs.</li>
 * </ol>
 * The history keeps a refused output masked ({@link #auditOutput}): a value is kept only where the output schema
 * declares the field and does not mark it {@code mask: true}; strings, lists, maps and nesting are bounded.
 * A refused step goes to a review task for {@code reviewer} when {@code onViolation} is {@code route-to-human} (the
 * default) and a reviewer is named; otherwise its job fails. The guard calls nothing outside the JVM.
 */
final class AgentResultGuard implements ResultGuard {

    /** Code of a refused result whose guardrail said {@code allow == false}. */
    static final String VIOLATION = "GUARDRAIL_VIOLATION";
    /** Code of a result refused because the guardrail could not be evaluated (fail closed). */
    static final String EVALUATION_ERROR = "GUARDRAIL_EVALUATION_ERROR";
    /** Code of a result refused because the step's guard spec is missing or malformed (fail closed). */
    static final String SPEC_INVALID = "GUARD_SPEC_INVALID";
    /** Code of a failed attempt whose output breaks the output schema. */
    static final String SCHEMA_INVALID = "AGENT_SCHEMA_INVALID";
    /** Most reasons kept from a guardrail decision. */
    static final int MAX_REASONS = 20;
    /** Longest reason kept, in characters. */
    static final int MAX_REASON_LENGTH = 200;
    /** Reason of a check that overran the runner's {@code guard-budget}. */
    static final String BUDGET_EXCEEDED = "GUARD_BUDGET_EXCEEDED";
    /** Longest string value of a refused output kept in its history entry, in characters. */
    static final int AUDIT_MAX_STRING = 200;
    /** Most items of a list, or entries of a nested object, of a refused output kept in its history entry. */
    static final int AUDIT_MAX_ITEMS = 20;
    /** Deepest nesting of a refused output kept in its history entry; anything deeper is masked. */
    static final int AUDIT_MAX_DEPTH = 4;

    private static final Pattern ASSIGNEE = Pattern.compile("^(role|user|group):[A-Za-z0-9_.@-]{1,200}$");
    private static final Pattern RULE_SET = Pattern.compile("^[a-z][a-z0-9]*([.-][a-z0-9]+)*$");

    private final OutputSchemaValidator schemas;
    private final Optional<DecisionPort> decisions;
    private final Clock clock;

    /**
     * @param schemas the output schema validator
     * @param decisions the in-process decision port, empty when the host has no Rule Engine
     * @param clock the engine clock, which also measures the budget
     */
    AgentResultGuard(OutputSchemaValidator schemas, Optional<DecisionPort> decisions, Clock clock) {
        this.schemas = Objects.requireNonNull(schemas, "schemas must not be null");
        this.decisions = Objects.requireNonNull(decisions, "decisions must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** The parsed spec; {@code route} and {@code reviewer} are always set, the rest is null when malformed. */
    private record Spec(Map<String, Object> outputSchema, String decision, Route route, String reviewer,
            String problem) {
    }

    @Override
    public Verdict check(Request request, Duration budget) {
        Instant started = clock.instant();
        Spec spec = spec(request.payload());
        if (spec.problem() != null) {
            return refused(spec, SPEC_INVALID, List.of(spec.problem()), List.of(), null);
        }
        Verdict verdict;
        try {
            verdict = evaluate(spec, request, started, budget);
        } catch (RuntimeException e) {
            // The class only: a message may carry output values.
            verdict = refused(spec, EVALUATION_ERROR, List.of(e.getClass().getSimpleName()), List.of(), null);
        }
        if (overran(started, budget) && !(verdict instanceof Verdict.Invalid)) {
            String decisionId = switch (verdict) {
                case Verdict.Allow a -> a.decisionId();
                case Verdict.Refused r -> r.decisionId();
                case Verdict.Invalid i -> null;
            };
            return refused(spec, EVALUATION_ERROR, List.of(BUDGET_EXCEEDED), List.of(), decisionId);
        }
        return verdict;
    }

    private boolean overran(Instant started, Duration budget) {
        return Duration.between(started, clock.instant()).compareTo(budget) > 0;
    }

    private Verdict evaluate(Spec spec, Request request, Instant started, Duration budget) {
        List<String> violations = schemas.validate(spec.outputSchema(), request.output());
        if (violations == null) {
            return refused(spec, EVALUATION_ERROR, List.of("SCHEMA_VALIDATOR_NULL"), List.of(), null);
        }
        if (!violations.isEmpty()) {
            return new Verdict.Invalid(SCHEMA_INVALID, violations, refused(spec, SCHEMA_INVALID, violations, List.of(),
                    null));
        }
        if (overran(started, budget)) {
            // The schema check used the budget up: the decision is not evaluated at all.
            return refused(spec, EVALUATION_ERROR, List.of(BUDGET_EXCEEDED), List.of(), null);
        }
        if (decisions.isEmpty()) {
            return refused(spec, EVALUATION_ERROR, List.of("RULE_ENGINE_MISSING"), List.of(), null);
        }
        DecisionOutcome outcome = decisions.get().evaluate(request.tenantId(),
                request.caller(), spec.decision(), request.output(),
                request.instanceId());
        return switch (outcome) {
            case DecisionOutcome.Matched m when Boolean.TRUE.equals(m.outputs().get("allow")) ->
                    new Verdict.Allow(m.decisionId(), m.ruleIds());
            case DecisionOutcome.Matched m when Boolean.FALSE.equals(m.outputs().get("allow")) ->
                    refused(spec, VIOLATION, reasons(m.outputs().get("reasons")), m.ruleIds(), m.decisionId());
            case DecisionOutcome.Matched m ->
                    refused(spec, EVALUATION_ERROR, List.of("GUARDRAIL_CONTRACT_INVALID"), m.ruleIds(), m.decisionId());
            case DecisionOutcome.NoMatch n ->
                    refused(spec, EVALUATION_ERROR, List.of("DECISION_NO_MATCH"), List.of(), n.decisionId());
            case DecisionOutcome.Failed f ->
                    refused(spec, EVALUATION_ERROR, List.of(f.code() == null ? "DECISION_FAILED" : f.code()), List.of(),
                            null);
        };
    }

    private static Verdict.Refused refused(Spec spec, String code, List<String> reasons, List<String> ruleIds,
            String decisionId) {
        return new Verdict.Refused(code, reasons, ruleIds, decisionId, spec.route(), spec.reviewer());
    }

    /**
     * Reads the spec. A malformed {@code onViolation} or {@code reviewer} sends the step to a failed job, never to a
     * guessed reviewer; a missing schema or decision still lets a well-named reviewer look at the result.
     */
    private static Spec spec(Map<String, Object> payload) {
        if (!(payload.get("guard") instanceof Map<?, ?> guard)) {
            return new Spec(null, null, Route.FAIL, null, "no guard spec");
        }
        Object onViolation = guard.get("onViolation");
        if (onViolation != null && !"fail".equals(onViolation) && !"route-to-human".equals(onViolation)) {
            return new Spec(null, null, Route.FAIL, null, "onViolation must be route-to-human or fail");
        }
        Object reviewerValue = guard.get("reviewer");
        if (reviewerValue != null && !(reviewerValue instanceof String r && ASSIGNEE.matcher(r).matches())) {
            return new Spec(null, null, Route.FAIL, null, "reviewer must be role:, user: or group: and a name");
        }
        String reviewer = "fail".equals(onViolation) ? null : (String) reviewerValue;
        Route route = reviewer == null ? Route.FAIL : Route.REVIEW;
        Object fourEyes = guard.get("fourEyes");
        if (fourEyes != null && !Boolean.TRUE.equals(fourEyes)) {
            // The review keeps four eyes; a spec asking for less is refused, and the reviewer still sees the result.
            return new Spec(null, null, route, reviewer, "fourEyes cannot be switched off");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = guard.get("outputSchema") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        if (schema == null) {
            return new Spec(null, null, route, reviewer, "no outputSchema");
        }
        if (!(guard.get("decision") instanceof String decision && RULE_SET.matcher(decision).matches())) {
            return new Spec(null, null, route, reviewer, "no guardrail decision");
        }
        return new Spec(schema, decision, route, reviewer, null);
    }

    /**
     * The step's output schema from the job payload, or null when the payload has none.
     *
     * @param payload the job payload
     * @return the schema
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> outputSchema(Map<String, Object> payload) {
        return payload != null && payload.get("guard") instanceof Map<?, ?> guard
                && guard.get("outputSchema") instanceof Map<?, ?> schema ? (Map<String, Object>) schema : null;
    }

    /**
     * Masks a refused output for its history entry. A value is kept only where the output schema declares the field
     * and does not mark it {@code mask: true}; an undeclared field, which the schema cannot classify, is masked.
     * Strings are cut at {@value #AUDIT_MAX_STRING} characters, lists and nested objects at {@value #AUDIT_MAX_ITEMS}
     * entries, and anything nested deeper than {@value #AUDIT_MAX_DEPTH} levels is masked.
     */
    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> auditOutput(Map<String, Object> payload, Map<String, Object> output) {
        Object masked = mask(outputSchema(payload), output == null ? Map.of() : output, 0, AUDIT_MAX_FIELDS);
        // No schema, or one that masks the whole output: field names only, every value masked.
        return masked instanceof Map<?, ?> m ? (Map<String, Object>) m : ResultGuard.super.auditOutput(payload, output);
    }

    private static Object mask(Object schema, Object value, int depth, int maxEntries) {
        if (!(schema instanceof Map<?, ?> node) || Boolean.TRUE.equals(node.get("mask")) || depth > AUDIT_MAX_DEPTH) {
            return MASKED;
        }
        return switch (value) {
            case Map<?, ?> map -> {
                Map<?, ?> properties = node.get("properties") instanceof Map<?, ?> p ? p : Map.of();
                Map<String, Object> out = new java.util.LinkedHashMap<>();
                map.entrySet().stream().limit(maxEntries).forEach(e -> out.put(String.valueOf(e.getKey()),
                        mask(properties.get(e.getKey()), e.getValue(), depth + 1, AUDIT_MAX_ITEMS)));
                yield out;
            }
            case Collection<?> items -> items.stream().limit(AUDIT_MAX_ITEMS)
                    .map(item -> mask(node.get("items"), item, depth + 1, AUDIT_MAX_ITEMS)).toList();
            case String text -> text.length() > AUDIT_MAX_STRING ? text.substring(0, AUDIT_MAX_STRING) : text;
            case null -> null;
            default -> value;
        };
    }

    private static List<String> reasons(Object value) {
        List<String> out = new ArrayList<>();
        Collection<?> items = value instanceof Collection<?> c ? c : value == null ? List.of() : List.of(value);
        for (Object item : items) {
            if (out.size() >= MAX_REASONS) {
                break;
            }
            String text = String.valueOf(item);
            out.add(text.length() > MAX_REASON_LENGTH ? text.substring(0, MAX_REASON_LENGTH) : text);
        }
        return out;
    }
}
