package io.github.dhluong90.bpm.rules.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * How two versions of a rule set would have decided the logged decisions of a past period (RLE-075, RLE-076): counts,
 * totals side by side and the most common changes. Aggregates only: no input, decision id or fingerprint, so a result
 * can go into a committee pack without customer data. Nothing of it is written anywhere.
 *
 * @param ruleSet the rule set name
 * @param base the base version number
 * @param candidate the candidate version number
 * @param from the first instant of the period, inclusive
 * @param to the end of the period, exclusive
 * @param baseCanonicalHash the {@code canonicalHash} of the base version
 * @param candidateCanonicalHash the {@code canonicalHash} of the candidate version
 * @param decisionsCompared how many logged decisions were evaluated through both versions; the totals cover these
 *     only
 * @param changedCount how many of them changed: the outcome kind, an output value or the failure code differs
 * @param baseOutcomes the base outcome kinds ({@code MATCHED}, {@code NO_MATCH}, {@code FAILED}) with their counts
 * @param candidateOutcomes the candidate outcome kinds with their counts
 * @param totals the sums of the numeric and money outputs, by output and currency
 * @param transitions the most common output changes, at most {@value #MAX_TRANSITIONS}, by count, then by value
 * @param skipped the decisions that could not be evaluated, by reason ({@code MASKED_INPUT}, {@code MISSING_INPUT});
 *     a reason with no decision is absent
 */
public record ComparisonResult(String ruleSet, int base, int candidate, Instant from, Instant to,
        String baseCanonicalHash, String candidateCanonicalHash, long decisionsCompared, long changedCount,
        Map<String, Long> baseOutcomes, Map<String, Long> candidateOutcomes, List<Total> totals,
        List<Transition> transitions, Map<String, Long> skipped) {

    /** The most transitions a result lists. */
    public static final int MAX_TRANSITIONS = 20;

    /** The reason of a decision skipped because a masked input a version reads has only a fingerprint in the log. */
    public static final String MASKED_INPUT = "MASKED_INPUT";

    /** The reason of a decision skipped because a version reads an input the logged decision does not hold. */
    public static final String MISSING_INPUT = "MISSING_INPUT";

    public ComparisonResult {
        Objects.requireNonNull(ruleSet, "ruleSet must not be null");
        baseOutcomes = Map.copyOf(baseOutcomes);
        candidateOutcomes = Map.copyOf(candidateOutcomes);
        totals = List.copyOf(totals);
        transitions = List.copyOf(transitions);
        skipped = Map.copyOf(skipped);
    }

    /**
     * The sum of one output over the compared decisions, per side.
     *
     * @param output the output name
     * @param currency the ISO 4217 code of a money output, or null for a number
     * @param base the sum for the base version: minor units for money, the exact value for a number
     * @param candidate the sum for the candidate version
     * @param difference {@code candidate - base}
     */
    public record Total(String output, String currency, BigDecimal base, BigDecimal candidate, BigDecimal difference) {
    }

    /**
     * One output change and how many decisions had it.
     *
     * @param output the output name
     * @param from the value under the base version, null when the output was absent
     * @param to the value under the candidate version, null when the output is absent
     * @param count the decisions with exactly this change
     */
    public record Transition(String output, Object from, Object to, long count) {
    }
}
