package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.SourceFormat;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.eval.CompiledRuleSet;
import io.github.dhluong90.bpm.rules.internal.expr.RegexLimits;
import io.github.dhluong90.bpm.rules.internal.read.RuleSetReader;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Regular expression matches are bounded in input length and time (RLE-007, TR-SEC-02), through the public services
 * and the compiled rule set. A payment reference check is the example: a reference is a letter code and digits.
 */
class RegexBoundsTest {

    static final String ADVERSARIAL = "a".repeat(28) + "!";
    /**
     * The pattern of RLE-007. Since Java 9 the JDK memoises greedy group loops, so on Java 21 and 25 it fails on the
     * adversarial input in linear time; it is kept to prove the decision returns at once.
     */
    static final String SPEC_PATTERN = "(a+)+$";
    /** The same nesting with lazy quantifiers, which the JDK still runs in exponential time (seconds on this input). */
    static final String EXPONENTIAL_PATTERN = "^(a+?)+?$";
    /** Time on top of the 50 ms limit for thread scheduling on a busy CI runner; the limit itself is not relaxed. */
    static final long SCHEDULING_MARGIN_MS = 150;

    /** A reference check with the catastrophic pattern of RLE-007 and a normal one. */
    static final String REFERENCE_CHECK = """
            schemaVersion: 1
            ruleSet: reference-check
            hitPolicy: first
            inputs:
              - { name: reference, type: string }
            outputs:
              - { name: route, type: string }
            rules:
              - { id: R1, when: { reference: "matches('^(a+?)+?$')" }, then: { route: "'REVIEW'" } }
              - { id: R2, when: { reference: "matches('^FT[0-9]{8}$')" }, then: { route: "'AUTO'" } }
              - { id: R3, when: {}, then: { route: "'MANUAL'" } }
            """;

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "alice");

    RuleEngines engines(EngineSettings settings) {
        return RuleEngines.create(new EngineContext(caller, caller,
                Clock.fixed(Instant.parse("2027-01-15T02:00:00Z"), ZoneOffset.UTC), stores, stores, settings),
                stores, stores, stores);
    }

    void publish(RuleEngines engines, String yaml, String name) {
        caller.become("bank-a", "alice");
        engines.versionService().importRuleSet(ImportRequest.yaml(yaml.getBytes(StandardCharsets.UTF_8)));
        engines.versionService().requestPublish(name, 1, WriteOptions.none());
        caller.become("bank-a", "bob");
        engines.versionService().approve(name, 1, WriteOptions.none());
        caller.become("bank-a", "teller", Set.of("rules:evaluate"));
    }

    static CompiledRuleSet compiled(String yaml, RegexLimits limits) {
        return CompiledRuleSet.compile(RuleSetReader.read(SourceFormat.YAML, yaml.getBytes(StandardCharsets.UTF_8))
                .model(), io.github.dhluong90.bpm.rules.internal.service.EngineDefaults.OUTPUT_MAX_BYTES,
                io.github.dhluong90.bpm.rules.internal.service.EngineDefaults.STRING_MAX_LENGTH, limits);
    }

    @Test
    void RLE_007_regex_timeout_aborts_adversarial_input() {
        RuleEngines engines = engines(EngineSettings.defaults().withRegexLimits(50, 4096));
        publish(engines, REFERENCE_CHECK, "reference-check");

        long started = System.nanoTime();
        DecisionResult result = engines.ruleEngine().evaluate("reference-check",
                DecisionInput.of(Map.of("reference", ADVERSARIAL)));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertThat(elapsedMs).isLessThanOrEqualTo(50 + SCHEDULING_MARGIN_MS);
        assertThat(result.outcome()).isInstanceOfSatisfying(DecisionResult.Failed.class, failed -> {
            assertThat(failed.code()).isEqualTo("EVALUATION_ERROR");
            // The deterministic read budget stops it first; the 50 ms clock is the backstop.
            assertThat(failed.detail()).startsWith("REGEX_TIMEOUT").contains("evaluation.regex-max-reads")
                    .doesNotContain(ADVERSARIAL);
        });
        assertThat(stores.decisions).singleElement().satisfies(row -> {
            assertThat(row.outcome()).isEqualTo("EVALUATION_ERROR");
            assertThat(row.errorCode()).isEqualTo("REGEX_TIMEOUT");
            assertThat(row.outputsJson()).isNull();
        });
        // The thread is free: the same thread evaluates a normal reference straight after.
        assertThat(engines.ruleEngine().evaluate("reference-check", DecisionInput.of(Map.of("reference", "FT20270115")))
                .outputs()).containsEntry("route", "AUTO");

        // The pattern as RLE-007 writes it returns within the bound too; the JDK never lets it run away.
        publish(engines, REFERENCE_CHECK.replace("reference-check", "reference-check-greedy")
                .replace(EXPONENTIAL_PATTERN, SPEC_PATTERN), "reference-check-greedy");
        started = System.nanoTime();
        DecisionResult greedy = engines.ruleEngine().evaluate("reference-check-greedy",
                DecisionInput.of(Map.of("reference", ADVERSARIAL)));
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThanOrEqualTo(50 + SCHEDULING_MARGIN_MS);
        assertThat(greedy.outputs()).containsEntry("route", "MANUAL");
    }


    @Test
    void RLE_007_the_read_budget_stops_the_same_way_on_every_run_whatever_the_clock() {
        // A generous clock leaves only the deterministic budget: the outcome and detail are the same every time.
        CompiledRuleSet rules = compiled(REFERENCE_CHECK, new RegexLimits(1000, 4096, 200_000));
        List<String> details = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            CompiledRuleSet.Evaluation evaluation = rules.evaluateWithReason(
                    DecisionInput.of(Map.of("reference", ADVERSARIAL)));
            assertThat(evaluation.reason()).isEqualTo("REGEX_TIMEOUT");
            details.add(((DecisionResult.Failed) evaluation.outcome()).detail());
        }
        assertThat(details).containsOnly("REGEX_TIMEOUT: matches() stopped after reading 200000 characters"
                + " (evaluation.regex-max-reads); simplify the pattern");
    }

    @Test
    void TR_SEC_02_text_longer_than_the_limit_fails_without_running_the_match() {
        // A budget of one read: had the match run, it would have stopped with REGEX_TIMEOUT instead.
        RuleEngines engines = engines(EngineSettings.defaults().withRegexLimits(new RegexLimits(50, 16, 1)));
        publish(engines, REFERENCE_CHECK, "reference-check");

        DecisionResult result = engines.ruleEngine().evaluate("reference-check",
                DecisionInput.of(Map.of("reference", ADVERSARIAL)));

        assertThat(result.outcome()).isInstanceOfSatisfying(DecisionResult.Failed.class, failed -> {
            assertThat(failed.code()).isEqualTo("EVALUATION_ERROR");
            assertThat(failed.detail()).startsWith("REGEX_INPUT_TOO_LONG").contains("29 characters", "16");
        });
        assertThat(stores.decisions.getFirst().errorCode()).isEqualTo("REGEX_INPUT_TOO_LONG");
    }

    @Test
    void TR_SEC_02_a_pattern_that_recurses_past_the_stack_fails_the_decision_not_the_thread() throws Exception {
        // (a|b)*c recurses once per repetition; on a 256 KB stack a few thousand characters overflow it.
        CompiledRuleSet rules = compiled(REFERENCE_CHECK.replace(EXPONENTIAL_PATTERN, "(a|b)*c"),
                new RegexLimits(1000, 4096));
        List<CompiledRuleSet.Evaluation> results = new java.util.concurrent.CopyOnWriteArrayList<>();
        Thread small = new Thread(null, () -> {
            results.add(rules.evaluateWithReason(DecisionInput.of(Map.of("reference", "a".repeat(4096)))));
            results.add(rules.evaluateWithReason(DecisionInput.of(Map.of("reference", "abc"))));
        }, "small-stack", 256 * 1024);
        small.start();
        small.join(10_000);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).reason()).isEqualTo("REGEX_TOO_DEEP");
        assertThat(((DecisionResult.Failed) results.get(0).outcome()).detail()).doesNotContain("aaaa");
        assertThat(results.get(1).outcome()).isInstanceOf(DecisionResult.Matched.class);
    }

    @Test
    void TR_SEC_02_ten_adversarial_evaluations_do_not_delay_a_normal_one() throws Exception {
        CompiledRuleSet rules = compiled(REFERENCE_CHECK, RegexLimits.defaults());
        DecisionInput normal = DecisionInput.of(Map.of("reference", "FT20270115"));
        long usual = Long.MAX_VALUE;
        for (int i = 0; i < 50; i++) {
            long started = System.nanoTime();
            rules.evaluateWithReason(normal);
            usual = Math.min(usual, System.nanoTime() - started);
        }
        ExecutorService pool = Executors.newFixedThreadPool(10);
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        CountDownLatch started = new CountDownLatch(10);
        try {
            List<Future<Set<String>>> adversarial = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                adversarial.add(pool.submit(() -> {
                    // Each task keeps one adversarial match running until the normal decisions are measured.
                    Set<String> reasons = new java.util.HashSet<>();
                    started.countDown();
                    while (!stop.get()) {
                        reasons.add(rules.evaluateWithReason(DecisionInput.of(Map.of("reference", ADVERSARIAL)))
                                .reason());
                    }
                    return reasons;
                }));
            }
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            long worst = 0;
            for (int i = 0; i < 50; i++) {
                assertThat(adversarial).noneMatch(Future::isDone);
                long begin = System.nanoTime();
                CompiledRuleSet.Evaluation evaluation = rules.evaluateWithReason(normal);
                worst = Math.max(worst, System.nanoTime() - begin);
                assertThat(evaluation.outcome()).isInstanceOf(DecisionResult.Matched.class);
            }
            stop.set(true);
            // Usual time is microseconds; allow one scheduler time slice on a runner with fewer cores than threads.
            assertThat(worst / 1_000_000).isLessThan(Math.max(1, usual / 1_000_000) + 50);
            for (Future<Set<String>> run : adversarial) {
                assertThat(run.get(10, TimeUnit.SECONDS)).containsOnly("REGEX_TIMEOUT");
            }
        } finally {
            stop.set(true);
            pool.shutdownNow();
        }
    }

    @Test
    void TR_SEC_02_back_references_and_look_around_are_refused_at_import() {
        RuleEngines engines = engines(EngineSettings.defaults());
        caller.become("bank-a", "alice");
        for (String[] bad : new String[][] {
            // v1 strings escape a backslash as \\, and YAML double quotes escape it again.
            {"matches('(a)\\\\\\\\1')", "back-reference"},
            {"matches('(?<x>a)b')", "look-around or an inline flag"},
            {"matches('FT(?=[0-9])')", "look-around"},
            {"matches('(?<!X)FT')", "look-around"},
            {"matches('(?i)ft')", "inline flag"},
            {"matches('a++')", "possessive quantifier"},
            {"matches('[a[b]]')", "nests a character class"},
            {"matches('[a-z&&[^b]]')", "intersection"},
            {"matches('[a-z&&b]')", "intersection"},
            {"matches('\\\\\\\\x41')", "not in the XPath regex dialect"},
            {"matches('\\\\\\\\p{IsBasicLatin}')", "only general categories"},
            {"matches('[0-9')", "Invalid pattern"},
            {"matches('" + "a".repeat(1001) + "')", "longer than 1000"},
            {"matches(reference, reference)", "string literal"},
            {"matches(42)", "string literal"}}) {
            String yaml = REFERENCE_CHECK.replace("matches('" + EXPONENTIAL_PATTERN + "')", bad[0]);
            assertThatThrownBy(() -> engines.versionService().importRuleSet(
                    ImportRequest.yaml(yaml.getBytes(StandardCharsets.UTF_8))))
                    .as(bad[0]).isInstanceOf(ValidationFailedException.class)
                    .satisfies(e -> assertThat(((ValidationFailedException) e).violations())
                            .anySatisfy(v -> assertThat(v.message()).contains(bad[1])));
        }
    }

    @Test
    void TR_SEC_02_matches_needs_text_and_a_character_class_is_not_look_around() {
        RuleEngines engines = engines(EngineSettings.defaults());
        caller.become("bank-a", "alice");
        String amountCheck = """
                schemaVersion: 1
                ruleSet: amount-check
                hitPolicy: first
                inputs:
                  - { name: amount, type: integer }
                outputs:
                  - { name: route, type: string }
                rules:
                  - { id: R1, when: { amount: "matches(amount, '[0-9]+')" }, then: { route: "'AUTO'" } }
                """;
        assertThatThrownBy(() -> engines.versionService().importRuleSet(
                ImportRequest.yaml(amountCheck.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(ValidationFailedException.class).hasMessageContaining("matches() tests text");

        // Inside a character class "(?=" is three literal characters, not a look-ahead.
        CompiledRuleSet classes = compiled(REFERENCE_CHECK.replace("matches('" + EXPONENTIAL_PATTERN + "')",
                "matches('[(?=]')"), RegexLimits.defaults());
        assertThat(classes.evaluate(DecisionInput.of(Map.of("reference", "="))))
                .isInstanceOfSatisfying(DecisionResult.Matched.class,
                        matched -> assertThat(matched.outputs()).containsEntry("route", "REVIEW"));
    }

    @Test
    void TR_SEC_02_regex_limits_are_bounded_and_named() {
        assertThatThrownBy(() -> new RegexLimits(0, 10)).hasMessageContaining("regex-timeout-ms");
        assertThatThrownBy(() -> new RegexLimits(1001, 10)).hasMessageContaining("regex-timeout-ms");
        assertThatThrownBy(() -> new RegexLimits(50, 0)).hasMessageContaining("regex-max-input-length");
        assertThatThrownBy(() -> EngineSettings.defaults().withSizeLimits(EngineDefaults.OUTPUT_MAX_BYTES, 100,
                EngineDefaults.RULES_MAX).withRegexLimits(50, 101))
                .hasMessageContaining("regex-max-input-length").hasMessageContaining("max-string-length");
        assertThat(EngineSettings.defaults().regexLimits()).isEqualTo(new RegexLimits(50, 4096));
    }
}
