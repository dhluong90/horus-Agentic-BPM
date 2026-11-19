package io.github.dhluong90.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.HorusBpmException;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.LifecycleAction;
import io.github.dhluong90.bpm.rules.api.NoEffectiveVersionException;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.VersionHistoryEntry;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.service.EngineSettings;
import io.github.dhluong90.bpm.rules.internal.service.RuleEngines;
import io.github.dhluong90.bpm.rules.spi.CallerResolver;
import io.github.dhluong90.bpm.rules.spi.TenantResolver;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * RLE-026 and RLE-027 on PostgreSQL (#28): retirement by hand and by supersession through the real SQL, with two
 * engine instances (replicas) on one fresh database per test. Selection reads {@code retired_at}; a concurrent retire
 * and concurrent sweeps record each retirement exactly once, without an advisory lock.
 */
@Testcontainers
class RetireVersionPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(System.getProperty("bpm.test.postgres.image", "postgres:16-alpine"));

    static final Set<String> AUTHOR = Set.of("rules:read", "rules:author");
    static final Set<String> APPROVER = Set.of("rules:read", "rules:approve");
    static final Set<String> TELLER = Set.of("rules:evaluate");

    final Caller caller = new Caller();
    final EditorApiPostgresIT.Movable clock = new EditorApiPostgresIT.Movable(Instant.parse("2026-10-20T00:00:00Z"));
    final List<String> statements = Collections.synchronizedList(new ArrayList<>());
    JdbcClient jdbc;
    RuleEngines podA;
    RuleEngines podB;

    @BeforeEach
    void setUp() {
        String name = "retire_" + System.nanoTime();
        JdbcClient.create(dataSource(POSTGRES.getDatabaseName())).sql("CREATE DATABASE " + name).update();
        DataSource db = dataSource(name);
        new RuleEngineSchemaMigrator(db).migrate();
        jdbc = JdbcClient.create(db);
        DataSource captured = new RuleEnginePostgresIT.SqlCapturingDataSource(db, statements);
        podA = engines(captured);
        podB = engines(captured);
    }

    RuleEngines engines(DataSource db) {
        RuleEngineTransactions transactions = new RuleEngineTransactions(new DataSourceTransactionManager(db));
        RuleEngines engines = PostgresRuleEngines.create(db, transactions, caller, caller, clock, EngineSettings.defaults());
        engines.cursorKeys().load();
        return engines;
    }

    static DataSource dataSource(String database) {
        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database));
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }

    static byte[] fee(String ruleSet, String effectiveFrom, String fee) {
        return ("""
                schemaVersion: 1
                ruleSet: %s
                effectiveFrom: "%s"
                hitPolicy: first
                inputs:
                  - { name: tier, type: string }
                outputs:
                  - { name: fee, type: money }
                rules:
                  - { id: R1, when: {}, then: { fee: "%s" } }
                """).formatted(ruleSet, effectiveFrom, fee).getBytes(StandardCharsets.UTF_8);
    }

    RuleSetVersion publish(String tenant, String ruleSet, byte[] document, Instant approvedAt) {
        caller.as(tenant, "lan", AUTHOR);
        int number = podA.versionService().findRuleSet(ruleSet).isPresent()
                ? podA.versionService().importVersion(ruleSet, ImportRequest.yaml(document)).version()
                : podA.versionService().importRuleSet(ImportRequest.yaml(document)).version();
        podA.versionService().requestPublish(ruleSet, number, WriteOptions.none());
        clock.set(approvedAt);
        caller.as(tenant, "minh", APPROVER);
        return podA.versionService().approve(ruleSet, number, WriteOptions.none());
    }

    int evaluate(RuleEngines pod, String ruleSet) {
        caller.as("bank-a", "teller", TELLER);
        return pod.ruleEngine().evaluate(ruleSet, DecisionInput.of(Map.of("tier", "STANDARD"))).version();
    }

    long retiredEvents() {
        return jdbc.sql("SELECT count(*) FROM rle_decision_outbox WHERE payload->>'eventType' = 'rules.version.retired.v1'")
                .query(Long.class).single();
    }

    long retiredRows() {
        return jdbc.sql("SELECT count(*) FROM rle_version_history WHERE action = 'RETIRED'").query(Long.class).single();
    }

    @Test
    void RLE_026_manual_retire_stops_every_replica_serving_the_version() {
        // Given tet-fee-waiver v1 effective since 20 Jan 2027, served by both replicas
        RuleSetVersion v1 = publish("bank-a", "tet-fee-waiver", fee("tet-fee-waiver", "2027-01-20T00:00:00Z", "0 VND"),
                Instant.parse("2027-01-15T00:00:00Z"));
        clock.set(Instant.parse("2027-02-01T09:00:00Z"));
        assertThat(evaluate(podA, "tet-fee-waiver")).isEqualTo(1);
        assertThat(evaluate(podB, "tet-fee-waiver")).isEqualTo(1);

        // When bob retires it on pod A at 2027-02-17T08:00:00Z
        clock.set(Instant.parse("2027-02-17T08:00:00Z"));
        caller.as("bank-a", "bob", APPROVER);
        // without the confirmation the last version in effect stays (PD-101); the key is not used up by the refusal
        assertThatThrownBy(() -> podA.versionService().retire("tet-fee-waiver", 1,
                new WriteOptions("k-301", v1.lockVersion(), "Tet promotion ended")))
                .extracting(e -> ((HorusBpmException) e).code()).isEqualTo("LAST_VERSION_IN_EFFECT");
        assertThat(retiredEvents()).isZero();
        podA.versionService().retire("tet-fee-waiver", 1, new WriteOptions("k-301", v1.lockVersion(), "Tet promotion ended"),
                true);

        // Then the row, the history and the event are written in that transaction
        assertThat(jdbc.sql("SELECT status, retired_at FROM rle_version WHERE tenant_id = 'bank-a'")
                .query((rs, n) -> rs.getString("status") + " " + rs.getTimestamp("retired_at").toInstant()).single())
                .isEqualTo("RETIRED 2027-02-17T08:00:00Z");
        assertThat(jdbc.sql("SELECT actor || ' ' || reason || ' ' || approval_mode || ' ' || no_version_in_effect"
                + " FROM rle_version_history WHERE action = 'RETIRED'").query(String.class).single())
                .isEqualTo("bob Tet promotion ended single true");
        assertThat(jdbc.sql("SELECT payload->>'noVersionInEffect' FROM rle_decision_outbox"
                + " WHERE payload->>'eventType' = 'rules.version.retired.v1'").query(String.class).single()).isEqualTo("true");
        assertThat(retiredEvents()).isEqualTo(1);
        // and 5 s later neither replica serves it; a clock before the retirement still selects it on pod B
        clock.set(Instant.parse("2027-02-17T08:00:05Z"));
        assertThatThrownBy(() -> evaluate(podA, "tet-fee-waiver")).isInstanceOf(NoEffectiveVersionException.class);
        assertThatThrownBy(() -> evaluate(podB, "tet-fee-waiver")).isInstanceOf(NoEffectiveVersionException.class);
        clock.set(Instant.parse("2027-02-01T09:00:00Z"));
        assertThat(evaluate(podB, "tet-fee-waiver")).isEqualTo(1);
        // and the history reads back the mode, and the list shows no published version at the host clock
        clock.set(Instant.parse("2027-02-18T00:00:00Z"));
        caller.as("bank-a", "dan", Set.of("rules:read"));
        VersionHistoryEntry last = podB.versionService().history("tet-fee-waiver", 1).getLast();
        assertThat(last.action()).isEqualTo(LifecycleAction.RETIRED);
        assertThat(last.approvalMode()).isEqualTo("single");
        assertThat(last.supersededBy()).isNull();
        assertThat(last.noVersionInEffect()).isTrue();
        assertThat(podB.versionService().findRuleSet("tet-fee-waiver").orElseThrow().publishedVersion()).isNull();
        assertThat(podB.versionService().find("tet-fee-waiver", 1, false).orElseThrow().retiredAt())
                .isEqualTo(Instant.parse("2027-02-17T08:00:00Z"));
    }

    @Test
    void RLE_026_two_approvers_retiring_the_same_version_at_once_retire_it_once() throws Exception {
        RuleSetVersion v1 = publish("bank-a", "tet-fee-waiver", fee("tet-fee-waiver", "2027-01-20T00:00:00Z", "0 VND"),
                Instant.parse("2027-01-15T00:00:00Z"));
        clock.set(Instant.parse("2027-02-17T08:00:00Z"));
        CyclicBarrier together = new CyclicBarrier(2);
        List<String> outcomes = Collections.synchronizedList(new ArrayList<>());

        // When bob on pod A and minh on pod B retire v1 with the same lock version at the same moment
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> both = new ArrayList<>();
            for (var who : List.of(Map.entry("bob", podA), Map.entry("minh", podB))) {
                both.add(pool.submit(() -> {
                    caller.asThread("bank-a", who.getKey(), APPROVER);
                    together.await();
                    try {
                        who.getValue().versionService().retire("tet-fee-waiver", 1,
                                new WriteOptions(null, v1.lockVersion(), "Tet promotion ended"), true);
                        outcomes.add("200");
                    } catch (HorusBpmException refused) {
                        outcomes.add(refused.code());
                    }
                    return null;
                }));
            }
            for (Future<?> f : both) {
                f.get();
            }
        }

        // Then one succeeds, the other is a 409, and exactly one retirement is recorded
        assertThat(outcomes).hasSize(2).containsOnlyOnce("200");
        assertThat(outcomes).filteredOn(code -> !code.equals("200")).singleElement()
                .isIn("INVALID_STATE", "VERSION_CONFLICT");
        assertThat(retiredEvents()).isEqualTo(1);
        assertThat(retiredRows()).isEqualTo(1);
    }

    @Test
    void RLE_027_previous_version_retires_once_when_newer_becomes_effective() throws Exception {
        // Given twenty rule sets, each with v1 from 1 Nov and v2 approved on 25 Nov for 1 Dec
        for (int i = 0; i < 20; i++) {
            String name = "card-fee-" + i;
            publish("bank-a", name, fee(name, "2026-11-01T00:00:00Z", "50000 VND"), Instant.parse("2026-10-20T00:00:00Z"));
            publish("bank-a", name, fee(name, "2026-12-01T00:00:00Z", "30000 VND"), Instant.parse("2026-11-25T10:00:00Z"));
        }
        clock.set(Instant.parse("2026-11-30T23:59:59Z"));
        assertThat(evaluate(podA, "card-fee-0")).isEqualTo(1);

        // When the clock passes 1 Dec: both replicas select v2 before any sweep
        clock.set(Instant.parse("2026-12-01T00:00:01Z"));
        assertThat(evaluate(podA, "card-fee-0")).isEqualTo(2);
        assertThat(evaluate(podB, "card-fee-0")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM rle_version WHERE status = 'PUBLISHED'").query(Long.class).single())
                .isEqualTo(40L);

        // When the sweep runs on both replicas at the same moment
        statements.clear();
        CyclicBarrier together = new CyclicBarrier(2);
        int retired;
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Integer> a = pool.submit(() -> { together.await(); return podA.lifecycleSweep().run(); });
            Future<Integer> b = pool.submit(() -> { together.await(); return podB.lifecycleSweep().run(); });
            retired = a.get() + b.get();
        }

        // Then every v1 is RETIRED exactly once: twenty events and twenty history rows, actor system, SUPERSEDED
        assertThat(retired).isEqualTo(20);
        assertThat(retiredEvents()).isEqualTo(20);
        assertThat(retiredRows()).isEqualTo(20);
        assertThat(jdbc.sql("SELECT DISTINCT actor || ' ' || reason || ' ' || superseded_by || ' ' || approval_mode"
                + " FROM rle_version_history WHERE action = 'RETIRED'").query(String.class).list())
                .containsExactly("system SUPERSEDED 2 system");
        assertThat(jdbc.sql("SELECT DISTINCT retired_at FROM rle_version WHERE status = 'RETIRED'")
                .query(Timestamp.class).single().toInstant()).isEqualTo(Instant.parse("2026-12-01T00:00:01Z"));
        // and no advisory lock or session state was used
        assertThat(statements).isNotEmpty().noneMatch(sql -> sql.toLowerCase(java.util.Locale.ROOT).contains("advisory"))
                .noneMatch(sql -> sql.toLowerCase(java.util.Locale.ROOT).contains("set session"));
        // and a second sweep retires nothing; a clock of 20 Nov still selects v1
        assertThat(podA.lifecycleSweep().run()).isZero();
        clock.set(Instant.parse("2026-11-20T10:00:00Z"));
        assertThat(evaluate(podB, "card-fee-7")).isEqualTo(1);
    }

    @Test
    void RLE_027_a_version_effective_at_approval_retires_the_previous_one_in_the_same_transaction() {
        publish("bank-a", "fx-margin", fee("fx-margin", "2026-11-01T00:00:00Z", "100 VND"),
                Instant.parse("2026-10-20T00:00:00Z"));
        // v2's effectiveFrom equals its approval instant
        publish("bank-a", "fx-margin", fee("fx-margin", "2026-11-20T10:00:00Z", "120 VND"),
                Instant.parse("2026-11-20T10:00:00Z"));

        assertThat(jdbc.sql("SELECT version_num || ' ' || status FROM rle_version ORDER BY version_num")
                .query(String.class).list()).containsExactly("1 RETIRED", "2 PUBLISHED");
        assertThat(retiredEvents()).isEqualTo(1);
        assertThat(podB.lifecycleSweep().run()).isZero();
    }

    @Test
    void RLE_026_retiring_a_scheduled_version_leaves_the_version_in_force_serving() {
        // Given card-fee v1 in force since 1 Nov and v2 approved on 25 Nov for 1 Dec (ADR-0025, as clarified in #276)
        publish("bank-a", "card-fee", fee("card-fee", "2026-11-01T00:00:00Z", "50000 VND"),
                Instant.parse("2026-10-20T00:00:00Z"));
        RuleSetVersion v2 = publish("bank-a", "card-fee", fee("card-fee", "2026-12-01T00:00:00Z", "30000 VND"),
                Instant.parse("2026-11-25T10:00:00Z"));

        // When bob cancels the December change on 26 Nov by retiring v2 on pod A
        clock.set(Instant.parse("2026-11-26T09:00:00Z"));
        caller.as("bank-a", "bob", APPROVER);
        podA.versionService().retire("card-fee", 2, new WriteOptions("k-cancel", v2.lockVersion(),
                "December tariff postponed"));

        // Then only v2 is retired; v1 stays PUBLISHED and serves on both replicas, also after 1 Dec, and the sweep
        // finds nothing to retire
        assertThat(jdbc.sql("SELECT version_num || ' ' || status FROM rle_version ORDER BY version_num")
                .query(String.class).list()).containsExactly("1 PUBLISHED", "2 RETIRED");
        assertThat(retiredEvents()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT no_version_in_effect FROM rle_version_history WHERE action = 'RETIRED'")
                .query(Boolean.class).single()).isFalse();
        assertThat(evaluate(podB, "card-fee")).isEqualTo(1);
        clock.set(Instant.parse("2026-12-05T00:00:00Z"));
        assertThat(evaluate(podA, "card-fee")).isEqualTo(1);
        assertThat(evaluate(podB, "card-fee")).isEqualTo(1);
        assertThat(podB.lifecycleSweep().run()).isZero();
        assertThat(retiredEvents()).isEqualTo(1);
    }

    @Test
    void RLE_026_two_retirements_of_the_last_versions_lock_the_rule_set_and_run_one_after_the_other() throws Exception {
        // Given card-fee v1 in force and v2 approved for 1 Dec (PD-101: v2 does not count as in effect)
        RuleSetVersion v1 = publish("bank-a", "card-fee", fee("card-fee", "2026-11-01T00:00:00Z", "50000 VND"),
                Instant.parse("2026-10-20T00:00:00Z"));
        RuleSetVersion v2 = publish("bank-a", "card-fee", fee("card-fee", "2026-12-01T00:00:00Z", "30000 VND"),
                Instant.parse("2026-11-25T10:00:00Z"));
        clock.set(Instant.parse("2026-11-26T09:00:00Z"));
        statements.clear();

        // When bob on pod A retires v2 without the confirmation while minh on
        // pod B retires v1 with it, at the same moment
        CyclicBarrier together = new CyclicBarrier(2);
        String v2Outcome;
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<String> a = pool.submit(() -> {
                caller.asThread("bank-a", "bob", APPROVER);
                together.await();
                try {
                    podA.versionService().retire("card-fee", 2, new WriteOptions(null, v2.lockVersion(), "Postponed"));
                    return "200";
                } catch (HorusBpmException refused) {
                    return refused.code();
                }
            });
            Future<?> b = pool.submit(() -> {
                caller.asThread("bank-a", "minh", APPROVER);
                together.await();
                podB.versionService().retire("card-fee", 1, new WriteOptions(null, v1.lockVersion(), "Withdrawn"), true);
                return null;
            });
            v2Outcome = a.get();
            b.get();
        }

        // Then the rule set lock ran them one after the other, and whichever order they took, no version left the
        // rule set empty without a confirmed retirement saying so:
        // - v2 first: v1 was still in force, so v2 retired with false, then v1 retired with true;
        // - v1 first: v1 retired with true, then v2 was the last (scheduled only) and was refused, unconfirmed.
        List<String> rows = jdbc.sql("SELECT v.version_num || ' ' || h.no_version_in_effect FROM rle_version_history h"
                + " JOIN rle_version v ON v.tenant_id = h.tenant_id AND v.id = h.version_id WHERE h.action = 'RETIRED'"
                + " ORDER BY v.version_num").query(String.class).list();
        if (v2Outcome.equals("200")) {
            assertThat(rows).containsExactly("1 true", "2 false");
        } else {
            assertThat(v2Outcome).isEqualTo("LAST_VERSION_IN_EFFECT");
            assertThat(rows).containsExactly("1 true");
        }
        assertThat(statements).filteredOn(sql -> sql.contains("FROM rle_rule_set") && sql.contains("FOR UPDATE"))
                .hasSizeGreaterThanOrEqualTo(2);
        assertThatThrownBy(() -> evaluate(podA, "card-fee")).isInstanceOf(NoEffectiveVersionException.class);
    }

    @Test
    void RLE_026_a_retire_from_another_tenant_finds_nothing_and_writes_nothing() {
        RuleSetVersion v1 = publish("bank-a", "tet-fee-waiver", fee("tet-fee-waiver", "2027-01-20T00:00:00Z", "0 VND"),
                Instant.parse("2027-01-15T00:00:00Z"));
        long events = jdbc.sql("SELECT count(*) FROM rle_decision_outbox").query(Long.class).single();

        caller.as("bank-b", "bob", APPROVER);
        assertThatThrownBy(() -> podB.versionService().retire("tet-fee-waiver", 1,
                new WriteOptions("k-b", v1.lockVersion(), "Not ours")))
                .extracting(e -> ((HorusBpmException) e).code()).isEqualTo("RULE_SET_NOT_FOUND");

        assertThat(jdbc.sql("SELECT count(*) FROM rle_decision_outbox").query(Long.class).single()).isEqualTo(events);
        assertThat(jdbc.sql("SELECT status FROM rle_version").query(String.class).single()).isEqualTo("PUBLISHED");
        assertThat(jdbc.sql("SELECT count(*) FROM rle_idempotency WHERE tenant_id = 'bank-b'").query(Long.class).single())
                .isZero();
    }

    /** The host security context: shared, or per thread for the concurrency tests. */
    static final class Caller implements TenantResolver, CallerResolver {
        private record Who(String tenant, String user, Set<String> permissions) {
        }

        private volatile Who shared = new Who("bank-a", "lan", AUTHOR);
        private final ThreadLocal<Who> perThread = new ThreadLocal<>();

        void as(String tenant, String user, Set<String> permissions) {
            shared = new Who(tenant, user, Set.copyOf(permissions));
        }

        void asThread(String tenant, String user, Set<String> permissions) {
            perThread.set(new Who(tenant, user, Set.copyOf(permissions)));
        }

        private Who who() {
            Who mine = perThread.get();
            return mine == null ? shared : mine;
        }

        @Override
        public String currentTenantId() {
            return who().tenant();
        }

        @Override
        public String currentCallerId() {
            return who().user();
        }

        @Override
        public Set<String> currentPermissions() {
            return who().permissions();
        }
    }
}
