package io.github.dhluong90.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.HorusBpmException;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.LifecycleAction;
import io.github.dhluong90.bpm.rules.api.Page;
import io.github.dhluong90.bpm.rules.api.PageRequest;
import io.github.dhluong90.bpm.rules.api.QueryInvalidException;
import io.github.dhluong90.bpm.rules.api.RuleSetNotFoundException;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.VersionHistoryEntry;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.VersionSummary;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.service.EngineSettings;
import io.github.dhluong90.bpm.rules.internal.service.RuleEngines;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** QA review of #147 on PostgreSQL: cursor tampering, concurrent rejects, paging under inserts, pre-V3 history. */
@Testcontainers
class EditorApiQaPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static final Instant DEC_01 = Instant.parse("2026-12-01T00:00:00Z");
    private static final AtomicInteger DATABASES = new AtomicInteger();

    final RuleEnginePostgresIT.Caller caller = new RuleEnginePostgresIT.Caller();
    final EditorApiPostgresIT.Movable clock = new EditorApiPostgresIT.Movable(DEC_01);
    DataSource db;
    JdbcClient jdbc;
    VersionService versions;

    @BeforeEach
    void setUp() {
        db = freshDatabase();
        new RuleEngineSchemaMigrator(db).migrate();
        RuleEngineTransactions transactions = new RuleEngineTransactions(new DataSourceTransactionManager(db));
        jdbc = JdbcClient.create(db);
        RuleEngines engines = PostgresRuleEngines.create(db, transactions, caller, caller, clock, EngineSettings.defaults());
        engines.cursorKeys().load();   // as the starter does at start-up, after the migrations
        versions = engines.versionService();
    }

    static String cursor(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void RLE_039_a_tampered_cursor_with_a_nul_character_is_query_invalid_not_a_server_error() {
        imported("card-fee");
        caller.as("bank-a", "dan");

        // A tampered cursor that decodes to text PostgreSQL cannot hold
        String tampered = cursor("rule-sets:\u0000");

        assertThatThrownBy(() -> versions.listRuleSets(new PageRequest(2, tampered)))
                .isInstanceOf(QueryInvalidException.class);
    }

    @Test
    void RLE_039_a_rule_set_name_with_a_nul_character_is_not_found_not_a_server_error() {
        imported("card-fee");
        caller.as("bank-a", "dan");

        assertThat(versions.findRuleSet("card\u0000fee")).isEmpty();
        assertThatThrownBy(() -> versions.listVersions("card\u0000fee", PageRequest.first()))
                .isInstanceOf(RuleSetNotFoundException.class);
    }

    @Test
    void RLE_040_paging_versions_while_a_new_version_is_imported_never_repeats_or_skips() {
        // Given card-fee v1 to v3
        imported("card-fee");
        versions.importVersion("card-fee", ImportRequest.yaml(EditorApiPostgresIT.renamed("card-fee", null)));
        versions.importVersion("card-fee", ImportRequest.yaml(EditorApiPostgresIT.renamed("card-fee", null)));
        Page<VersionSummary> first = versions.listVersions("card-fee", new PageRequest(2, null));

        // When v4 is imported between the pages
        versions.importVersion("card-fee", ImportRequest.yaml(EditorApiPostgresIT.renamed("card-fee", null)));
        Page<VersionSummary> second = versions.listVersions("card-fee", new PageRequest(2, first.nextCursor()));

        // Then
        assertThat(first.items()).extracting(VersionSummary::version).containsExactly(3, 2);
        assertThat(second.items()).extracting(VersionSummary::version).containsExactly(1);
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    void RLE_025_concurrent_rejects_with_different_keys_reject_once() throws Exception {
        // Given card-fee v1 pending
        imported("card-fee");
        RuleSetVersion pending = versions.requestPublish("card-fee", 1, WriteOptions.none());
        long events = count("rle_decision_outbox");
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> calls = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String key = "k-race-" + i;
            calls.add(() -> {
                start.await();
                try {
                    // the caller is shared and fixed to minh before the race starts
                    versions.reject("card-fee", 1, new WriteOptions(key, pending.lockVersion(), "race " + key));
                    return "OK";
                } catch (HorusBpmException e) {
                    return e.code();
                }
            });
        }
        caller.as("bank-a", "minh");

        // When eight approvers' rejects race with the same If-Match
        List<String> outcomes = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<String>> futures = new ArrayList<>();
            for (Callable<String> call : calls) {
                futures.add(pool.submit(call));
            }
            start.countDown();
            for (Future<String> f : futures) {
                outcomes.add(f.get());
            }
        }

        // Then exactly one wins, the rest are refused as conflicts, and one row and one event are written
        assertThat(outcomes).filteredOn("OK"::equals).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !o.equals("OK"))
                .allSatisfy(o -> assertThat(o).isIn("VERSION_CONFLICT", "INVALID_STATE"));
        assertThat(versions.history("card-fee", 1)).extracting(VersionHistoryEntry::action)
                .containsExactly(LifecycleAction.CREATED, LifecycleAction.REQUESTED, LifecycleAction.REJECTED);
        assertThat(count("rle_decision_outbox")).isEqualTo(events + 1);
    }

    @Test
    void RLE_025_history_of_another_tenant_is_rule_set_not_found() {
        imported("card-fee");
        caller.as("bank-b", "minh");

        assertThatThrownBy(() -> versions.history("card-fee", 1)).isInstanceOf(RuleSetNotFoundException.class);
        assertThat(versions.findRuleSet("card-fee")).isEmpty();
    }

    /** A version that existed before V3 still answers GET .../history with its creation (DESIGN 3.3.3). */
    @Test
    void RLE_025_a_version_created_before_the_history_table_has_its_created_row() {
        // Given a database at V2 holding card-fee v1
        DataSource old = freshDatabase();
        org.flywaydb.core.Flyway.configure().dataSource(old).table("rle_schema_history")
                .locations(RuleEngineSchemaMigrator.MIGRATION_LOCATION).baselineOnMigrate(true).baselineVersion("0")
                .target("2").load().migrate();
        JdbcClient v2 = JdbcClient.create(old);
        v2.sql("INSERT INTO rle_rule_set (tenant_id, id, name, created_at) VALUES "
                + "('bank-a', '01KD00000000000000000RS001', 'card-fee', '2026-11-01T00:00:00Z')").update();
        v2.sql("""
                INSERT INTO rle_version (tenant_id, id, rule_set_id, version_num, status, hit_policy, canonical_json,
                  canonical_hash, source_format, source_document, source_hash, lock_version, created_by, created_at)
                VALUES ('bank-a', '01KD00000000000000000VS001', '01KD00000000000000000RS001', 1, 'DRAFT', 'FIRST',
                  '{}', :h, 'YAML', '', :h, 0, 'lan', '2026-11-01T00:00:00Z')
                """).param("h", "0".repeat(64)).update();

        // When V3 runs
        new RuleEngineSchemaMigrator(old).migrate();

        // Then the version's history is not empty
        assertThat(v2.sql("SELECT count(*) FROM rle_version_history WHERE tenant_id = 'bank-a'")
                .query(Long.class).single()).isEqualTo(1L);
    }

    void imported(String name) {
        caller.as("bank-a", "lan");
        versions.importRuleSet(ImportRequest.yaml(EditorApiPostgresIT.renamed(name, null)));
    }

    long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    static DataSource freshDatabase() {
        String name = "editorqa_" + DATABASES.incrementAndGet();
        JdbcClient.create(dataSource(POSTGRES.getDatabaseName())).sql("CREATE DATABASE " + name).update();
        return dataSource(name);
    }

    static DataSource dataSource(String database) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setURL(POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database));
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }
}
