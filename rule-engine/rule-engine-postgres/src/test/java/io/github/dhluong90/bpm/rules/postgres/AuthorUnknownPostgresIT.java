package io.github.dhluong90.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.AuthorUnknownException;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.RuleEngineSchema;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.service.EngineSettings;
import io.github.dhluong90.bpm.rules.internal.service.RuleEngines;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A pending version written before the author was recorded cannot be approved after the upgrade (RLE-054, PD-105),
 * on a real PostgreSQL. The database runs the release before V2: a card fee version is pending, requested by lan, in
 * the V1 row shape with no author column. The schema is then upgraded to the current version and the engine runs on
 * it. bob's approval is refused with {@code AUTHOR_UNKNOWN} and nothing is written; no author is assigned by the
 * upgrade or by the refusal.
 */
@Testcontainers
class AuthorUnknownPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(TestImages.postgres());

    static final Set<String> AUTHOR = Set.of("rules:read", "rules:author");
    static final Set<String> APPROVER = Set.of("rules:read", "rules:approve");
    private static final AtomicInteger DATABASES = new AtomicInteger();

    static final byte[] CARD_FEE = """
            schemaVersion: 1
            ruleSet: card-fee
            hitPolicy: first
            inputs:
              - { name: tier, type: string }
            outputs:
              - { name: fee, type: money }
            rules:
              - { id: R1, when: { tier: "== 'GOLD'" }, then: { fee: "0 VND" } }
              - { id: R2, when: {}, then: { fee: "50000 VND" } }
            """.getBytes(StandardCharsets.UTF_8);

    final RetireVersionPostgresIT.Caller caller = new RetireVersionPostgresIT.Caller();
    final EditorApiPostgresIT.Movable clock = new EditorApiPostgresIT.Movable(Instant.parse("2027-01-15T02:00:00Z"));

    @Test
    void RLE_054_a_pending_version_written_before_v2_is_refused_after_the_upgrade() {
        // Given: card-fee v1 pending at V1, requested by lan, with content the current engine wrote elsewhere
        Map<String, Object> content = currentEngineContent();
        DataSource db = databaseAt("1");
        JdbcClient jdbc = JdbcClient.create(db);
        jdbc.sql("INSERT INTO rle_rule_set (tenant_id, id, name, created_at) VALUES ('bank-a', 'RS1', 'card-fee', now())")
                .update();
        jdbc.sql("""
                INSERT INTO rle_version (tenant_id, id, rule_set_id, version_num, status, hit_policy, canonical_json,
                  canonical_hash, source_format, source_document, source_hash, lock_version, requested_by,
                  requested_at, created_at)
                VALUES ('bank-a', '01KDG7Z0RQ6V2Y8M1C4N5P7T9W', 'RS1', 1, 'PENDING_APPROVAL', 'FIRST',
                  CAST(:json AS jsonb), :hash, 'YAML', :source, :sourceHash, 1, 'lan', now(), now())
                """).param("json", content.get("canonical_json").toString()).param("hash", content.get("canonical_hash"))
                .param("source", content.get("source_document")).param("sourceHash", content.get("source_hash"))
                .update();

        // When the schema is upgraded and bob, who holds rules:approve and did not request it, approves
        new RuleEngineSchemaMigrator(db).migrate();
        RuleEngines engines = engines(db);
        caller.as("bank-a", "bob", APPROVER);
        RuleSetVersion pending = engines.versionService().find("card-fee", 1, false).orElseThrow();
        assertThat(pending.createdBy()).isNull();
        long historyBefore = count(jdbc, "SELECT count(*) FROM rle_version_history");
        long outboxBefore = count(jdbc, "SELECT count(*) FROM rle_decision_outbox");

        // Then it is refused with AUTHOR_UNKNOWN, and the row, its history and the outbox are untouched
        assertThatThrownBy(() -> engines.versionService().approve("card-fee", 1,
                        new WriteOptions("ap-1", pending.lockVersion(), "reviewed")))
                .isInstanceOfSatisfying(AuthorUnknownException.class,
                        e -> assertThat(e.code()).isEqualTo("AUTHOR_UNKNOWN"));
        Map<String, Object> row = jdbc.sql("""
                SELECT status, created_by, approved_by, lock_version FROM rle_version
                WHERE tenant_id = 'bank-a' AND rule_set_id = 'RS1' AND version_num = 1
                """).query().singleRow();
        assertThat(row.get("status")).isEqualTo("PENDING_APPROVAL");
        assertThat(row.get("created_by")).isNull();
        assertThat(row.get("approved_by")).isNull();
        assertThat(row.get("lock_version")).isEqualTo(pending.lockVersion());
        assertThat(count(jdbc, "SELECT count(*) FROM rle_version_history")).isEqualTo(historyBefore);
        assertThat(count(jdbc, "SELECT count(*) FROM rle_decision_outbox")).isEqualTo(outboxBefore);

        // And: rejecting stays allowed, and the change imported again by lan is approved by bob
        assertThat(engines.versionService().reject("card-fee", 1,
                new WriteOptions("rj-1", pending.lockVersion(), "author unknown, submit again")).status())
                .isEqualTo(VersionStatus.DRAFT);
        caller.as("bank-a", "lan", AUTHOR);
        int v2 = engines.versionService().importVersion("card-fee", ImportRequest.yaml(CARD_FEE)).version();
        long reviewed = engines.versionService().requestPublish("card-fee", v2, WriteOptions.idempotencyKey("rp-2"))
                .lockVersion();
        caller.as("bank-a", "bob", APPROVER);
        RuleSetVersion published = engines.versionService().approve("card-fee", v2,
                new WriteOptions("ap-2", reviewed, "reviewed"));
        assertThat(published.status()).isEqualTo(VersionStatus.PUBLISHED);
        assertThat(published.createdBy()).isEqualTo("lan");
    }

    /** The canonical form and hashes the current engine stores for {@link #CARD_FEE}, from a separate database. */
    private Map<String, Object> currentEngineContent() {
        DataSource donor = freshDatabase();
        new RuleEngineSchemaMigrator(donor).migrate();
        caller.as("bank-a", "lan", AUTHOR);
        engines(donor).versionService().importRuleSet(ImportRequest.yaml(CARD_FEE));
        return JdbcClient.create(donor).sql("""
                SELECT canonical_json::text AS canonical_json, canonical_hash, source_document, source_hash
                FROM rle_version WHERE tenant_id = 'bank-a'
                """).query().singleRow();
    }

    private RuleEngines engines(DataSource db) {
        RuleEngineTransactions transactions = new RuleEngineTransactions(new DataSourceTransactionManager(db));
        RuleEngines engines = PostgresRuleEngines.create(db, transactions, caller, caller, clock,
                EngineSettings.defaults().withRequireContentHash(false));
        engines.cursorKeys().load();
        return engines;
    }

    private static long count(JdbcClient jdbc, String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    /** A database migrated to {@code version} only, as one that ran a release before it. */
    private static DataSource databaseAt(String version) {
        DataSource db = freshDatabase();
        Flyway.configure(AuthorUnknownPostgresIT.class.getClassLoader()).dataSource(db)
                .table(RuleEngineSchema.HISTORY_TABLE).locations(RuleEngineSchemaMigrator.MIGRATION_LOCATION)
                .target(version).load().migrate();
        return db;
    }

    private static DataSource freshDatabase() {
        String name = "rle_author_unknown_" + DATABASES.incrementAndGet();
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
