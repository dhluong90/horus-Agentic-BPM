package io.github.dhluong90.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.RuleEngineSchema;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The database's four-eyes check {@code rle_version_four_eyes} without the {@code system:dev-auto-publish}
 * exemption (RLE-009, TR-SEC-04, migration V12), against a real PostgreSQL, one fresh database per test. The rows
 * are written around the service, as an injected statement or a careless script would, because the constraint is the
 * second line of defence behind the service's own check.
 */
@Testcontainers
class FourEyesConstraintPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(TestImages.postgres());

    static final String FORMER_EXEMPT_ID = "system:dev-auto-publish";
    static final String MIGRATION = "io/github/dhluong90/bpm/rules/postgres/migration/"
            + "V12__rle_version_four_eyes_no_exemption.sql";
    private static final AtomicInteger DATABASES = new AtomicInteger();

    @Test
    void RLE_009_the_database_refuses_the_former_exempt_id_approving_its_own_request() {
        // Given: a card fee version requested by the former exempt id, written at V11 and at V12
        DataSource before = databaseAt("11");
        DataSource after = migratedDatabase();
        for (DataSource db : new DataSource[] {before, after}) {
            insertVersion(db, "V1", "PENDING_APPROVAL", "alice", FORMER_EXEMPT_ID, null);
        }

        // When / Then: V2's exemption let the same id approve; the constraint now refuses it, and a colleague passes
        assertThat(approve(before, "V1", FORMER_EXEMPT_ID)).isEqualTo(1);
        assertThatThrownBy(() -> approve(after, "V1", FORMER_EXEMPT_ID))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("rle_version_four_eyes");
        assertThat(approve(after, "V1", "bob")).isEqualTo(1);
    }

    @Test
    void RLE_009_the_database_refuses_the_former_exempt_id_approving_its_own_draft() {
        // Given: authored by the former exempt id, requested by alice
        DataSource db = migratedDatabase();
        insertVersion(db, "V1", "PENDING_APPROVAL", FORMER_EXEMPT_ID, "alice", null);

        // When / Then
        assertThatThrownBy(() -> approve(db, "V1", FORMER_EXEMPT_ID))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("rle_version_four_eyes");
        assertThatThrownBy(() -> approve(db, "V1", "alice"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("rle_version_four_eyes");
        assertThat(approve(db, "V1", "bob")).isEqualTo(1);
    }

    @Test
    void RLE_009_existing_rows_validate_when_upgrading_to_the_constraint_without_exemption() {
        // Given: a database at V11 with a draft, a pending request, a colleague's approval and an approval recorded
        // under the former exempt id for someone else's request, all valid without the exemption
        DataSource db = databaseAt("11");
        insertVersion(db, "V1", "DRAFT", "alice", null, null);
        insertVersion(db, "V2", "PENDING_APPROVAL", "alice", "alice", null);
        insertVersion(db, "V3", "PUBLISHED", "alice", "alice", "bob");
        insertVersion(db, "V4", "RETIRED", "alice", "carol", FORMER_EXEMPT_ID);

        // When
        SchemaMigrationResult result = new RuleEngineSchemaMigrator(db).migrate();

        // Then: the constraint is in place and validated, and every row is kept
        assertThat(result.initialVersion()).isEqualTo("11");
        assertThat(result.currentVersion()).isEqualTo("16");
        assertThat(JdbcClient.create(db).sql("""
                SELECT convalidated FROM pg_constraint
                WHERE conname = 'rle_version_four_eyes' AND conrelid = 'rle_version'::regclass
                """).query(Boolean.class).single()).isTrue();
        assertThat(constraintDefinition(db)).doesNotContain(FORMER_EXEMPT_ID);
        assertThat(JdbcClient.create(db).sql("SELECT count(*) FROM rle_version WHERE tenant_id = 'bank-a'")
                .query(Long.class).single()).isEqualTo(4L);
    }

    @Test
    void RLE_009_an_existing_self_approval_under_the_former_exempt_id_stops_the_upgrade_naming_the_constraint() {
        // Given: the one row shape V2 allowed and V12 does not; no engine code writes it
        DataSource db = databaseAt("11");
        insertVersion(db, "V1", "PUBLISHED", "alice", FORMER_EXEMPT_ID, FORMER_EXEMPT_ID);

        // When / Then: VALIDATE refuses, the migration rolls back, and the schema stays at V11
        assertThatThrownBy(() -> new RuleEngineSchemaMigrator(db).migrate())
                .hasStackTraceContaining("rle_version_four_eyes");
        assertThat(new RuleEngineSchemaMigrator(db).currentVersion()).contains("11");
        assertThat(constraintDefinition(db)).contains(FORMER_EXEMPT_ID);
    }

    @Test
    void RLE_009_the_documented_rollback_restores_the_previous_constraint() throws IOException {
        // Given
        DataSource db = migratedDatabase();
        insertVersion(db, "V1", "PENDING_APPROVAL", "alice", FORMER_EXEMPT_ID, null);

        // When: the rollback statements written in V12's header are run as a forward step
        JdbcClient.create(db).sql(rollbackStatements()).update();

        // Then: the V2 constraint, exemption included, is back and validated
        assertThat(constraintDefinition(db)).contains(FORMER_EXEMPT_ID);
        assertThat(approve(db, "V1", FORMER_EXEMPT_ID)).isEqualTo(1);
        assertThatThrownBy(() -> approve(db, "V1", "alice")).isInstanceOf(DataIntegrityViolationException.class);
    }

    /** The statements between {@code rollback-begin} and {@code rollback-end} in V12, without the comment marks. */
    private static String rollbackStatements() throws IOException {
        try (InputStream in = FourEyesConstraintPostgresIT.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(in).as(MIGRATION).isNotNull();
            String script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String block = script.substring(script.indexOf("-- rollback-begin\n") + "-- rollback-begin\n".length(),
                    script.indexOf("-- rollback-end"));
            return block.lines().map(line -> line.replaceFirst("^--\\s{0,3}", "")).collect(Collectors.joining("\n"));
        }
    }

    private static int approve(DataSource db, String id, String approver) {
        return JdbcClient.create(db).sql("""
                UPDATE rle_version SET status = 'PUBLISHED', approved_by = :approver, approved_at = now()
                WHERE tenant_id = 'bank-a' AND id = :id
                """).param("approver", approver).param("id", id).update();
    }

    private static void insertVersion(DataSource db, String id, String status, String author, String requester,
            String approver) {
        JdbcClient jdbc = JdbcClient.create(db);
        jdbc.sql("""
                INSERT INTO rle_rule_set (tenant_id, id, name, created_at)
                VALUES ('bank-a', 'RS1', 'card-fee', now()) ON CONFLICT DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO rle_version (tenant_id, id, rule_set_id, version_num, status, hit_policy, canonical_json,
                  canonical_hash, source_format, source_document, source_hash, created_by, requested_by, approved_by,
                  created_at, retired_at)
                VALUES ('bank-a', :id, 'RS1', (SELECT coalesce(max(version_num), 0) + 1 FROM rle_version),
                  :status, 'FIRST', '{}'::jsonb, :hash, 'YAML', 'ruleSet: card-fee', :hash, :author, :requester,
                  :approver, now(), CASE WHEN :status = 'RETIRED' THEN now() END)
                """).param("id", id).param("status", status).param("hash", "a".repeat(64)).param("author", author)
                .param("requester", requester).param("approver", approver).update();
    }

    private static String constraintDefinition(DataSource db) {
        return JdbcClient.create(db).sql("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conname = 'rle_version_four_eyes' AND conrelid = 'rle_version'::regclass
                """).query(String.class).single();
    }

    private static DataSource migratedDatabase() {
        DataSource db = freshDatabase();
        new RuleEngineSchemaMigrator(db).migrate();
        return db;
    }

    /** A database migrated to {@code version} only, as one that ran a release before V12. */
    private static DataSource databaseAt(String version) {
        DataSource db = freshDatabase();
        Flyway.configure(FourEyesConstraintPostgresIT.class.getClassLoader()).dataSource(db)
                .table(RuleEngineSchema.HISTORY_TABLE).locations(RuleEngineSchemaMigrator.MIGRATION_LOCATION)
                .target(version).load().migrate();
        return db;
    }

    private static DataSource freshDatabase() {
        String name = "rle_four_eyes_" + DATABASES.incrementAndGet();
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
