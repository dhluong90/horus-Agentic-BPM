package io.github.dhluong90.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionNotFoundException;
import io.github.dhluong90.bpm.rules.api.DecisionRequest;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.service.EngineSettings;
import io.github.dhluong90.bpm.rules.internal.service.RuleEngines;
import io.github.dhluong90.bpm.rules.spi.CallerResolver;
import io.github.dhluong90.bpm.rules.spi.TenantResolver;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
 * RLE-008 and RLE-022 on PostgreSQL (#22): selection by effective date in SQL, the clock in {@code eval_clock}, replay
 * by id through the partitioned decision log on another replica, inputs that survive JSONB, tenant scope, and replay
 * while a newer version is approved. Two engine instances share one fresh database per test.
 */
@Testcontainers
class EffectiveDatesAndReplayPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    static final Instant NOV_20 = Instant.parse("2026-11-20T10:00:00Z");
    static final Instant DEC_01 = Instant.parse("2026-12-01T00:00:00Z");
    static final Instant DEC_05 = Instant.parse("2026-12-05T08:00:00Z");
    static final Set<String> AUTHOR = Set.of("rules:read", "rules:author");
    static final Set<String> APPROVER = Set.of("rules:read", "rules:approve");
    static final Set<String> PRICING = Set.of("rules:evaluate", "rules:simulate", "rules:pin");
    static final Set<String> AUDITOR = Set.of("rules:log:read");

    final Caller caller = new Caller();
    final EditorApiPostgresIT.Movable clock = new EditorApiPostgresIT.Movable(Instant.parse("2026-10-20T00:00:00Z"));
    JdbcClient jdbc;
    RuleEngines podA;
    RuleEngines podB;

    @BeforeEach
    void setUp() {
        String name = "replay_" + System.nanoTime();
        JdbcClient.create(dataSource(POSTGRES.getDatabaseName())).sql("CREATE DATABASE " + name).update();
        DataSource fresh = dataSource(name);
        new RuleEngineSchemaMigrator(fresh).migrate();
        jdbc = JdbcClient.create(fresh);
        podA = engines(fresh);
        podB = engines(fresh);
        // The decision log's monthly partitions, as the starter creates them at start-up.
        new DecisionLogPartitions(fresh, new RuleEngineTransactions(new DataSourceTransactionManager(fresh)))
                .ensureCurrentAndNextMonth(Instant.parse("2026-11-15T00:00:00Z"));
    }

    RuleEngines engines(DataSource db) {
        RuleEngineTransactions transactions = new RuleEngineTransactions(new DataSourceTransactionManager(db));
        RuleEngines engines = PostgresRuleEngines.create(db, transactions, caller, caller, clock, EngineSettings.defaults());
        engines.cursorKeys().load();
        return engines;
    }

    static byte[] cardFee(String effectiveFrom, String fee) {
        return ("""
                schemaVersion: 1
                ruleSet: card-fee
                effectiveFrom: "%s"
                hitPolicy: first
                inputs:
                  - { name: tier, type: string }
                  - { name: balance, type: money }
                  - { name: openedOn, type: date }
                outputs:
                  - { name: fee, type: money }
                  - { name: billedOn, type: date }
                rules:
                  - { id: R1, when: { tier: "== 'GOLD'" }, then: { fee: "0 VND", billedOn: "today()" } }
                  - { id: R2, when: { balance: ">= 50000000 VND" }, then: { fee: "0 VND", billedOn: "today()" } }
                  - { id: R3, when: { openedOn: "> today()" }, then: { fee: "0 VND", billedOn: "today()" } }
                  - { id: R4, when: {}, then: { fee: "%s", billedOn: "today()" } }
                """).formatted(effectiveFrom, fee).getBytes(StandardCharsets.UTF_8);
    }

    void publish(String tenant, byte[] document, Instant approvedAt) {
        caller.as(tenant, "lan", AUTHOR);
        int number = podA.versionService().findRuleSet("card-fee").isPresent()
                ? podA.versionService().importVersion("card-fee", ImportRequest.yaml(document)).version()
                : podA.versionService().importRuleSet(ImportRequest.yaml(document)).version();
        long lock = podA.versionService().requestPublish("card-fee", number, WriteOptions.none()).lockVersion();
        clock.set(approvedAt);
        caller.as(tenant, "minh", APPROVER);
        podA.versionService().approve("card-fee", number, new WriteOptions(null, lock, null));
    }

    static final DecisionInput STANDARD = DecisionInput.of(Map.of("tier", "STANDARD",
            "balance", Money.ofMinor("VND", 1_250_000), "openedOn", LocalDate.of(2024, 3, 9)));

    @Test
    void RLE_008_explicit_clock_resolves_effective_version_and_replays() {
        // Given v1 from 1 Nov at 50,000 VND and v2 from 1 Dec at 30,000 VND
        publish("bank-a", cardFee("2026-11-01T00:00:00Z", "50000 VND"), Instant.parse("2026-10-20T00:00:00Z"));
        publish("bank-a", cardFee("2026-12-01T00:00:00Z", "30000 VND"), Instant.parse("2026-11-25T10:00:00Z"));
        clock.set(Instant.parse("2026-11-26T09:00:00Z"));
        caller.as("bank-a", "pricing", PRICING);

        // When pod A evaluates with clock 20 Nov, then 1 Dec
        DecisionResult november = podA.ruleEngine().evaluate(DecisionRequest.of("card-fee", STANDARD).withClock(NOV_20));
        DecisionResult december = podA.ruleEngine().evaluate(DecisionRequest.of("card-fee", STANDARD).withClock(DEC_01));

        // Then v1 and 50,000 VND, v2 and 30,000 VND; the row keeps the clock apart from when it ran
        assertThat(november.version()).isEqualTo(1);
        assertThat(november.outputs()).containsEntry("fee", Money.ofMinor("VND", 50_000));
        assertThat(december.version()).isEqualTo(2);
        assertThat(december.outputs()).containsEntry("fee", Money.ofMinor("VND", 30_000));
        assertThat(jdbc.sql("SELECT eval_clock FROM rle_decision WHERE id = :id").param("id", november.decisionId())
                .query(java.sql.Timestamp.class).single().toInstant()).isEqualTo(NOV_20);
        assertThat(jdbc.sql("SELECT created_at FROM rle_decision WHERE id = :id").param("id", november.decisionId())
                .query(java.sql.Timestamp.class).single().toInstant()).isEqualTo(Instant.parse("2026-11-26T09:00:00Z"));

        // When pod B replays the November decision after 5 Dec, and pod A evaluates pinned to (v1, 20 Nov)
        clock.set(DEC_05);
        long rows = count("rle_decision");
        caller.as("bank-a", "thu", AUDITOR);
        DecisionResult replayed = podB.ruleEngine().replay(november.decisionId());
        caller.as("bank-a", "pricing", PRICING);
        DecisionResult pinned = podA.ruleEngine().evaluate(DecisionRequest.of("card-fee", STANDARD)
                .withVersion(1).withClock(NOV_20));

        // Then the version, fee and matchedRuleIds are those logged; the money and date inputs came back from JSONB
        assertThat(replayed.decisionId()).isEqualTo(november.decisionId());
        assertThat(replayed.version()).isEqualTo(1);
        assertThat(replayed.clock()).isEqualTo(NOV_20);
        assertThat(replayed.outcome()).isEqualTo(november.outcome());
        assertThat(replayed.matchedRuleIds()).containsExactly("R4");
        assertThat(pinned.outcome()).isEqualTo(november.outcome());
        assertThat(count("rle_decision")).isEqualTo(rows + 1);
        // and the log reads back what kind of decision each row is: chosen clock, pinned, or neither
        assertThat(jdbc.sql("SELECT pinned, eval_clock <> created_at AS clock_chosen FROM rle_decision WHERE id = :id")
                .param("id", pinned.decisionId()).query((rs, n) -> rs.getBoolean(1) + " " + rs.getBoolean(2)).single())
                .isEqualTo("true true");
        assertThat(jdbc.sql("SELECT pinned, eval_clock <> created_at FROM rle_decision WHERE id = :id")
                .param("id", november.decisionId()).query((rs, n) -> rs.getBoolean(1) + " " + rs.getBoolean(2)).single())
                .isEqualTo("false true");
        caller.as("bank-a", "thu", AUDITOR);
        DecisionResult pinnedReplay = podB.ruleEngine().replay(pinned.decisionId());
        assertThat(pinnedReplay.pinned()).isTrue();
        assertThat(pinnedReplay.clockChosen()).isTrue();
        assertThat(replayed.pinned()).isFalse();
        // and the decision-log read of the other pod shows the same marks, so neither looks like a normal decision
        io.github.dhluong90.bpm.rules.api.LoggedDecision readPinned = podB.decisionLog().get(pinned.decisionId());
        io.github.dhluong90.bpm.rules.api.LoggedDecision readNovember = podB.decisionLog().get(november.decisionId());
        assertThat(readPinned.pinned()).isTrue();
        assertThat(readPinned.clockChosen()).isTrue();
        assertThat(readPinned.evalClock()).isEqualTo(NOV_20);
        assertThat(readPinned.evaluatedAt()).isEqualTo(DEC_05);
        assertThat(readNovember.pinned()).isFalse();
        assertThat(readNovember.clockChosen()).isTrue();
        assertThat(readNovember.evalClock()).isEqualTo(NOV_20);
        assertThat(count("rle_decision_outbox")).isEqualTo(count("rle_version") * 3); // lifecycle events only
    }

    @Test
    void RLE_008_replay_finds_a_decision_logged_after_the_engine_clock_went_back() {
        // Given a decision logged at 5 Dec, then the host clock set back to 26 Nov and another decision logged, whose
        // ULID keeps the later millisecond (ids are monotonic within the process)
        publish("bank-a", cardFee("2026-11-01T00:00:00Z", "50000 VND"), Instant.parse("2026-10-20T00:00:00Z"));
        caller.as("bank-a", "pricing", PRICING);
        clock.set(DEC_05);
        podA.ruleEngine().evaluate(DecisionRequest.of("card-fee", STANDARD));
        clock.set(Instant.parse("2026-11-26T09:00:00Z"));
        DecisionResult earlier = podA.ruleEngine().evaluate(DecisionRequest.of("card-fee", STANDARD));

        // When it is replayed, Then the second lookup finds it
        caller.as("bank-a", "thu", AUDITOR);
        DecisionResult replayed = podB.ruleEngine().replay(earlier.decisionId());
        assertThat(replayed.outcome()).isEqualTo(earlier.outcome());
        assertThat(replayed.clock()).isEqualTo(Instant.parse("2026-11-26T09:00:00Z"));
    }

    @Test
    void RLE_008_replay_of_another_tenants_decision_is_not_found_and_writes_nothing() {
        publish("bank-a", cardFee("2026-11-01T00:00:00Z", "50000 VND"), Instant.parse("2026-10-20T00:00:00Z"));
        caller.as("bank-a", "pricing", PRICING);
        String bankA = podA.ruleEngine().evaluate(DecisionRequest.of("card-fee", STANDARD).withClock(NOV_20)).decisionId();
        long rows = count("rle_decision");

        caller.as("bank-b", "thu", AUDITOR);
        assertThatThrownBy(() -> podB.ruleEngine().replay(bankA)).isInstanceOf(DecisionNotFoundException.class);
        assertThat(count("rle_decision")).isEqualTo(rows);
    }

    @Test
    void RLE_008_replay_keeps_its_version_while_a_newer_version_is_approved_at_the_same_moment() throws Exception {
        // Given the November decision of v1
        publish("bank-a", cardFee("2026-11-01T00:00:00Z", "50000 VND"), Instant.parse("2026-10-20T00:00:00Z"));
        caller.as("bank-a", "pricing", PRICING);
        DecisionResult november = podA.ruleEngine().evaluate(DecisionRequest.of("card-fee", STANDARD).withClock(NOV_20));
        caller.as("bank-a", "lan", AUTHOR);
        podA.versionService().importVersion("card-fee", ImportRequest.yaml(cardFee("2026-11-02T00:00:00Z", "10000 VND")));
        long lock = podA.versionService().requestPublish("card-fee", 2, WriteOptions.none()).lockVersion();
        clock.set(DEC_05);

        // When pod A approves v2 (in force at once) while pod B replays the November decision twenty times
        CyclicBarrier together = new CyclicBarrier(2);
        List<DecisionResult> replays = new ArrayList<>();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> approve = pool.submit(() -> {
                caller.asThread("bank-a", "minh", APPROVER);
                together.await();
                podA.versionService().approve("card-fee", 2, new WriteOptions(null, lock, null));
                return null;
            });
            Future<List<DecisionResult>> replay = pool.submit(() -> {
                caller.asThread("bank-a", "thu", AUDITOR);
                together.await();
                List<DecisionResult> results = new ArrayList<>();
                for (int i = 0; i < 20; i++) {
                    results.add(podB.ruleEngine().replay(november.decisionId()));
                }
                return results;
            });
            approve.get();
            replays.addAll(replay.get());
        }

        // Then every replay is v1 at 50,000 VND, before, during and after the approval
        assertThat(replays).hasSize(20).allSatisfy(r -> {
            assertThat(r.version()).isEqualTo(1);
            assertThat(r.outcome()).isEqualTo(november.outcome());
        });
        caller.as("bank-a", "pricing", PRICING);
        assertThat(podB.ruleEngine().evaluate(DecisionRequest.of("card-fee", STANDARD)).version()).isEqualTo(2);
    }

    @Test
    void RLE_022_today_uses_rule_set_time_zone() {
        // Given same-day-cutoff v1 in Asia/Ho_Chi_Minh and v2 in UTC
        String cutoff = """
                schemaVersion: 1
                ruleSet: same-day-cutoff
                %s
                hitPolicy: first
                inputs:
                  - { name: txDate, type: date }
                outputs:
                  - { name: sameDay, type: boolean }
                rules:
                  - { id: R1, when: { txDate: "== today()" }, then: { sameDay: true } }
                """;
        Instant at = Instant.parse("2027-01-15T18:30:00Z");
        DecisionInput tx = DecisionInput.of(Map.of("txDate", LocalDate.of(2027, 1, 16)));
        List<DecisionResult> results = new ArrayList<>();

        // When each is evaluated with txDate 2027-01-16 at 2027-01-15T18:30Z while it is the version in force (v2's
        // approval retires v1, and a retired version is not pinned by default)
        for (String zone : new String[] {"timeZone: Asia/Ho_Chi_Minh", ""}) {
            caller.as("bank-a", "lan", AUTHOR);
            byte[] document = cutoff.formatted(zone).getBytes(StandardCharsets.UTF_8);
            int number = podA.versionService().findRuleSet("same-day-cutoff").isPresent()
                    ? podA.versionService().importVersion("same-day-cutoff", ImportRequest.yaml(document)).version()
                    : podA.versionService().importRuleSet(ImportRequest.yaml(document)).version();
            long lock = podA.versionService().requestPublish("same-day-cutoff", number, WriteOptions.none())
                    .lockVersion();
            caller.as("bank-a", "minh", APPROVER);
            podA.versionService().approve("same-day-cutoff", number, new WriteOptions(null, lock, null));
            caller.as("bank-a", "pricing", PRICING);
            results.add(podA.ruleEngine().evaluate(DecisionRequest.of("same-day-cutoff", tx).withVersion(number)
                    .withClock(at)));
        }
        DecisionResult v1 = results.get(0);
        DecisionResult v2 = results.get(1);

        // Then v1 matches and v2 does not; replaying either on pod B gives the same
        assertThat(v1.matchedRuleIds()).containsExactly("R1");
        assertThat(v2.outcome()).isInstanceOf(DecisionResult.NoMatch.class);
        caller.as("bank-a", "thu", AUDITOR);
        assertThat(podB.ruleEngine().replay(v1.decisionId()).outcome()).isEqualTo(v1.outcome());
        assertThat(podB.ruleEngine().replay(v2.decisionId()).outcome()).isEqualTo(v2.outcome());
    }

    static DataSource dataSource(String database) {
        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database));
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }

    long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    /** The host security context: shared by default, or per thread for the concurrency test. */
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
