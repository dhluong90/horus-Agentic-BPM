package io.github.dhluong90.bpm.process.internal.service;

import io.github.dhluong90.bpm.process.api.AgentReplayCase;
import io.github.dhluong90.bpm.process.internal.store.AgentReplayStore.RecordedAnswer;
import io.github.dhluong90.bpm.process.spi.OutputSchemaValidator;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The check of one recorded answer against a candidate (PRE-092, DESIGN section 4.1): the result check of the result
 * transaction, {@link AgentResultGuard}, run again with the candidate rule set version (and the candidate output
 * schema, when given) in place of the recorded ones, at the answer's recorded time, through a decision port that
 * simulates and writes nothing. Calls no adapter and nothing outside the JVM.
 *
 * <p>The recorded answer is the masked copy history keeps. A top-level field whose recorded value is the mask
 * ({@code ***}) was never kept in plain text: it is left out of the check, out of the schema's {@code required} list,
 * and counted on the line. A rule that reads such a field sees no value, and neither does a schema keyword that
 * tests it. So a line whose candidate guardrail reads a masked field, or whose candidate schema newly constrains its
 * value, is {@code NOT_REPLAYED} with {@code MASKED_INPUT_REQUIRED} (PRE-092): only a check that never touches a
 * masked field may report {@code UNCHANGED} or a change.
 */
final class AgentReplayCheck {

    static final String ALLOWED = "ALLOWED";
    static final String REVIEW = "REVIEW";
    static final String REFUSED = "REFUSED";
    static final String NOT_REPLAYED = "NOT_REPLAYED";
    static final String UNCHANGED = "UNCHANGED";
    static final String NEWLY_ALLOWED = "NEWLY_ALLOWED";
    static final String NEWLY_REFUSED = "NEWLY_REFUSED";
    static final String NEWLY_ROUTED_TO_REVIEW = "NEWLY_ROUTED_TO_REVIEW";
    /** Why a line is {@code NOT_REPLAYED}: the candidate check needs the value of a field history keeps only masked. */
    static final String MASKED_INPUT_REQUIRED = "MASKED_INPUT_REQUIRED";
    /**
     * Schema keywords that say nothing about a value: a masked field with only these is checked by presence, which the
     * recorded answer shows. Any other keyword (an enum, a pattern, a bound, a format) tests the value.
     */
    private static final java.util.Set<String> ANNOTATIONS = java.util.Set.of("type", "mask", "description", "title",
            "examples", "default", "deprecated", "readOnly", "writeOnly", "$comment");
    /**
     * Who the replay's simulated decisions are attributed to; the simulating port ignores it and nothing is logged.
     * Not engine work: a replay is requested by a person and never reaches the engine-scoped evaluation.
     */
    static final String REPLAY_ACTOR = "system:agent-replay";
    /** The result check of a replay has no time budget: it runs on a background worker, never under a row lock. */
    private static final Duration NO_BUDGET = Duration.ofDays(1);

    private final OutputSchemaValidator schemas;
    private final DecisionPort decisions;
    private final String candidateRuleSet;
    private final int candidateVersion;
    private final Map<String, Object> candidateSchema;
    private DecisionSignature candidateSignature;

    /**
     * @param schemas the output schema validator of the result transaction
     * @param decisions the host's decision port; only its simulation is called
     * @param candidateRuleSet the candidate rule set
     * @param candidateVersion the candidate version
     * @param candidateSchema the candidate output schema, or null for each answer's recorded one
     */
    AgentReplayCheck(OutputSchemaValidator schemas, DecisionPort decisions, String candidateRuleSet,
            int candidateVersion, Map<String, Object> candidateSchema) {
        this.schemas = schemas;
        this.decisions = decisions;
        this.candidateRuleSet = candidateRuleSet;
        this.candidateVersion = candidateVersion;
        this.candidateSchema = candidateSchema;
    }

    /**
     * @param tenant the run's tenant
     * @param answer the recorded answer
     * @param overridden whether a reviewer changed it
     * @return the report line
     */
    AgentReplayCase check(String tenant, RecordedAnswer answer, boolean overridden) {
        Map<String, Object> details = answer.details();
        String recorded = recordedOutcome(answer.eventType(), details);
        List<String> recordedRules = strings(details.get("ruleIds"));
        Map<String, Object> guard = answer.jobPayload() != null && answer.jobPayload().get("guard") instanceof Map<?, ?> g
                ? copy(g) : null;
        if (guard == null) {
            return new AgentReplayCase(answer.attemptId(), answer.instanceId(), answer.occurredAt(), recorded,
                    NOT_REPLAYED, NOT_REPLAYED, recordedRules, List.of(), AgentResultGuard.SPEC_INVALID, overridden, 0);
        }
        Map<String, Object> schema = candidateSchema != null ? copy(candidateSchema)
                : guard.get("outputSchema") instanceof Map<?, ?> s ? copy(s) : null;
        Map<String, Object> output = details.get("output") instanceof Map<?, ?> o ? copy(o) : new LinkedHashMap<>();
        List<String> maskedNames = maskedFields(output);
        List<String> needed = needed(tenant, maskedNames, guard.get("outputSchema") instanceof Map<?, ?> r ? copy(r) : null);
        if (!needed.isEmpty()) {
            return new AgentReplayCase(answer.attemptId(), answer.instanceId(), answer.occurredAt(), recorded,
                    NOT_REPLAYED, NOT_REPLAYED, recordedRules, List.of(), MASKED_INPUT_REQUIRED, overridden,
                    maskedNames.size());
        }
        int masked = dropMasked(output, schema, maskedNames);
        if (schema != null) {
            guard.put("outputSchema", schema);
        }
        // The candidate in place of the recorded guardrail; a recorded spec without one stays malformed.
        if (guard.get("decision") instanceof String) {
            guard.put("decision", candidateRuleSet);
        }
        Map<String, Object> payload = new LinkedHashMap<>(answer.jobPayload());
        payload.put("guard", guard);
        DecisionPort simulating = (tenantId, caller, ref, inputs, at, key, correlation) -> decisions.simulate(tenantId,
                ref, candidateVersion, List.of(new DecisionPort.SimulatedCase(inputs, at))).get(0);
        // A fixed clock at the recorded time: today() is the day the answer was checked (PRE-093), and no budget runs.
        AgentResultGuard guardCheck = new AgentResultGuard(schemas, Optional.of(simulating),
                Clock.fixed(answer.occurredAt(), ZoneOffset.UTC));
        ResultGuard.Verdict verdict = guardCheck.check(new ResultGuard.Request(tenant, answer.instanceId(),
                "replay", answer.attemptId(), "ASYNC_AGENT", payload, output, new DecisionCaller.Request(tenant, REPLAY_ACTOR)),
                NO_BUDGET);
        if (verdict instanceof ResultGuard.Verdict.Invalid invalid) {
            // Schema retries are generations of the model; a replay has only the recorded answer.
            verdict = invalid.exhausted();
        }
        String candidate;
        String code;
        List<String> candidateRules;
        switch (verdict) {
            case ResultGuard.Verdict.Allow allow -> {
                candidate = ALLOWED;
                code = null;
                candidateRules = allow.ruleIds();
            }
            case ResultGuard.Verdict.Refused refused -> {
                candidate = refused.route() == ResultGuard.Route.REVIEW ? REVIEW : REFUSED;
                code = refused.code();
                candidateRules = refused.ruleIds();
            }
            case ResultGuard.Verdict.Invalid invalid -> throw new IllegalStateException("unreachable");
        }
        if (AgentResultGuard.SPEC_INVALID.equals(code)) {
            return new AgentReplayCase(answer.attemptId(), answer.instanceId(), answer.occurredAt(), recorded,
                    NOT_REPLAYED, NOT_REPLAYED, recordedRules, List.of(), code, overridden, masked);
        }
        return new AgentReplayCase(answer.attemptId(), answer.instanceId(), answer.occurredAt(), recorded, candidate,
                change(recorded, candidate), recordedRules, candidateRules, code, overridden, masked);
    }

    static String recordedOutcome(String eventType, Map<String, Object> details) {
        if ("ASYNC_RESULT_ACCEPTED".equals(eventType)) {
            return ALLOWED;
        }
        return "review".equals(details.get("route")) ? REVIEW : REFUSED;
    }

    static String change(String recorded, String candidate) {
        if (recorded.equals(candidate)) {
            return UNCHANGED;
        }
        return switch (candidate) {
            case ALLOWED -> NEWLY_ALLOWED;
            case REVIEW -> NEWLY_ROUTED_TO_REVIEW;
            default -> NEWLY_REFUSED;
        };
    }

    /** The top-level fields recorded as the mask. */
    private static List<String> maskedFields(Map<String, Object> output) {
        List<String> masked = new ArrayList<>();
        output.forEach((field, value) -> {
            if (ResultGuard.MASKED.equals(value)) {
                masked.add(field);
            }
        });
        return masked;
    }

    /**
     * The masked fields the candidate check would read: read by a rule of the candidate rule set version, or
     * constrained in value by a candidate schema that differs from the recorded one. A rule set that cannot be
     * described reads none here; its evaluation reports its own error.
     */
    private List<String> needed(String tenant, List<String> masked, Map<String, Object> recorded) {
        if (masked.isEmpty()) {
            return List.of();
        }
        if (candidateSignature == null) {
            candidateSignature = decisions.signature(tenant, candidateRuleSet, candidateVersion);
        }
        List<String> out = new ArrayList<>();
        for (String field : masked) {
            boolean byRule = candidateSignature instanceof DecisionSignature.Found found
                    && found.readInputs().contains(field);
            if (byRule || constrains(recorded, field)) {
                out.add(field);
            }
        }
        return out;
    }

    /**
     * Whether the candidate schema tests a masked field's value in a way the recorded schema did not. A constraint both
     * schemas share (a {@code maxLength} on a masked summary) was met by the recorded answer, which is how its outcome
     * was reached, so it is no new input; one that is new or changed has never been checked against the value.
     */
    private boolean constrains(Map<String, Object> recorded, String field) {
        if (candidateSchema == null) {
            return false;
        }
        Map<String, Object> now = valueKeywords(candidateSchema, field);
        return !now.isEmpty() && !now.equals(valueKeywords(recorded, field));
    }

    /** The keywords of a property that test its value: everything but the annotations. */
    private static Map<String, Object> valueKeywords(Map<String, Object> schema, String field) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (schema != null && schema.get("properties") instanceof Map<?, ?> properties
                && properties.get(field) instanceof Map<?, ?> definition) {
            definition.forEach((k, v) -> {
                if (!ANNOTATIONS.contains(String.valueOf(k))) {
                    out.put(String.valueOf(k), v);
                }
            });
        }
        return out;
    }

    /** Leaves out the masked fields and their {@code required} entries; returns how many. */
    private static int dropMasked(Map<String, Object> output, Map<String, Object> schema, List<String> masked) {
        masked.forEach(output::remove);
        if (schema != null && !masked.isEmpty() && schema.get("required") instanceof Collection<?> required) {
            List<Object> kept = new ArrayList<>(required);
            kept.removeAll(masked);
            schema.put("required", kept);
        }
        return masked.size();
    }

    private static Map<String, Object> copy(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof Collection<?> items)) {
            return List.of();
        }
        return items.stream().map(String::valueOf).toList();
    }
}
