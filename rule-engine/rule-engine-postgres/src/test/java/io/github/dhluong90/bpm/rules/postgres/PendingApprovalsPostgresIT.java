package io.github.dhluong90.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.Page;
import io.github.dhluong90.bpm.rules.api.PageRequest;
import io.github.dhluong90.bpm.rules.api.PendingApproval;
import io.github.dhluong90.bpm.rules.api.QueryInvalidException;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.service.EngineSettings;
import io.github.dhluong90.bpm.rules.internal.service.RuleEngines;
import io.github.dhluong90.bpm.rules.postgres.migration.V30__rle_version_pending_index;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
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
 * RLE-190 (#31) on PostgreSQL: the pending approvals list reads only the caller's tenant, includes a pending version
 * that is not the latest, pages by a signed keyset cursor over {@code (requested_at, id)}, never reads a definition,
 * and V30 builds its index CONCURRENTLY, repairs an interrupted build and may run twice.
 */
@Testcontainers
class PendingApprovalsPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static final String INDEX = V30__rle_version_pending_index.INDEX;
    private static final Set<String> APPROVER = Set.of("rules:read", "rules:approve");

    @Test
    void RLE_190_lists_the_callers_tenant_oldest_first_across_pages_with_its_hashes() {
        // Given bank-a: card-fee v1 and v2 pending (v1 not the latest), fx-margin v1 pending; bank-b: aml-score pending
        DataSource db = database();
        new RuleEngineSchemaMigrator(db).migrate();
        RuleEnginePostgresIT.Caller caller = new RuleEnginePostgresIT.Caller();
        EditorApiPostgresIT.Movable clock = new EditorApiPostgresIT.Movable(Instant.parse("2027-01-10T02:00:00Z"));
        RuleEngines engines = PostgresRuleEngines.create(db, new RuleEngineTransactions(new DataSourceTransactionManager(db)),
                caller, caller, clock, EngineSettings.defaults());
        engines.cursorKeys().load();
        VersionService versions = engines.versionService();
        caller.as("bank-a", "lan");
        versions.importRuleSet(ImportRequest.yaml(EditorApiPostgresIT.renamed("card-fee", null)));
        versions.importVersion("card-fee", ImportRequest.yaml(differentFee("card-fee")));
        RuleSetVersion v1 = versions.requestPublish("card-fee", 1, WriteOptions.none());
        clock.set(Instant.parse("2027-01-11T02:00:00Z"));
        caller.as("bank-a", "ana");
        versions.importRuleSet(ImportRequest.yaml(EditorApiPostgresIT.renamed("fx-margin", null)));
        RuleSetVersion fx = versions.requestPublish("fx-margin", 1, WriteOptions.none());
        clock.set(Instant.parse("2027-01-12T02:00:00Z"));
        caller.as("bank-a", "lan");
        RuleSetVersion v2 = versions.requestPublish("card-fee", 2, WriteOptions.none());
        caller.as("bank-b", "lan");
        versions.importRuleSet(ImportRequest.yaml(EditorApiPostgresIT.renamed("aml-score", null)));
        versions.requestPublish("aml-score", 1, WriteOptions.none());

        // When bob of bank-a reads two at a time
        caller.as("bank-a", "bob", APPROVER);
        Page<PendingApproval> first = versions.pendingApprovals(new PageRequest(2, null));
        Page<PendingApproval> second = versions.pendingApprovals(new PageRequest(2, first.nextCursor()));

        // Then oldest request first, bank-b's aml-score absent, each with the hash to send back
        assertThat(first.items()).extracting(PendingApproval::ruleSet, PendingApproval::version,
                PendingApproval::requestedBy, PendingApproval::canonicalHash)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("card-fee", 1, "lan", v1.canonicalHash()),
                        org.assertj.core.groups.Tuple.tuple("fx-margin", 1, "ana", fx.canonicalHash()));
        assertThat(first.items().getFirst().allowedActions()).containsExactly("approve", "reject");
        assertThat(second.items()).extracting(PendingApproval::ruleSet, PendingApproval::version,
                PendingApproval::canonicalHash)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("card-fee", 2, v2.canonicalHash()));
        assertThat(second.nextCursor()).isNull();

        // And bank-b's reader cannot use bank-a's cursor
        caller.as("bank-b", "eve", Set.of("rules:read"));
        assertThatThrownBy(() -> versions.pendingApprovals(new PageRequest(2, first.nextCursor())))
                .isInstanceOf(QueryInvalidException.class);
        assertThat(versions.pendingApprovals(PageRequest.first()).items()).extracting(PendingApproval::ruleSet)
                .containsExactly("aml-score");
    }

    @Test
    void RLE_190_requests_made_in_the_same_instant_page_by_id_without_gap_or_repeat() {
        // Given three versions requested at one instant
        DataSource db = database();
        new RuleEngineSchemaMigrator(db).migrate();
        RuleEnginePostgresIT.Caller caller = new RuleEnginePostgresIT.Caller();
        RuleEngines engines = PostgresRuleEngines.create(db, new RuleEngineTransactions(new DataSourceTransactionManager(db)),
                caller, caller, new EditorApiPostgresIT.Movable(Instant.parse("2027-01-10T02:00:00Z")),
                EngineSettings.defaults());
        engines.cursorKeys().load();
        VersionService versions = engines.versionService();
        caller.as("bank-a", "lan");
        for (String name : new String[] {"card-fee", "fx-margin", "kyc-risk"}) {
            versions.importRuleSet(ImportRequest.yaml(EditorApiPostgresIT.renamed(name, null)));
            versions.requestPublish(name, 1, WriteOptions.none());
        }

        // When read one at a time
        caller.as("bank-a", "bob", APPROVER);
        Page<PendingApproval> a = versions.pendingApprovals(new PageRequest(1, null));
        Page<PendingApproval> b = versions.pendingApprovals(new PageRequest(1, a.nextCursor()));
        Page<PendingApproval> c = versions.pendingApprovals(new PageRequest(1, b.nextCursor()));

        // Then each version appears once and the last page has no cursor
        assertThat(java.util.stream.Stream.of(a, b, c).flatMap(p -> p.items().stream()).map(PendingApproval::ruleSet))
                .containsExactlyInAnyOrder("card-fee", "fx-margin", "kyc-risk");
        assertThat(c.nextCursor()).isNull();
    }

    @Test
    void TR_OPS_02_V30_builds_a_valid_partial_index_and_a_rerun_after_an_interrupted_build_replaces_it() throws SQLException {
        // Given a database at V26 with an INVALID leftover under the index's name
        DataSource db = database();
        Flyway.configure(RuleEngineSchemaMigrator.class.getClassLoader())
                .configuration(RuleEngineSchemaMigrator.LOCKING).dataSource(db).table("rle_schema_history")
                .locations(RuleEngineSchemaMigrator.MIGRATION_LOCATION).baselineOnMigrate(true).baselineVersion("0")
                .target("26").load().migrate();
        JdbcClient jdbc = JdbcClient.create(db);
        jdbc.sql("CREATE INDEX " + INDEX + " ON rle_version (tenant_id, requested_at, id)").update();
        jdbc.sql("UPDATE pg_index SET indisvalid = false WHERE indexrelid = '" + INDEX + "'::regclass").update();
        long leftover = oid(jdbc);

        // When the migrator runs V30, and then V30 runs again
        new RuleEngineSchemaMigrator(db).migrate();
        long rebuilt = oid(jdbc);
        try (Connection connection = db.getConnection()) {
            V30__rle_version_pending_index.apply(connection);
        }

        // Then the index is valid, partial on the pending status, and the re-run keeps it
        assertThat(rebuilt).isNotEqualTo(leftover);
        assertThat(jdbc.sql("SELECT indisvalid FROM pg_index WHERE indexrelid = to_regclass(:i)").param("i", INDEX)
                .query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT pg_get_indexdef(to_regclass(:i))").param("i", INDEX).query(String.class).single())
                .contains("(tenant_id, requested_at, id)").contains("PENDING_APPROVAL");
        assertThat(oid(jdbc)).isEqualTo(rebuilt);
        assertThat(jdbc.sql("SELECT count(*) FROM rle_schema_history WHERE version = '30' AND success")
                .query(Long.class).single()).isEqualTo(1L);
    }

    private static byte[] differentFee(String name) {
        return new String(EditorApiPostgresIT.renamed(name, null), java.nio.charset.StandardCharsets.UTF_8)
                .replace("then: { fee: \"10000 VND\" }", "then: { fee: \"12000 VND\" }")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static long oid(JdbcClient jdbc) {
        return jdbc.sql("SELECT to_regclass(:i)::oid::bigint").param("i", INDEX).query(Long.class).single();
    }

    private static DataSource database() {
        String name = "pending_" + DATABASES.incrementAndGet();
        PGSimpleDataSource admin = dataSource(POSTGRES.getDatabaseName());
        JdbcClient.create(admin).sql("CREATE DATABASE " + name).update();
        return dataSource(name);
    }

    private static PGSimpleDataSource dataSource(String database) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database));
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }
}
