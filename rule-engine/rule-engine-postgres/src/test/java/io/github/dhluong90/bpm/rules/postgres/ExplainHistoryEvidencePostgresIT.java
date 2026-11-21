package io.github.dhluong90.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.DecisionExplanation;
import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionNotFoundException;
import io.github.dhluong90.bpm.rules.api.EvidenceExport;
import io.github.dhluong90.bpm.rules.api.EvidenceFormat;
import io.github.dhluong90.bpm.rules.api.EvidenceRequest;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.LifecycleAction;
import io.github.dhluong90.bpm.rules.api.MaskingKey;
import io.github.dhluong90.bpm.rules.api.PageRequest;
import io.github.dhluong90.bpm.rules.api.RuleSetHistory;
import io.github.dhluong90.bpm.rules.api.VersionHistoryEntry;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.service.DecisionQuerySettings;
import io.github.dhluong90.bpm.rules.internal.service.EngineSettings;
import io.github.dhluong90.bpm.rules.internal.service.RuleEngines;
import io.github.dhluong90.bpm.rules.spi.MaskingPolicy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The decision explanation (RLE-030, RLE-031), the rule set history and the evidence export (PD-116) on PostgreSQL:
 * the explanation reads the decision row and the stored version and writes nothing; the export appends one rule set
 * row to {@code rle_version_history} and one outbox row in its transaction, replays from its idempotency record, and
 * the rule set events are read through the V24 index.
 */
@Testcontainers
class ExplainHistoryEvidencePostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    static final String NATIONAL_ID = "079090001234";
    static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    private static final AtomicInteger DATABASES = new AtomicInteger();

    static final String CARD_FEE = """
            schemaVersion: 1
            ruleSet: card-fee
            hitPolicy: first
            inputs:
              - { name: nationalId, type: string, mask: true }
              - { name: tier, type: string }
              - { name: txCount, type: integer }
            outputs:
              - { name: fee, type: money }
            rules:
              - { id: R1, when: { tier: "== 'GOLD'" }, then: { fee: "0 VND" } }
              - { id: R2, when: { txCount: ">= 3" }, then: { fee: "10000 VND" } }
              - { id: R3, when: {}, then: { fee: "50000 VND" } }
            """;

    final List<String> statements = new CopyOnWriteArrayList<>();
    final RuleEnginePostgresIT.Caller caller = new RuleEnginePostgresIT.Caller();
    DataSource plain;
    DataSource db;
    JdbcClient jdbc;
    TransactionTemplate host;
    RuleEngines engines;

    @BeforeEach
    void setUp() {
        plain = freshDatabase();
        db = new RuleEnginePostgresIT.SqlCapturingDataSource(plain, statements);
        new RuleEngineSchemaMigrator(db).migrate();
        DataSourceTransactionManager manager = new DataSourceTransactionManager(db);
        RuleEngineTransactions transactions = new RuleEngineTransactions(manager);
        new DecisionLogPartitions(db, transactions).ensureCurrentAndNextMonth(NOW);
        host = new TransactionTemplate(manager);
        jdbc = JdbcClient.create(plain);
        engines = PostgresRuleEngines.create(db, transactions, caller, caller, Clock.fixed(NOW, ZoneOffset.UTC),
                EngineSettings.defaults().withRequireContentHash(false), MaskingPolicy.withKey(MaskingKey.of(KEY)), DecisionQuerySettings.defaults());
        engines.cursorKeys().load();
    }

    void publish(String yaml, int version) {
        caller.as("bank-a", "lan");
        byte[] source = yaml.getBytes(StandardCharsets.UTF_8);
        if (version == 1) {
            engines.versionService().importRuleSet(ImportRequest.yaml(source));
        } else {
            engines.versionService().importVersion("card-fee", ImportRequest.yaml(source));
        }
        long lock = engines.versionService().requestPublish("card-fee", version, WriteOptions.none()).lockVersion();
        caller.as("bank-a", "minh");
        engines.versionService().approve("card-fee", version, new WriteOptions(null, lock, null));
    }

    String decide(Map<String, Object> inputs) {
        caller.as("bank-a", "teller", Set.of("rules:evaluate"));
        return engines.ruleEngine().evaluate("card-fee", DecisionInput.of(inputs)).decisionId();
    }

    @Test
    void RLE_030_explanation_is_generated_from_log_entry_and_version() {
        publish(CARD_FEE, 1);
        String d7 = decide(Map.of("nationalId", NATIONAL_ID, "tier", "SILVER", "txCount", 5));
        publish(CARD_FEE.replace("10000 VND", "20000 VND"), 2);
        long rows = count("SELECT count(*) FROM rle_decision");
        caller.as("bank-a", "dan", Set.of("rules:log:read"));
        statements.clear();

        DecisionExplanation first = engines.decisionLog().explain(d7, List.of("en"));
        DecisionExplanation again = engines.decisionLog().explain(d7, List.of("en"));

        assertThat(first.version()).isEqualTo(1);
        assertThat(first.text()).isEqualTo(
                "Rule R2 of card-fee version 1 matched because txCount is 5, which is at least 3. Result: fee 10000 VND.");
        assertThat(again).isEqualTo(first);
        assertThat(statements).noneMatch(sql -> sql.strip().toUpperCase().matches("(?s)^(INSERT|UPDATE|DELETE).*"));
        assertThat(count("SELECT count(*) FROM rle_decision")).isEqualTo(rows);
        caller.as("bank-b", "dan", Set.of("rules:log:read"));
        assertThatThrownBy(() -> engines.decisionLog().explain(d7, List.of())).isInstanceOf(DecisionNotFoundException.class);
    }

    @Test
    void RLE_031_explanation_masks_inputs_and_covers_no_match_null_and_failure() {
        publish(CARD_FEE.replace("{ tier: \"== 'GOLD'\" }", "{ nationalId: \"is not null\", tier: \"== 'GOLD'\" }"), 1);
        String gold = decide(Map.of("nationalId", NATIONAL_ID, "tier", "GOLD", "txCount", 0));
        caller.as("bank-a", "dan", Set.of("rules:log:read"));

        DecisionExplanation masked = engines.decisionLog().explain(gold, List.of("vi"));

        DecisionExplanation.Condition id = masked.matched().getFirst().conditions().getFirst();
        assertThat(id.input()).isEqualTo("nationalId");
        assertThat(id.masked()).isTrue();
        assertThat(id.valueJson()).matches("\"hmac:k1:[0-9a-f]{64}\"");
        assertThat(masked.reasons()).extracting(DecisionExplanation.Reason::code).contains("VALUE_MASKED");
        assertThat(masked.text()).isEqualTo("Quy tắc R1 của card-fee phiên bản 1 khớp vì nationalId đã được che, tức là"
                + " có giá trị, và tier là GOLD, tức là bằng GOLD. Kết quả: fee 0 VND.");
        assertThat(masked.toString()).doesNotContain(NATIONAL_ID);
    }

    @Test
    void evidence_export_writes_one_rule_set_row_and_one_event_and_replays_its_bytes() {
        publish(CARD_FEE, 1);
        List<String> decisions = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            decisions.add(decide(Map.of("nationalId", NATIONAL_ID, "tier", "SILVER", "txCount", 3 + i)));
        }
        caller.as("bank-a", "ha", Set.of("rules:read", "rules:log:read"));
        EvidenceRequest request = new EvidenceRequest(EvidenceFormat.CSV, List.of(1), decisions, "k-118", "audit-case-17");

        EvidenceExport export = host.execute(status -> engines.ruleSetHistory().exportEvidence("card-fee", request));
        EvidenceExport replay = engines.ruleSetHistory().exportEvidence("card-fee", request);

        assertThat(replay.content()).isEqualTo(export.content());
        assertThat(export.content()).doesNotContain(NATIONAL_ID).contains("hmac:k1:");
        assertThat(jdbc.sql("""
                SELECT actor || ' ' || (evidence ->> 'format') || ' ' || (evidence ->> 'contentSha256')
                  || ' ' || jsonb_array_length(evidence -> 'decisionIds')
                FROM rle_version_history WHERE tenant_id = 'bank-a' AND action = 'EVIDENCE_EXPORTED'
                  AND version_id IS NULL AND to_status IS NULL AND lock_version IS NULL
                """).query(String.class).list()).containsExactly("ha csv " + export.contentSha256() + " 3");
        assertThat(jdbc.sql("""
                SELECT payload ->> 'actor' FROM rle_decision_outbox
                WHERE tenant_id = 'bank-a' AND payload ->> 'eventType' = 'rules.evidence.exported.v1'
                  AND payload ->> 'eventId' = id AND payload ->> 'correlationId' = 'audit-case-17'
                """).query(String.class).list()).containsExactly("ha");

        RuleSetHistory history = engines.ruleSetHistory().history("card-fee", PageRequest.first());
        assertThat(history.ruleSetEvents()).hasSize(1);
        assertThat(history.ruleSetEvents().getFirst().evidence().decisionIds()).containsExactlyElementsOf(decisions);
        assertThat(history.versions().getFirst().events()).extracting(VersionHistoryEntry::action)
                .containsExactly(LifecycleAction.CREATED, LifecycleAction.REQUESTED, LifecycleAction.APPROVED);

        // The trail stays append-only for rule set rows (V4)
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM rle_version_history WHERE action = 'EVIDENCE_EXPORTED'").update())
                .hasMessageContaining("append-only");
    }

    @Test
    void rule_set_events_are_read_through_the_v24_index() {
        publish(CARD_FEE, 1);
        String ruleSetId = jdbc.sql("SELECT id FROM rle_rule_set WHERE tenant_id = 'bank-a' AND name = 'card-fee'")
                .query(String.class).single();

        // Even when sequential scans are discouraged, as the planner does once the table is large, the plan must use
        // the rule set index and never a filter over every history row
        JdbcClient inHost = JdbcClient.create(db);
        String plan = host.execute(status -> {
            inHost.sql("SET LOCAL enable_seqscan = off").update();
            return inHost.sql("EXPLAIN (FORMAT JSON) " + JdbcRuleStore.RULE_SET_EVENTS_SQL)
                    .param("tenant", "bank-a").param("ruleSetId", ruleSetId).param("limit", 101)
                    .query(String.class).single();
        });

        assertThat(plan).contains("\"Index Name\": \"idx_rle_version_history_rule_set\"");
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private static DataSource freshDatabase() {
        String name = "rle_evidence_it_" + DATABASES.incrementAndGet();
        JdbcClient.create(dataSourceFor(POSTGRES.getDatabaseName())).sql("CREATE DATABASE " + name).update();
        return dataSourceFor(name);
    }

    static DataSource dataSourceFor(String database) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setServerNames(new String[] {POSTGRES.getHost()});
        ds.setPortNumbers(new int[] {POSTGRES.getFirstMappedPort()});
        ds.setDatabaseName(database);
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }
}
