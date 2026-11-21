package io.github.dhluong90.bpm.rules.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.RuleSetNotFoundException;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.json.EngineJson;
import io.github.dhluong90.bpm.rules.internal.store.TransactionRunner;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The evaluation and retirement records of DESIGN section 4.8 (TR-OBS-02) on the in-memory stores: one record per
 * evaluation call with its outcome and error code, a refusal for another tenant's rule set recorded without writing a
 * decision, retirements counted by reason once committed and never when rolled back, and no value outside the fixed
 * sets ever reaching a recorder. The Micrometer names and tags are tested in the starter.
 */
class EngineMetersTest {

    static final Instant NOW = Instant.parse("2026-12-15T02:00:00Z");

    final InMemoryStores stores = new InMemoryStores();
    final TestCaller caller = new TestCaller("bank-a", "alice");
    final EditorApiTest.MovableClock clock = new EditorApiTest.MovableClock(NOW);
    final AfterCommitQueue transactions = new AfterCommitQueue(stores);
    final Records records = new Records();

    RuleEngines engines() {
        return engines(new EngineSettings(1000, Duration.ofHours(24), 34, true));
    }

    RuleEngines engines(EngineSettings settings) {
        RuleEngines engines = RuleEngines.create(new EngineContext(caller, caller, clock, transactions, stores,
                settings), stores, stores, stores);
        engines.meters().bind(records);
        return engines;
    }

    @Test
    void TR_OBS_02_each_evaluation_call_is_recorded_once_with_its_outcome() {
        // Given card-fee (first hit, a default row) and overdraft-fee (no default row) published for bank-a
        RuleEngines engines = engines();
        publish(engines, "card-fee", CardFee.yaml(), 1);
        publish(engines, "overdraft-fee", OVERDRAFT_FEE.getBytes(StandardCharsets.UTF_8), 1);
        transactions.commit();

        // When a teller prices a Gold card, a Silver card with one transaction and an account 2 days overdrawn
        caller.become("bank-a", "teller-01");
        engines.ruleEngine().evaluate("card-fee", DecisionInput.of(Map.of("tier", "GOLD", "txCount", 0)));
        engines.ruleEngine().evaluate("card-fee", DecisionInput.of(Map.of("tier", "SILVER", "txCount", 1)));
        engines.ruleEngine().evaluate("overdraft-fee", DecisionInput.of(Map.of("overdrawnDays", 2)));

        // Then three calls are recorded: two matched, one without a match, none with a code, each with a duration
        assertThat(records.evaluations).extracting(Evaluation::outcome).containsExactly(EngineMeters.Outcome.MATCHED,
                EngineMeters.Outcome.MATCHED, EngineMeters.Outcome.NO_MATCH);
        assertThat(records.evaluations).allSatisfy(e -> {
            assertThat(e.code()).isNull();
            assertThat(e.nanos()).isPositive();
        });
    }

    @Test
    void TR_OBS_02_a_request_for_another_tenants_rule_set_is_refused_recorded_by_code_and_writes_nothing() {
        // Given card-fee published for bank-a only
        RuleEngines engines = engines();
        publish(engines, "card-fee", CardFee.yaml(), 1);
        int decisions = stores.decisions.size();

        // When a teller of bank-b evaluates card-fee
        caller.become("bank-b", "teller-07");
        assertThatThrownBy(() -> engines.ruleEngine().evaluate("card-fee",
                DecisionInput.of(Map.of("tier", "GOLD", "txCount", 0))))
                .isInstanceOf(RuleSetNotFoundException.class);

        // Then the call is refused with RULE_SET_NOT_FOUND, no decision is written, and no record names a tenant
        assertThat(stores.decisions).hasSize(decisions);
        assertThat(records.evaluations).containsExactly(
                new Evaluation(EngineMeters.Outcome.REFUSED, "RULE_SET_NOT_FOUND", records.evaluations.get(0).nanos()));
        assertThat(records.evaluations.toString()).doesNotContain("bank-a", "bank-b", "card-fee", "teller-07");
    }

    @Test
    void TR_OBS_02_a_caller_without_the_evaluate_permission_is_recorded_as_refused() {
        // Given card-fee published for bank-a
        RuleEngines engines = engines();
        publish(engines, "card-fee", CardFee.yaml(), 1);

        // When an author without rules:evaluate evaluates it
        caller.become("bank-a", "author-02", java.util.Set.of("rules:author"));
        assertThatThrownBy(() -> engines.ruleEngine().evaluate("card-fee",
                DecisionInput.of(Map.of("tier", "GOLD", "txCount", 0))))
                .isInstanceOf(io.github.dhluong90.bpm.rules.api.PermissionDeniedException.class);

        // Then the refusal is recorded with the permission code
        assertThat(records.evaluations).extracting(Evaluation::outcome, Evaluation::code)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(EngineMeters.Outcome.REFUSED,
                        new io.github.dhluong90.bpm.rules.api.PermissionDeniedException("rules:evaluate").code()));
    }

    @Test
    void TR_OBS_02_retirements_are_counted_by_reason_after_commit_and_never_on_rollback() {
        // Given card-fee v1 in force
        RuleEngines engines = engines();
        publish(engines, "card-fee", CardFee.yaml(), 1);
        transactions.commit();

        // When v2 is approved, effective at once, and commits
        long lock = publish(engines, "card-fee", withDefaultFee(60000), 2).lockVersion();

        // Then v1 is retired as SUPERSEDED, counted only once the transaction commits
        assertThat(records.retired).isEmpty();
        transactions.commit();
        assertThat(records.retired).containsExactly(Map.entry(EngineMeters.RetireReason.SUPERSEDED, 1));

        // When bob retires v2 by hand, but the transaction rolls back
        clock.set(NOW.plus(Duration.ofHours(1)));
        caller.become("bank-a", "bob");
        engines.versionService().retire("card-fee", 2, new WriteOptions(null, lock, "Fee table withdrawn"), true);
        transactions.rollback();

        // Then nothing more is counted
        assertThat(records.retired).hasSize(1);
    }

    @Test
    void TR_OBS_02_a_manual_retirement_is_counted_as_manual() {
        // Given card-fee v1 in force
        RuleEngines engines = engines();
        long lock = publish(engines, "card-fee", CardFee.yaml(), 1).lockVersion();
        transactions.commit();

        // When bob retires it, confirming that no version stays in effect
        caller.become("bank-a", "bob");
        engines.versionService().retire("card-fee", 1, new WriteOptions(null, lock, "Fee table withdrawn"), true);
        transactions.commit();

        // Then one MANUAL retirement is counted
        assertThat(records.retired).containsExactly(Map.entry(EngineMeters.RetireReason.MANUAL, 1));
    }

    @Test
    void TR_OBS_02_a_four_eyes_retirement_is_counted_once_at_confirmation_and_never_at_request() {
        // Given card-fee v1 in force, with retirement under four eyes
        RuleEngines engines = engines(new EngineSettings(1000, Duration.ofHours(24), 34, true)
                .withLifecycle("system:supersession", "four-eyes"));
        long lock = publish(engines, "card-fee", CardFee.yaml(), 1).lockVersion();
        transactions.commit();

        // When bob requests the retirement and it commits
        caller.become("bank-a", "bob");
        long requested = engines.versionService()
                .retire("card-fee", 1, new WriteOptions(null, lock, "Fee table withdrawn"), true).lockVersion();
        transactions.commit();

        // Then nothing is counted yet
        assertThat(records.retired).isEmpty();

        // When carol confirms it and it commits
        caller.become("bank-a", "carol");
        engines.versionService().confirmRetire("card-fee", 1, new WriteOptions(null, requested, null), true);
        transactions.commit();

        // Then one MANUAL retirement is counted
        assertThat(records.retired).containsExactly(Map.entry(EngineMeters.RetireReason.MANUAL, 1));
    }

    @Test
    void TR_OBS_02_only_engine_error_codes_reach_a_recorder() {
        // Given codes of the engine and free text a host exception might carry
        // When they are kept as tag values
        // Then a code keeps its form and anything else becomes OTHER
        assertThat(EngineMeters.code("RULE_SET_NOT_FOUND")).isEqualTo("RULE_SET_NOT_FOUND");
        assertThat(EngineMeters.code("UNIQUE_HIT_VIOLATION")).isEqualTo("UNIQUE_HIT_VIOLATION");
        assertThat(EngineMeters.code("bank-a/card-fee")).isEqualTo(EngineMeters.OTHER_CODE);
        assertThat(EngineMeters.code("Customer 079090001234 not found")).isEqualTo(EngineMeters.OTHER_CODE);
        assertThat(EngineMeters.code("A".repeat(49))).isEqualTo(EngineMeters.OTHER_CODE);
        assertThat(EngineMeters.code(null)).isEqualTo(EngineMeters.OTHER_CODE);
    }

    @Test
    void TR_OBS_02_error_code_tags_come_from_an_allow_list_of_the_contracts_codes() throws Exception {
        // Given the Problem.code enum of the REST contract
        JsonNode spec = EngineJson.YAML.readTree(Files.readAllBytes(Path.of("../api/openapi.yaml")));
        Set<String> contract = new TreeSet<>();
        spec.at("/components/schemas/Problem/properties/code/enum").forEach(code -> contract.add(code.asString()));

        // When / Then: the allow-list is exactly the contract's codes, and each keeps its value as a tag
        assertThat(contract).isNotEmpty();
        assertThat(new TreeSet<>(EngineMeters.KNOWN_CODES)).isEqualTo(contract);
        assertThat(contract).allSatisfy(code -> assertThat(EngineMeters.code(code)).isEqualTo(code));

        // And a host code that only looks like an engine code becomes OTHER, as does free text
        assertThat(EngineMeters.code("CUSTOMER_NOT_FOUND")).isEqualTo(EngineMeters.OTHER_CODE);
        assertThat(EngineMeters.code("ACCOUNT_079090001234_FROZEN")).isEqualTo(EngineMeters.OTHER_CODE);
        assertThat(EngineMeters.code("rule_set_not_found")).isEqualTo(EngineMeters.OTHER_CODE);
        assertThat(EngineMeters.code("RULE_SET_NOT_FOUND ")).isEqualTo(EngineMeters.OTHER_CODE);
    }

    @Test
    void TR_OBS_02_with_no_recorder_bound_evaluation_records_nothing_and_still_works() {
        // Given an engine whose meters have no recorder
        EngineSettings settings = new EngineSettings(1000, Duration.ofHours(24), 34, true);
        RuleEngines engines = RuleEngines.create(new EngineContext(caller, caller, clock, stores, stores, settings),
                stores, stores, stores);
        publish(engines, "card-fee", CardFee.yaml(), 1);

        // When a teller evaluates
        caller.become("bank-a", "teller-01");
        var result = engines.ruleEngine().evaluate("card-fee", DecisionInput.of(Map.of("tier", "GOLD", "txCount", 0)));

        // Then the decision is the usual one
        assertThat(result.matchedRuleIds()).containsExactly("R1");
    }

    private RuleSetVersion publish(RuleEngines engines, String ruleSet, byte[] yaml, int version) {
        caller.become("bank-a", "alice");
        if (version == 1) {
            engines.versionService().importRuleSet(ImportRequest.yaml(yaml));
        } else {
            engines.versionService().importVersion(ruleSet, ImportRequest.yaml(yaml));
        }
        RuleSetVersion requested = engines.versionService().requestPublish(ruleSet, version, WriteOptions.none());
        caller.become("bank-a", "bob");
        return engines.versionService().approve(ruleSet, version,
                new WriteOptions(null, requested.lockVersion(), null).expectingContent(requested.canonicalHash()));
    }

    private static byte[] withDefaultFee(long fee) {
        return new String(CardFee.yaml(), StandardCharsets.UTF_8).replace("\"50000 VND\"", "\"" + fee + " VND\"")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** An overdraft fee from day 5; no default row, so a shorter overdraft is a no-match. */
    static final String OVERDRAFT_FEE = """
            schemaVersion: 1
            ruleSet: overdraft-fee
            hitPolicy: first
            inputs:
              - { name: overdrawnDays, type: integer }
            outputs:
              - { name: fee, type: money }
            rules:
              - id: R1
                when: { overdrawnDays: ">= 5" }
                then: { fee: "200000 VND" }
            """;

    record Evaluation(EngineMeters.Outcome outcome, String code, long nanos) {
    }

    /** Collects what the engine records. */
    static final class Records implements EngineMeters.Recorder {
        final List<Evaluation> evaluations = new ArrayList<>();
        final List<Map.Entry<EngineMeters.RetireReason, Integer>> retired = new ArrayList<>();

        @Override
        public synchronized void evaluation(EngineMeters.Outcome outcome, String code, long nanos) {
            evaluations.add(new Evaluation(outcome, code, nanos));
        }

        @Override
        public synchronized void retired(EngineMeters.RetireReason reason, int count) {
            retired.add(Map.entry(reason, count));
        }
    }

    /**
     * The in-memory transactions, with after-commit work held until the test commits or rolls back. The in-memory
     * stores keep what they wrote either way; only the after-commit work is under test.
     */
    static final class AfterCommitQueue implements TransactionRunner {
        private final TransactionRunner delegate;
        private final List<Runnable> pending = new ArrayList<>();

        AfterCommitQueue(TransactionRunner delegate) {
            this.delegate = delegate;
        }

        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return delegate.inTransaction(work);
        }

        @Override
        public void afterCommit(Runnable work) {
            pending.add(work);
        }

        void commit() {
            List<Runnable> work = List.copyOf(pending);
            pending.clear();
            work.forEach(Runnable::run);
        }

        void rollback() {
            pending.clear();
        }
    }
}
