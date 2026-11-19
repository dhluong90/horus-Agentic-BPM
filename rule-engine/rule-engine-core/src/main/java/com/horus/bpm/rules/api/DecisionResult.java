package com.horus.bpm.rules.api;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The result of one logged evaluation.
 *
 * @param decisionId id of the decision row in {@code rle_decision}
 * @param ruleSet the rule set name
 * @param version the version number that was evaluated
 * @param evaluatedAt the evaluation clock instant
 * @param outcome what happened; switch over it exhaustively
 */
public record DecisionResult(String decisionId, String ruleSet, int version, Instant evaluatedAt, Outcome outcome) {

    public DecisionResult {
        Objects.requireNonNull(decisionId, "decisionId must not be null");
        Objects.requireNonNull(ruleSet, "ruleSet must not be null");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
    }

    /**
     * Returns the ids of the matched rules, in rule order; empty unless the outcome is {@link Matched}.
     *
     * @return the matched rule ids
     */
    public List<String> matchedRuleIds() {
        return outcome instanceof Matched matched ? matched.ruleIds() : List.of();
    }

    /**
     * Returns the outputs; empty unless the outcome is {@link Matched}.
     *
     * @return the outputs by output name
     */
    public Map<String, Object> outputs() {
        return outcome instanceof Matched matched ? matched.outputs() : Map.of();
    }

    /**
     * Tells whether the evaluation failed.
     *
     * @return true for {@link Failed}
     */
    public boolean isError() {
        return outcome instanceof Failed;
    }

    /** Outcome of an evaluation. */
    public sealed interface Outcome permits Matched, NoMatch, Failed {
    }

    /**
     * At least one rule matched.
     *
     * @param ruleIds matched rule ids in rule order
     * @param outputs output values by name; {@code Money}, {@code BigDecimal}, {@code String}, {@code Boolean} or null
     */
    public record Matched(List<String> ruleIds, Map<String, Object> outputs) implements Outcome {
        public Matched {
            ruleIds = List.copyOf(ruleIds);
            outputs = Collections.unmodifiableMap(new LinkedHashMap<>(outputs));
        }
    }

    /** No rule matched. */
    public record NoMatch() implements Outcome {
    }

    /**
     * The evaluation failed and was logged with this code; no partial outputs.
     *
     * @param code {@code EVALUATION_ERROR} or {@code UNIQUE_HIT_VIOLATION}
     * @param detail a detail without input values
     */
    public record Failed(String code, String detail) implements Outcome {
        public Failed {
            Objects.requireNonNull(code, "code must not be null");
            Objects.requireNonNull(detail, "detail must not be null");
        }
    }
}
