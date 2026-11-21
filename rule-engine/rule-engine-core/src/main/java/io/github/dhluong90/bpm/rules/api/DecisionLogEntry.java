package io.github.dhluong90.bpm.rules.api;

import io.github.dhluong90.bpm.expr.api.Money;
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
 * @param evalClock the instant the decision reasoned about ({@code eval_clock}): the request's {@code clock} or the
 *     host clock; the version in force and every date input are read at it
 * @param evaluatedAt when the decision ran ({@code created_at}), as in {@link LoggedDecision}
 * @param loggedAt the time of the log row, which places it in its monthly partition; equal to {@code evaluatedAt}
 * @param durationMicros evaluation time in microseconds
 * @param pinned true when the caller named the version ({@code rules:pin}) instead of the engine selecting the
 *     version in force; set by the engine on the row, never read from a request
 * @param onBehalfOf the human an embedded engine evaluated for, as in {@link LoggedDecision}, or null
 */
public record DecisionLogEntry(
        String decisionId, String ruleSet, int version, String hitPolicy, Outcome outcome, List<String> matchedRuleIds,
        Map<String, Object> inputs, Map<String, Object> outputs, String callerId, String correlationId,
        Instant evalClock, Instant evaluatedAt, Instant loggedAt, long durationMicros, boolean pinned,
        String onBehalfOf) {

    /**
     * A decision made for its caller alone, with no {@code onBehalfOf}.
     *
     * @param decisionId see the record
     * @param ruleSet see the record
     * @param version see the record
     * @param hitPolicy see the record
     * @param outcome see the record
     * @param matchedRuleIds see the record
     * @param inputs see the record
     * @param outputs see the record
     * @param callerId see the record
     * @param correlationId see the record
     * @param evalClock see the record
     * @param evaluatedAt see the record
     * @param loggedAt see the record
     * @param durationMicros see the record
     * @param pinned see the record
     */
    public DecisionLogEntry(String decisionId, String ruleSet, int version, String hitPolicy, Outcome outcome,
            List<String> matchedRuleIds, Map<String, Object> inputs, Map<String, Object> outputs, String callerId,
            String correlationId, Instant evalClock, Instant evaluatedAt, Instant loggedAt, long durationMicros,
            boolean pinned) {
        this(decisionId, ruleSet, version, hitPolicy, outcome, matchedRuleIds, inputs, outputs, callerId,
                correlationId, evalClock, evaluatedAt, loggedAt, durationMicros, pinned, null);
    }

    public DecisionLogEntry {
        Objects.requireNonNull(decisionId, "decisionId must not be null");
        Objects.requireNonNull(ruleSet, "ruleSet must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(evalClock, "evalClock must not be null");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");
        Objects.requireNonNull(loggedAt, "loggedAt must not be null");
        matchedRuleIds = List.copyOf(matchedRuleIds);
        inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        outputs = Collections.unmodifiableMap(new LinkedHashMap<>(outputs));
    }

    /**
     * Tells whether the caller chose the clock, so the decision is back-dated or forward-dated, as
     * {@link LoggedDecision#clockChosen()}.
     *
     * @return true when {@code evalClock} differs from {@code evaluatedAt}
     */
    public boolean clockChosen() {
        return !evalClock.equals(evaluatedAt);
    }

    /** What a logged decision ended with. New values may be added; do not switch on it exhaustively. */
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
