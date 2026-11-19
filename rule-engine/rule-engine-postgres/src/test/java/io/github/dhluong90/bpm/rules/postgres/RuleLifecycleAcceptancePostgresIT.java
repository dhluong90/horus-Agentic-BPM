package io.github.dhluong90.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.InputInvalidException;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.json.Hashes;
import io.github.dhluong90.bpm.rules.internal.service.RuleEngines;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
 * Acceptance tests of issue #1 on PostgreSQL: decision rows per committed evaluation, rollback of
 * every write with the host, the source document through a TEXT column, and tenant scope of rows and
 * idempotency keys. One fresh database per test; clock fixed at 15 December 2026, 09:00 ICT.
 */
@Testcontainers
class RuleLifecycleAcceptancePostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static final Instant NOW = Instant.parse("2026-12-15T02:00:00Z");
    private static final AtomicInteger DATABASES = new AtomicInteger();

    final List<String> statements = new CopyOnWriteArrayList<>();
    final RuleEnginePostgresIT.Caller caller = new RuleEnginePostgresIT.Caller();
    DataSource db;
    JdbcClient jdbc;
    TransactionTemplate host;
    RuleEngines engines;

    @BeforeEach
    void setUp() {
        db = new RuleEnginePostgresIT.SqlCapturingDataSource(freshDatabase(), statements);
        new RuleEngineSchemaMigrator(db).migrate();
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(db);
        RuleEngineTransactions transactions = new RuleEngineTransactions(transactionManager);
        new DecisionLogPartitions(db, transactions).ensureCurrentAndNextMonth(NOW);
        host = new TransactionTemplate(transactionManager);
        jdbc = JdbcClient.create(db);
        engines = PostgresRuleEngines.create(db, transactions, caller, caller, Clock.fixed(NOW, ZoneOffset.UTC),
                io.github.dhluong90.bpm.rules.internal.service.EngineSettings.defaults());
    }

    @Test
    void RLE_013_each_committed_evaluation_inserts_exactly_one_row() {
        // Given
        publish("bank-a", RuleEnginePostgresIT.cardFee());
        caller.as("bank-a", "teller-01");

        // When three evaluations commit in one host transaction
        statements.clear();
        host.executeWithoutResult(status -> {
            engines.ruleEngine().evaluate("card-fee", DecisionInput.of(Map.of("tier", "GOLD", "txCount", 5)));
            engines.ruleEngine().evaluate("card-fee", DecisionInput.of(Map.of("tier", "SILVER", "txCount", 3)));
            engines.ruleEngine().evaluate("card-fee", DecisionInput.of(Map.of("tier", "SILVER", "txCount", 0)));
        });

        // Then three rows and three inserts, one per evaluation
        assertThat(jdbc.sql("SELECT string_agg(matched_rule_ids::text || outputs::text, ' ' ORDER BY matched_rule_ids::text) FROM rle_decision")
                .query(String.class).single())
                .isEqualTo("[\"R1\"]{\"fee\": {\"amount\": 0, \"currency\": \"VND\"}} "
                        + "[\"R2\"]{\"fee\": {\"amount\": 10000, \"currency\": \"VND\"}} "
                        + "[\"R3\"]{\"fee\": {\"amount\": 50000, \"currency\": \"VND\"}}");
        assertThat(statements).filteredOn(sql -> sql.strip().toUpperCase().startsWith("INSERT INTO RLE_DECISION ")
                || sql.strip().toUpperCase().startsWith("INSERT INTO RLE_DECISION(")).hasSize(3);
    }

    /**
     * A host that catches a refused evaluation (wrong input, unknown rule set, no effective version)
     * and goes on, for example with a default fee, must still commit its own work. The refusal writes
     * nothing, so it must not mark the host transaction rollback-only.
     */
    @Test
    void TR_EMB_04_a_refused_evaluation_that_the_host_handles_does_not_doom_the_host_transaction() {
        // Given
        publish("bank-a", RuleEnginePostgresIT.cardFee());
        caller.as("bank-a", "teller-01");
        jdbc.sql("CREATE TABLE host_posting (id INT PRIMARY KEY, fee BIGINT NOT NULL)").update();

        // When the host evaluates with a bad input, catches the refusal, charges a default fee and commits
        host.executeWithoutResult(status -> {
            long fee;
            try {
                engines.ruleEngine().evaluate("card-fee", DecisionInput.of(Map.of("tier", "GOLD", "txCount", "five")));
                fee = -1;
            } catch (InputInvalidException refused) {
                fee = 50_000;
            }
            jdbc.sql("INSERT INTO host_posting (id, fee) VALUES (1, :fee)").param("fee", fee).update();
        });

        // Then the host's posting is committed and no decision row exists
        assertThat(jdbc.sql("SELECT fee FROM host_posting WHERE id = 1").query(Long.class).single()).isEqualTo(50_000L);
        assertThat(count("rle_decision")).isZero();
    }

    @Test
    void RLE_013_a_rolled_back_host_transaction_leaves_no_row_of_any_number_of_evaluations() {
        publish("bank-a", RuleEnginePostgresIT.cardFee());
        caller.as("bank-a", "teller-01");

        assertThatThrownBy(() -> host.executeWithoutResult(status -> {
            for (int i = 0; i < 5; i++) {
                engines.ruleEngine().evaluate("card-fee", DecisionInput.of(Map.of("tier", "GOLD", "txCount", i)));
            }
            status.setRollbackOnly();
            throw new IllegalStateException("posting failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(count("rle_decision")).isZero();
    }

    @Test
    void TR_EMB_04_import_and_request_roll_back_with_the_host_transaction_events_included() {
        assertThatThrownBy(() -> host.executeWithoutResult(status -> {
            engines.versionService().importRuleSet(ImportRequest.yaml(RuleEnginePostgresIT.cardFee()).withIdempotencyKey("k-1"));
            engines.versionService().requestPublish("card-fee", 1, WriteOptions.none());
            throw new IllegalStateException("host failed after the request");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(count("rle_rule_set")).isZero();
        assertThat(count("rle_version")).isZero();
        assertThat(count("rle_rule")).isZero();
        assertThat(count("rle_decision_outbox")).isZero();
        assertThat(count("rle_idempotency")).isZero();
        assertThat(engines.versionService().importRuleSet(ImportRequest.yaml(RuleEnginePostgresIT.cardFee())
                .withIdempotencyKey("k-1")).version()).isEqualTo(1);
    }

    @Test
    void RLE_020_source_with_bom_crlf_and_vietnamese_comments_round_trips_through_postgresql_byte_for_byte() {
        // Given the card-fee file as a Windows editor saves it
        String unix = new String(RuleEnginePostgresIT.cardFee(), StandardCharsets.UTF_8);
        byte[] windows = ("﻿# Phí thẻ hằng tháng – áp dụng từ 01/12\r\n" + unix.replace("\n", "\r\n"))
                .getBytes(StandardCharsets.UTF_8);

        // When
        engines.versionService().importRuleSet(ImportRequest.yaml(windows));

        // Then
        byte[] stored = jdbc.sql("SELECT source_document FROM rle_version WHERE tenant_id = 'bank-a'")
                .query(String.class).single().getBytes(StandardCharsets.UTF_8);
        assertThat(stored).isEqualTo(windows);
        assertThat(jdbc.sql("SELECT source_hash FROM rle_version WHERE tenant_id = 'bank-a'").query(String.class).single())
                .isEqualTo(Hashes.sha256(windows));
        assertThat(jdbc.sql("SELECT canonical_hash FROM rle_version WHERE tenant_id = 'bank-a'").query(String.class).single())
                .isEqualTo(Hashes.sha256(CARD_FEE_CANONICAL));
        assertThat(engines.versionService().find("card-fee", 1, true).orElseThrow().sourceDocument()
                .getBytes(StandardCharsets.UTF_8)).isEqualTo(windows);
    }

    @Test
    void RLE_011_two_tenants_with_the_same_rule_set_name_get_their_own_versions_and_decision_rows() {
        // Given both tenants publish card-fee; tenant-b charges 7 VND to Gold
        publish("bank-a", RuleEnginePostgresIT.cardFee());
        publish("tenant-b", new String(RuleEnginePostgresIT.cardFee(), StandardCharsets.UTF_8)
                .replace("\"0 VND\"", "\"7 VND\"").getBytes(StandardCharsets.UTF_8));

        // When each tenant evaluates Gold
        caller.as("bank-a", "teller-a");
        engines.ruleEngine().evaluate("card-fee", DecisionInput.of(Map.of("tier", "GOLD")));
        caller.as("tenant-b", "teller-b");
        engines.ruleEngine().evaluate("card-fee", DecisionInput.of(Map.of("tier", "GOLD")));

        // Then each row carries its own tenant, caller and fee
        assertThat(jdbc.sql("SELECT string_agg(tenant_id || ' ' || caller_id || ' ' || (outputs->'fee'->>'amount'), ', ' "
                + "ORDER BY tenant_id) FROM rle_decision").query(String.class).single())
                .isEqualTo("bank-a teller-a 0, tenant-b teller-b 7");
        assertThat(jdbc.sql("SELECT count(DISTINCT rule_set_id) FROM rle_version").query(Long.class).single()).isEqualTo(2L);
    }

    @Test
    void RLE_014_the_same_idempotency_key_in_two_tenants_creates_two_versions() {
        caller.as("bank-a", "alice");
        engines.versionService().importRuleSet(ImportRequest.yaml(RuleEnginePostgresIT.cardFee()).withIdempotencyKey("k-101"));
        caller.as("tenant-b", "carol");
        engines.versionService().importRuleSet(ImportRequest.yaml(RuleEnginePostgresIT.cardFee()).withIdempotencyKey("k-101"));

        assertThat(jdbc.sql("SELECT string_agg(tenant_id, ',' ORDER BY tenant_id) FROM rle_version").query(String.class).single())
                .isEqualTo("bank-a,tenant-b");
        assertThat(count("rle_idempotency")).isEqualTo(2);
    }

    /** RFC 8785 form of card-fee.yaml, as in the core tests: members sorted, comments gone. */
    static final String CARD_FEE_CANONICAL = "{\"description\":\"Monthly card fee by tier and transaction count\","
            + "\"hitPolicy\":\"first\",\"inputs\":[{\"name\":\"tier\",\"type\":\"string\"},"
            + "{\"description\":\"card transactions in the month\",\"name\":\"txCount\",\"type\":\"integer\"}],"
            + "\"outputs\":[{\"name\":\"fee\",\"type\":\"money\"}],\"ruleSet\":\"card-fee\","
            + "\"rules\":[{\"id\":\"R1\",\"then\":{\"fee\":\"0 VND\"},\"when\":{\"tier\":\"== 'GOLD'\"}},"
            + "{\"id\":\"R2\",\"then\":{\"fee\":\"10000 VND\"},\"when\":{\"txCount\":\">= 3\"}},"
            + "{\"id\":\"R3\",\"then\":{\"fee\":\"50000 VND\"},\"when\":{}}],\"schemaVersion\":1}";

    private void publish(String tenant, byte[] yaml) {
        caller.as(tenant, tenant + "-maker");
        engines.versionService().importRuleSet(ImportRequest.yaml(yaml));
        engines.versionService().requestPublish("card-fee", 1, WriteOptions.none());
        caller.as(tenant, tenant + "-checker");
        engines.versionService().approve("card-fee", 1, WriteOptions.none());
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private static DataSource freshDatabase() {
        String name = "rle_acceptance_it_" + DATABASES.incrementAndGet();
        JdbcClient.create(dataSourceFor(POSTGRES.getDatabaseName())).sql("CREATE DATABASE " + name).update();
        return dataSourceFor(name);
    }

    private static DataSource dataSourceFor(String database) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setServerNames(new String[] {POSTGRES.getHost()});
        ds.setPortNumbers(new int[] {POSTGRES.getFirstMappedPort()});
        ds.setDatabaseName(database);
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }
}
