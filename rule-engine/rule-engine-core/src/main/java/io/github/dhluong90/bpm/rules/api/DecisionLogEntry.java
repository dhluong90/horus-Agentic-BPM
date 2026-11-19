package io.github.dhluong90.bpm.rules.api;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One logged decision, as stored in the append-only decision log.
 *
 * <p>Inputs are those the version declared, as logged: an input declared {@code mask: true} is the stored hash,
 * never the value the caller sent, because the plain value is not kept. Numbers are {@code Long},
 * {@code BigInteger} or {@code BigDecimal}, money is {@link Money}, and lists are lists.
 *
 * @param decisionId the decision id (ULID)
 * @param ruleSet the rule set name
 * @param version the version number that was evaluated
 * @param hitPolicy the hit policy of that version, for example {@code FIRST}
 * @param outcome what happened
 * @param matchedRuleIds the matched rules in rule order; for {@code UNIQUE_HIT_VIOLATION} every overlapping rule
 * @param inputs the logged inputs by name; values may be null
 * @param outputs the outputs by name; empty for a failed evaluation; values may be null
 * @param callerId the caller, or null
 * @param correlationId the correlation id, or null
 * @param evaluatedAt the evaluation clock instant
 * @param loggedAt the time of the log row, which places it in its monthly partition
 * @param durationMicros evaluation time in microseconds
 */
public record DecisionLogEntry(
        String decisionId, String ruleSet, int version, String hitPolicy, Outcome outcome, List<String> matchedRuleIds,
        Map<String, Object> inputs, Map<String, Object> outputs, String callerId, String correlationId,
        Instant evaluatedAt, Instant loggedAt, long durationMicros) {

    public DecisionLogEntry {
        Objects.requireNonNull(decisionId, "decisionId must not be null");
        Objects.requireNonNull(ruleSet, "ruleSet must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");
        Objects.requireNonNull(loggedAt, "loggedAt must not be null");
        matchedRuleIds = List.copyOf(matchedRuleIds);
        inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        outputs = Collections.unmodifiableMap(new LinkedHashMap<>(outputs));
    }

    /** What a logged decision ended with. */
    public enum Outcome {
        /** At least one rule matched. */
        MATCHED,
        /** No rule matched; every declared output is null. */
        NO_MATCH,
        /** A {@code unique} table matched several rules; no outputs. */
        UNIQUE_HIT_VIOLATION,
        /** The evaluation failed; no outputs. */
        EVALUATION_ERROR
    }
}
