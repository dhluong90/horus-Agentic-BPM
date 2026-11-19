package com.horus.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.horus.bpm.rules.api.DecisionInput;
import com.horus.bpm.rules.api.DecisionResult;
import com.horus.bpm.rules.api.ImportRequest;
import com.horus.bpm.rules.api.SourceFormat;
import com.horus.bpm.rules.api.ValidationFailedException;
import com.horus.bpm.rules.api.WriteOptions;
import com.horus.bpm.rules.internal.eval.CompiledRuleSet;
import com.horus.bpm.rules.internal.expr.RegexLimits;
import com.horus.bpm.rules.internal.read.RuleSetReader;
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
              - { id: R1, when: { reference: "matches('(a+)+$')" }, then: { route: "'REVIEW'" } }
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
                .model(), com.horus.bpm.rules.internal.service.EngineDefaults.OUTPUT_MAX_BYTES,
                com.horus.bpm.rules.internal.service.EngineDefaults.STRING_MAX_LENGTH, limits);
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
            assertThat(failed.detail()).startsWith("REGEX_TIMEOUT").contains("50 ms").doesNotContain(ADVERSARIAL);
        });
        assertThat(stores.decisions).singleElement().satisfies(row -> {
            assertThat(row.outcome()).isEqualTo("EVALUATION_ERROR");
            assertThat(row.errorCode()).isEqualTo("REGEX_TIMEOUT");
            assertThat(row.outputsJson()).isNull();
        });
        // The thread is free: the same thread evaluates a normal reference straight after.
        assertThat(engines.ruleEngine().evaluate("reference-check", DecisionInput.of(Map.of("reference", "FT20270115")))
                .outputs()).containsEntry("route", "AUTO");
    }

    @Test
    void TR_SEC_02_text_longer_than_the_limit_fails_without_running_the_match() {
        RuleEngines engines = engines(EngineSettings.defaults().withRegexLimits(50, 16));
        publish(engines, REFERENCE_CHECK, "reference-check");

        long started = System.nanoTime();
        DecisionResult result = engines.ruleEngine().evaluate("reference-check",
                DecisionInput.of(Map.of("reference", ADVERSARIAL)));

        // The adversarial text would run for the whole timeout; refused before matching, it takes far less.
        assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(40);
        assertThat(result.outcome()).isInstanceOfSatisfying(DecisionResult.Failed.class, failed -> {
            assertThat(failed.code()).isEqualTo("EVALUATION_ERROR");
            assertThat(failed.detail()).startsWith("REGEX_INPUT_TOO_LONG").contains("29 characters", "16");
        });
        assertThat(stores.decisions.getFirst().errorCode()).isEqualTo("REGEX_INPUT_TOO_LONG");
    }

    @Test
    void TR_SEC_02_ten_adversarial_evaluations_do_not_delay_a_normal_one() throws Exception {
        CompiledRuleSet rules = compiled(REFERENCE_CHECK, new RegexLimits(50, 4096));
        DecisionInput normal = DecisionInput.of(Map.of("reference", "FT20270115"));
        long usual = Long.MAX_VALUE;
        for (int i = 0; i < 20; i++) {
            long started = System.nanoTime();
            rules.evaluateWithReason(normal);
            usual = Math.min(usual, System.nanoTime() - started);
        }
        ExecutorService pool = Executors.newFixedThreadPool(11);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<CompiledRuleSet.Evaluation>> adversarial = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                adversarial.add(pool.submit(() -> {
                    go.await();
                    return rules.evaluateWithReason(DecisionInput.of(Map.of("reference", ADVERSARIAL)));
                }));
            }
            Future<Long> normalRun = pool.submit(() -> {
                go.await();
                Thread.sleep(5); // the adversarial matches are running
                long started = System.nanoTime();
                CompiledRuleSet.Evaluation evaluation = rules.evaluateWithReason(normal);
                long elapsed = System.nanoTime() - started;
                assertThat(evaluation.outcome()).isInstanceOf(DecisionResult.Matched.class);
                return elapsed;
            });
            go.countDown();

            long normalNanos = normalRun.get(5, TimeUnit.SECONDS);
            // Usual time is microseconds; allow one scheduler time slice on a runner with fewer cores than threads.
            assertThat(normalNanos / 1_000_000).isLessThan(Math.max(1, usual / 1_000_000) + 50);
            for (Future<CompiledRuleSet.Evaluation> run : adversarial) {
                assertThat(run.get(5, TimeUnit.SECONDS).reason()).isEqualTo("REGEX_TIMEOUT");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void TR_SEC_02_back_references_and_look_around_are_refused_at_import() {
        RuleEngines engines = engines(EngineSettings.defaults());
        caller.become("bank-a", "alice");
        for (String[] bad : new String[][] {
            {"matches('(a)\\\\1')", "back-reference"},
            {"matches('(?<x>a)\\\\k<x>')", "named back-reference"},
            {"matches('FT(?=[0-9])')", "look-ahead"},
            {"matches('(?<!X)FT')", "look-behind"},
            {"matches('[0-9')", "Invalid pattern"},
            {"matches('" + "a".repeat(1001) + "')", "longer than 1000"},
            {"matches(reference, reference)", "string literal"},
            {"matches(42)", "string literal"}}) {
            String yaml = REFERENCE_CHECK.replace("matches('(a+)+$')", bad[0]);
            assertThatThrownBy(() -> engines.versionService().importRuleSet(
                    ImportRequest.yaml(yaml.getBytes(StandardCharsets.UTF_8))))
                    .as(bad[0]).isInstanceOf(ValidationFailedException.class)
                    .satisfies(e -> assertThat(((ValidationFailedException) e).violations())
                            .anySatisfy(v -> assertThat(v.message()).contains(bad[1])));
        }
    }

    @Test
    void TR_SEC_02_matches_needs_text_and_escapes_inside_classes_are_not_back_references() {
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

        CompiledRuleSet classes = compiled(REFERENCE_CHECK.replace("matches('(a+)+$')", "matches('[\\\\1x]')"),
                RegexLimits.defaults());
        assertThat(classes.evaluate(DecisionInput.of(Map.of("reference", "x"))))
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
