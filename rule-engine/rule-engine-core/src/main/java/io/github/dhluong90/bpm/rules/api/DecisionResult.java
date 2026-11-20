package io.github.dhluong90.bpm.rules.api;

import io.github.dhluong90.bpm.expr.api.Money;
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
 * @param clock the instant the decision reasoned about: it selected the version and fed {@code today()} and
 *     {@code now()}; logged, so a replay uses it again (TR-DET-05)
 * @param evaluatedAt when the evaluation ran; equals {@code clock} unless the caller chose a clock
 * @param pinned true when the caller named the version ({@code rules:pin}) instead of letting the clock select the
 *     version in force; set by the engine and stored on the decision row, never taken from the request body
 * @param outcome what happened; switch over it exhaustively
 */
public record DecisionResult(String decisionId, String ruleSet, int version, Instant clock, Instant evaluatedAt,
        boolean pinned, Outcome outcome) {

    public DecisionResult {
        Objects.requireNonNull(decisionId, "decisionId must not be null");
        Objects.requireNonNull(ruleSet, "ruleSet must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
    }

    /**
     * Creates a result of the version in force, not pinned.
     *
     * @param decisionId the decision id
     * @param ruleSet the rule set name
     * @param version the version number
     * @param clock the instant the decision reasoned about
     * @param evaluatedAt when it ran
     * @param outcome the outcome
     */
    public DecisionResult(String decisionId, String ruleSet, int version, Instant clock, Instant evaluatedAt,
            Outcome outcome) {
        this(decisionId, ruleSet, version, clock, evaluatedAt, false, outcome);
    }

    /**
     * Creates a result whose clock is the instant it ran at.
     *
     * @param decisionId the decision id
     * @param ruleSet the rule set name
     * @param version the version number
     * @param evaluatedAt the clock and run instant
     * @param outcome the outcome
     */
    public DecisionResult(String decisionId, String ruleSet, int version, Instant evaluatedAt, Outcome outcome) {
        this(decisionId, ruleSet, version, evaluatedAt, evaluatedAt, false, outcome);
    }

    /**
     * Returns the ids of the matched rules, in rule order: those of {@link Matched}, the overlapping rules of a
     * {@code UNIQUE_HIT_VIOLATION}, and empty otherwise.
     *
     * @return the matched rule ids
     */
    public List<String> matchedRuleIds() {
        return switch (outcome) {
            case Matched matched -> matched.ruleIds();
            case Failed failed -> failed.matchedRuleIds();
            case NoMatch ignored -> List.of();
        };
    }

    /**
     * Returns the outputs: those of {@link Matched}; for {@link NoMatch}, every declared output with the value null;
     * empty for {@link Failed}.
     *
     * @return the outputs by output name
     */
    public Map<String, Object> outputs() {
        return switch (outcome) {
            case Matched matched -> matched.outputs();
            case NoMatch noMatch -> noMatch.outputs();
            case Failed ignored -> Map.of();
        };
    }

    /**
     * Tells whether the decision reasoned about another instant than the one it ran at: the caller chose the clock
     * ({@code rules:simulate}). The decision row records it the same way, as an {@code eval_clock} different from
     * {@code created_at}, so a back-dated decision is visible in the log.
     *
     * @return true when {@code clock} differs from {@code evaluatedAt}
     */
    public boolean clockChosen() {
        return !clock.equals(evaluatedAt);
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

    /**
     * No rule matched.
     *
     * @param outputs every declared output with the value null (DMN 1.5: a decision table with no match gives null)
     */
    public record NoMatch(Map<String, Object> outputs) implements Outcome {
        public NoMatch {
            outputs = Collections.unmodifiableMap(new LinkedHashMap<>(outputs));
        }

        /** No rule matched and there are no outputs. */
        public NoMatch() {
            this(Map.of());
        }
    }

    /**
     * The evaluation failed and was logged with this code; no partial outputs.
     *
     * @param code {@code EVALUATION_ERROR} or {@code UNIQUE_HIT_VIOLATION}
     * @param detail a detail without input values
     * @param matchedRuleIds for {@code UNIQUE_HIT_VIOLATION} every matched rule (two or more); for an
     *     {@code EVALUATION_ERROR} from a null under a {@code collect} aggregate, the matched rules; otherwise empty
     */
    public record Failed(String code, String detail, List<String> matchedRuleIds) implements Outcome {
        public Failed {
            Objects.requireNonNull(code, "code must not be null");
            Objects.requireNonNull(detail, "detail must not be null");
            matchedRuleIds = List.copyOf(matchedRuleIds);
        }

        /**
         * Creates a failure without matched rules.
         *
         * @param code the error code
         * @param detail the detail
         */
        public Failed(String code, String detail) {
            this(code, detail, List.of());
        }
    }
}
