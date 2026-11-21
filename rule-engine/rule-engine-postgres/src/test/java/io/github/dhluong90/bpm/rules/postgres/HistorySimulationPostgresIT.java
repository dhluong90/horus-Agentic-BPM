package io.github.dhluong90.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionRequest;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.HistorySimulation;
import io.github.dhluong90.bpm.rules.api.HistorySimulationResult;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.VersionNotFoundException;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.service.EngineSettings;
import io.github.dhluong90.bpm.rules.internal.service.RuleEngines;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * RLE-015 on PostgreSQL (TR-OBS-01): a draft replayed over the November decisions of the partitioned decision log, on
 * a replica whose every statement is captured. The tellers' replica logs 100 card fee decisions, 18 of them for
 * STANDARD customers; draft v2 lowers their fee from 50,000 VND to 30,000 VND.
 */
@Testcontainers
class HistorySimulationPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    static final Instant NOV_01 = Instant.parse("2026-11-01T00:00:00Z");
    static final Instant DEC_01 = Instant.parse("2026-12-01T00:00:00Z");
    static final Set<String> AUTHOR = Set.of("rules:read", "rules:author");
    static final Set<String> APPROVER = Set.of("rules:read", "rules:approve");
    static final Set<String> TELLER = Set.of("rules:evaluate");
    static final Set<String> REPLAYER = Set.of("rules:simulate", "rules:log:read");

    final EffectiveDatesAndReplayPostgresIT.Caller caller = new EffectiveDatesAndReplayPostgresIT.Caller();
    final EditorApiPostgresIT.Movable clock = new EditorApiPostgresIT.Movable(Instant.parse("2026-10-20T00:00:00Z"));
    final List<String> statements = new CopyOnWriteArrayList<>();
    JdbcClient jdbc;
    RuleEngines tellers;
    RuleEngines approvers;

    @BeforeEach
    void setUp() {
        String name = "history_" + System.nanoTime();
        JdbcClient.create(dataSource(POSTGRES.getDatabaseName()))
                .sql("CREATE DATABASE " + name).update();
        DataSource fresh = dataSource(name);
        new RuleEngineSchemaMigrator(fresh).migrate();
        jdbc = JdbcClient.create(fresh);
        tellers = engines(fresh);
        approvers = engines(new RuleEnginePostgresIT.SqlCapturingDataSource(fresh, statements));
        new DecisionLogPartitions(fresh, new RuleEngineTransactions(new DataSourceTransactionManager(fresh)))
                .ensureCurrentAndNextMonth(Instant.parse("2026-11-15T00:00:00Z"));
    }

    RuleEngines engines(DataSource db) {
        RuleEngineTransactions transactions = new RuleEngineTransactions(new DataSourceTransactionManager(db));
        RuleEngines engines = PostgresRuleEngines.create(db, transactions, caller, caller, clock, EngineSettings.defaults());
        engines.cursorKeys().load();
        return engines;
    }

    static byte[] cardFee(String fee) {
        return ("""
                schemaVersion: 1
                ruleSet: card-fee
                effectiveFrom: "2026-11-01T00:00:00Z"
                hitPolicy: first
                inputs:
                  - { name: tier, type: string }
                  - { name: balance, type: money }
                outputs:
                  - { name: fee, type: money }
                  - { name: billedOn, type: date }
                rules:
                  - { id: R1, when: { tier: "== 'GOLD'" }, then: { fee: "0 VND", billedOn: "today()" } }
                  - { id: R2, when: {}, then: { fee: "%s", billedOn: "today()" } }
                """).formatted(fee).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    void givenNovemberHistoryAndDraft(String tenant) {
        caller.as(tenant, "lan", AUTHOR);
        tellers.versionService().importRuleSet(ImportRequest.yaml(cardFee("50000 VND")));
        long lock = tellers.versionService().requestPublish("card-fee", 1, WriteOptions.none()).lockVersion();
        caller.as(tenant, "minh", APPROVER);
        tellers.versionService().approve("card-fee", 1, new WriteOptions(null, lock, null));
        caller.as(tenant, "teller", TELLER);
        for (int i = 0; i < 100; i++) {
            clock.set(NOV_01.plus(Duration.ofHours(7 * i + 1)));
            String tier = (i % 6 == 0 || i == 99) ? "STANDARD" : "GOLD";
            tellers.ruleEngine().evaluate(DecisionRequest.of("card-fee", DecisionInput.of(Map.of("tier", tier,
                    "balance", Money.ofMinor("VND", 1_250_000)))));
        }
        clock.set(Instant.parse("2026-12-15T02:00:00Z"));
        caller.as(tenant, "lan", AUTHOR);
        tellers.versionService().importVersion("card-fee", ImportRequest.yaml(cardFee("30000 VND")));
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

    @Test
    void RLE_015_simulation_leaves_decision_log_untouched() {
        // Given
        givenNovemberHistoryAndDraft("bank-a");
        long decisions = count("rle_decision");
        long outbox = count("rle_decision_outbox");
        long history = count("rle_version_history");
        long idempotency = count("rle_idempotency");
        caller.as("bank-a", "pricing-approver", REPLAYER);
        statements.clear();

        // When v2 is replayed over November on the approvers' replica
        HistorySimulationResult result = approvers.ruleEngine().simulateHistory("card-fee", 2,
                HistorySimulation.between(NOV_01, DEC_01));

        // Then 18 of 100 outcomes change, and 0 rows are written to rle_decision or anywhere else
        assertThat(decisions).isEqualTo(100);
        assertThat(result.replayedCount()).isEqualTo(100);
        assertThat(result.changedCount()).isEqualTo(18);
        assertThat(count("rle_decision")).isEqualTo(decisions);
        assertThat(count("rle_decision_outbox")).isEqualTo(outbox);
        assertThat(count("rle_version_history")).isEqualTo(history);
        assertThat(count("rle_idempotency")).isEqualTo(idempotency);
        Pattern write = Pattern.compile("(?is).*\\b(INSERT|UPDATE|DELETE|MERGE|TRUNCATE|DROP|ALTER|CREATE)\\b.*");
        assertThat(statements).isNotEmpty().noneMatch(sql -> write.matcher(sql).matches());
        // One read of the decision log, filtered by tenant, rule set and the period on the partition key
        assertThat(statements).filteredOn(sql -> sql.contains("FROM rle_decision")).hasSize(1).first()
                .satisfies(sql -> assertThat(sql).contains("tenant_id = ?", "rule_set_name = ?", "created_at >= ?",
                        "created_at < ?"));
        // The values survive JSONB: money and the date of the logged clock
        HistorySimulationResult.Item newest = result.items().getFirst();
        assertThat(newest.simulated()).isEqualTo(new DecisionResult.Matched(List.of("R2"),
                Map.of("fee", Money.ofMinor("VND", 30_000), "billedOn", LocalDate.of(2026, 11, 29))));
        assertThat(newest.logged()).isEqualTo(new DecisionResult.Matched(List.of("R2"),
                Map.of("fee", Money.ofMinor("VND", 50_000), "billedOn", LocalDate.of(2026, 11, 29))));
    }

    @Test
    void RLE_015_another_tenant_is_refused_and_nothing_is_read_or_written() {
        // Given bank-a's November history
        givenNovemberHistoryAndDraft("bank-a");
        caller.as("bank-b", "carol", REPLAYER);
        statements.clear();

        // When bank-b asks for card-fee v2
        assertThatThrownBy(() -> approvers.ruleEngine().simulateHistory("card-fee", 2,
                HistorySimulation.between(NOV_01, DEC_01))).isInstanceOf(VersionNotFoundException.class);

        // Then no decision was read and nothing was written
        assertThat(statements).noneMatch(sql -> sql.contains("FROM rle_decision"));
        Pattern write = Pattern.compile("(?is).*\\b(INSERT|UPDATE|DELETE)\\b.*");
        assertThat(statements).noneMatch(sql -> write.matcher(sql).matches());
        assertThat(count("rle_decision")).isEqualTo(100);
    }
}
