package io.github.dhluong90.bpm.rules.test;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.internal.eval.CompiledRuleSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A rule set loaded for a test (RLE-059): evaluates in memory with the engine's own evaluator at the test's fixed
 * clock, and tells the coverage report which rows a test matched. An undeclared or ill-typed input is
 * {@code INPUT_INVALID}, as in the engine. Nothing is written anywhere.
 */
public final class RuleSetUnderTest {

    /** The one tenant of a test; there is no way to name another. */
    public static final String TENANT = "test";

    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final LoadedRuleSet loaded;
    private final CompiledRuleSet rules;
    private final Instant clock;
    private final String testId;
    private final CoverageRun coverage;

    RuleSetUnderTest(LoadedRuleSet loaded, Instant clock, String testId, CoverageRun coverage) {
        this.loaded = Objects.requireNonNull(loaded, "loaded must not be null");
        this.rules = loaded.rules();
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.testId = Objects.requireNonNull(testId, "testId must not be null");
        this.coverage = Objects.requireNonNull(coverage, "coverage must not be null");
    }

    /**
     * Evaluates the rule set at this test's clock.
     *
     * @param input the inputs by name; a value may be null
     * @return the result the engine gives for version 1 of this file; a failed evaluation is a result with a
     *     {@code Failed} outcome, not an exception
     * @throws io.github.dhluong90.bpm.rules.api.InputInvalidException ({@code INPUT_INVALID}) naming an undeclared
     *     input, or an input of the wrong type
     */
    public DecisionResult evaluate(Map<String, ?> input) {
        return evaluate(input, clock);
    }

    /**
     * Evaluates the rule set at a given clock, for one call.
     *
     * @param input the inputs by name; a value may be null
     * @param at the instant {@code today()} and {@code now()} read
     * @return the result
     * @throws io.github.dhluong90.bpm.rules.api.InputInvalidException ({@code INPUT_INVALID}) naming an undeclared
     *     input, or an input of the wrong type
     */
    public DecisionResult evaluate(Map<String, ?> input, Instant at) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(at, "at must not be null");
        DecisionInput decisionInput = DecisionInput.of(input);
        rules.requireOnlyDeclared(decisionInput);
        DecisionResult.Outcome outcome = rules.evaluate(decisionInput, at);
        // A row is covered only by an evaluation that matched it: a failure produced no outputs and covers nothing.
        if (outcome instanceof DecisionResult.Matched matched) {
            coverage.matched(loaded, testId, matched.ruleIds());
        }
        return new DecisionResult("test-" + SEQUENCE.incrementAndGet(), rules.name(), 1, at, at, false, outcome);
    }

    /** @return the rule set name */
    public String name() {
        return rules.name();
    }

    /** @return the canonical hash of the loaded version, the one the engine would store */
    public String canonicalHash() {
        return loaded.hash();
    }

    /** @return the rule ids in document order */
    public List<String> ruleIds() {
        return loaded.ruleIds();
    }

    /** @return the clock of this test */
    public Instant clock() {
        return clock;
    }
}
