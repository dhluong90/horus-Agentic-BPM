package io.github.dhluong90.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The database's four-eyes check {@code rle_version_four_eyes} without the {@code system:dev-auto-publish}
 * exemption (RLE-009, TR-SEC-04), against a real PostgreSQL, one fresh database per test. The rows
 * are written around the service, as an injected statement or a careless script would, because the constraint is the
 * second line of defence behind the service's own check.
 */
@Testcontainers
class FourEyesConstraintPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(TestImages.postgres());

    static final String FORMER_EXEMPT_ID = "system:dev-auto-publish";
    private static final AtomicInteger DATABASES = new AtomicInteger();

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

    private static DataSource migratedDatabase() {
        DataSource db = freshDatabase();
        new RuleEngineSchemaMigrator(db).migrate();
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
