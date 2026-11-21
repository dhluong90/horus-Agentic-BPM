package io.github.dhluong90.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.DraftValidation;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.InvalidStateException;
import io.github.dhluong90.bpm.rules.api.RuleSetNotFoundException;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.service.EngineSettings;
import io.github.dhluong90.bpm.rules.internal.service.RuleEngines;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * {@code :validate} on PostgreSQL (RLE-090): valid and invalid tables, a published version and another tenant are all
 * answered, and not one row of any {@code rle_} table changes.
 */
@Testcontainers
class ValidateDraftPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-12-01T00:00:00Z"), ZoneOffset.UTC);

    final DraftReplaceAndDiffPostgresIT.PerThreadCaller who = new DraftReplaceAndDiffPostgresIT.PerThreadCaller();
    JdbcClient jdbc;
    VersionService versions;

    @BeforeEach
    void setUp() {
        DataSource db = dataSource(POSTGRES.getDatabaseName());
        String name = "validate_" + System.nanoTime();
        JdbcClient.create(db).sql("CREATE DATABASE " + name).update();
        db = dataSource(name);
        new RuleEngineSchemaMigrator(db).migrate();
        jdbc = JdbcClient.create(db);
        RuleEngines engines = PostgresRuleEngines.create(db, new RuleEngineTransactions(new DataSourceTransactionManager(db)),
                who, who, CLOCK, EngineSettings.defaults().withDraftAuthoring(true));
        engines.cursorKeys().load();
        versions = engines.versionService();
    }

    static DataSource dataSource(String database) {
        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database));
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }

    /** Every rle_ table as one fingerprint: its row count and a hash of its rows' text. */
    List<String> snapshot() {
        List<String> tables = jdbc.sql("SELECT table_name FROM information_schema.tables"
                + " WHERE table_schema = current_schema() AND table_name LIKE 'rle\\_%' ORDER BY table_name")
                .query(String.class).list();
        assertThat(tables).contains("rle_version", "rle_version_history", "rle_decision_outbox", "rle_idempotency");
        return tables.stream().map(t -> t + " " + jdbc.sql("SELECT count(*) || ' ' || md5(coalesce(string_agg(x::text, '|'"
                + " ORDER BY x::text), '')) FROM " + t + " x").query(String.class).single()).toList();
    }

    @Test
    void RLE_090_validating_a_table_stores_nothing_in_any_table() {
        // Given card-fee v1 published and lan's draft v2
        who.set("bank-a", "lan");
        versions.importRuleSet(ImportRequest.yaml(RuleEnginePostgresIT.cardFee()));
        RuleSetVersion requested = versions.requestPublish("card-fee", 1, WriteOptions.none());
        who.set("bank-a", "bob");
        versions.approve("card-fee", 1, new WriteOptions(null, requested.lockVersion(), null)
                .expectingContent(requested.canonicalHash()));
        who.set("bank-a", "lan");
        versions.importVersion("card-fee", ImportRequest.yaml(RuleEnginePostgresIT.cardFee()));
        List<String> before = snapshot();

        // When lan validates a good table, a table with an unknown input on R2, and the published v1;
        // and a bank-b author asks for card-fee
        String text = new String(RuleEnginePostgresIT.cardFee(), StandardCharsets.UTF_8);
        DraftValidation good = versions.validateDraft("card-fee", 2,
                ImportRequest.yaml(text.replace("\"10000 VND\"", "\"0 VND\"").getBytes(StandardCharsets.UTF_8)));
        DraftValidation bad = versions.validateDraft("card-fee", 2,
                ImportRequest.yaml(text.replace("txCount: \">= 3\"", "txCount: \">= tierX\"")
                        .getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> versions.validateDraft("card-fee", 1, ImportRequest.yaml(RuleEnginePostgresIT.cardFee())))
                .isInstanceOf(InvalidStateException.class);
        who.set("bank-b", "eva");
        assertThatThrownBy(() -> versions.validateDraft("card-fee", 2, ImportRequest.yaml(RuleEnginePostgresIT.cardFee())))
                .isInstanceOf(RuleSetNotFoundException.class);
        who.set("bank-a", "lan");

        // Then the answers are right
        assertThat(good.valid()).isTrue();
        assertThat(good.readings()).hasSize(3);
        assertThat(bad.valid()).isFalse();
        assertThat(bad.problems()).singleElement().satisfies(p -> {
            assertThat(p.ruleId()).isEqualTo("R2");
            assertThat(p.column()).isEqualTo("txCount");
            assertThat(p.code()).isEqualTo("UNKNOWN_INPUT");
            assertThat(p.message()).containsKeys("en", "vi");
        });
        // and no row of any rle_ table was written or changed: no version, history, event or idempotency record
        assertThat(snapshot()).isEqualTo(before);
    }
}
