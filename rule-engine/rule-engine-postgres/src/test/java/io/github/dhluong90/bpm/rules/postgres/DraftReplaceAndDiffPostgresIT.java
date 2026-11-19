package com.horus.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.horus.bpm.rules.api.HorusBpmException;
import com.horus.bpm.rules.api.ImportRequest;
import com.horus.bpm.rules.api.RuleSetNotFoundException;
import com.horus.bpm.rules.api.RuleSetVersion;
import com.horus.bpm.rules.api.ValidationFailedException;
import com.horus.bpm.rules.api.VersionDiff;
import com.horus.bpm.rules.api.VersionDiff.Change;
import com.horus.bpm.rules.api.VersionDiff.FieldChange;
import com.horus.bpm.rules.api.VersionDiff.RowChange;
import com.horus.bpm.rules.api.VersionService;
import com.horus.bpm.rules.api.WriteOptions;
import com.horus.bpm.rules.internal.service.EngineSettings;
import com.horus.bpm.rules.internal.service.RuleEngines;
import com.horus.bpm.rules.spi.CallerResolver;
import com.horus.bpm.rules.spi.TenantResolver;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
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

/**
 * Replacing a draft (RLE-047) and the version diff (RLE-028) on PostgreSQL: the content columns and the rule rows are
 * replaced in one guarded statement set, two concurrent replaces cannot both win, and the diff of versions read back
 * from JSONB is the same as of the documents written. One fresh database per test; each thread has its own caller.
 */
@Testcontainers
class DraftReplaceAndDiffPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(System.getProperty("bpm.test.postgres.image", "postgres:16-alpine"));

    private static final AtomicInteger DATABASES = new AtomicInteger();
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-12-01T00:00:00Z"), ZoneOffset.UTC);

    final PerThreadCaller who = new PerThreadCaller();
    JdbcClient jdbc;
    VersionService versions;

    @BeforeEach
    void setUp() {
        DataSource db = freshDatabase();
        new RuleEngineSchemaMigrator(db).migrate();
        jdbc = JdbcClient.create(db);
        RuleEngines engines = PostgresRuleEngines.create(db, new RuleEngineTransactions(new DataSourceTransactionManager(db)),
                who, who, CLOCK, EngineSettings.defaults().withDraftAuthoring(true));
        engines.cursorKeys().load();
        versions = engines.versionService();
    }

    @Test
    void RLE_047_author_replaces_draft_in_place_with_import_checks() {
        // Given card-fee v1 published, v2 a draft by lan with R2 at 10000 VND
        publishedV1();
        who.set("bank-a", "lan");
        RuleSetVersion draft = versions.importVersion("card-fee", ImportRequest.yaml(RuleEnginePostgresIT.cardFee()));
        long history = count("rle_version_history");
        long outbox = count("rle_decision_outbox");

        // When lan replaces it with R2 at 0 VND, twice with k-501
        WriteOptions put = new WriteOptions("k-501", draft.lockVersion(), null);
        RuleSetVersion replaced = versions.replaceDraft("card-fee", 2, ImportRequest.yaml(r2Zero()), put);
        RuleSetVersion replay = versions.replaceDraft("card-fee", 2, ImportRequest.yaml(r2Zero()), put);

        // Then the row keeps its number, status and author, with new content, hashes, source and lock
        assertThat(replay).isEqualTo(replaced);
        assertThat(jdbc.sql("""
                SELECT v.version_num || ' ' || v.status || ' ' || v.created_by || ' ' || v.lock_version || ' '
                  || v.canonical_hash || ' ' || v.source_hash
                FROM rle_version v JOIN rle_rule_set s ON s.tenant_id = v.tenant_id AND s.id = v.rule_set_id
                WHERE v.tenant_id = 'bank-a' AND s.name = 'card-fee' AND v.version_num = 2
                """).query(String.class).single()).isEqualTo("2 DRAFT lan 1 " + replaced.canonicalHash() + " "
                + replaced.sourceHash());
        assertThat(replaced.canonicalHash()).isNotEqualTo(draft.canonicalHash());
        assertThat(jdbc.sql("SELECT count(*) FROM rle_version WHERE tenant_id = 'bank-a'").query(Long.class).single())
                .isEqualTo(2L);
        // and the rule rows hold the new document, in order
        assertThat(jdbc.sql("""
                SELECT string_agg(r.rule_id || '=' || (r.then_expr_json->>'fee'), ',' ORDER BY r.row_order)
                FROM rle_rule r JOIN rle_version v ON v.tenant_id = r.tenant_id AND v.id = r.version_id
                WHERE r.tenant_id = 'bank-a' AND v.version_num = 2
                """).query(String.class).single()).isEqualTo("R1=0 VND,R2=0 VND,R3=50000 VND");
        // and one DRAFT_UPDATED row with both hashes, one draft-updated event, one idempotency record
        assertThat(count("rle_version_history")).isEqualTo(history + 1);
        assertThat(jdbc.sql("""
                SELECT actor || ' ' || from_status || ' ' || to_status || ' ' || lock_version || ' '
                  || previous_canonical_hash || ' ' || canonical_hash
                FROM rle_version_history WHERE tenant_id = 'bank-a' AND action = 'DRAFT_UPDATED'
                """).query(String.class).single()).isEqualTo("lan DRAFT DRAFT 1 " + draft.canonicalHash() + " "
                + replaced.canonicalHash());
        assertThat(count("rle_decision_outbox")).isEqualTo(outbox + 1);
        assertThat(jdbc.sql("""
                SELECT payload->>'updatedBy' || ' ' || (payload->>'previousCanonicalHash') || ' ' || (payload->>'canonicalHash')
                FROM rle_decision_outbox WHERE tenant_id = 'bank-a' AND payload->>'eventType' = 'rules.version.draft-updated.v1'
                """).query(String.class).single()).isEqualTo("lan " + draft.canonicalHash() + " " + replaced.canonicalHash());
        assertThat(versions.history("card-fee", 2).getLast().action())
                .isEqualTo(com.horus.bpm.rules.api.LifecycleAction.DRAFT_UPDATED);
        assertThat(jdbc.sql("SELECT count(*) FROM rle_idempotency WHERE tenant_id = 'bank-a' AND operation = 'replace-draft'")
                .query(Long.class).single()).isEqualTo(1L);

        // A refused document stores nothing; another tenant cannot reach the draft
        byte[] bad = new String(r2Zero(), StandardCharsets.UTF_8).replace("then: { fee: \"0 VND\" }", "then: { fee: \"+ 1\" }")
                .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> versions.replaceDraft("card-fee", 2, ImportRequest.yaml(bad),
                new WriteOptions("k-502", replaced.lockVersion(), null))).isInstanceOf(ValidationFailedException.class);
        who.set("bank-b", "lan");
        assertThatThrownBy(() -> versions.replaceDraft("card-fee", 2, ImportRequest.yaml(r2Zero()),
                new WriteOptions("k-503", replaced.lockVersion(), null))).isInstanceOf(RuleSetNotFoundException.class);
        who.set("bank-a", "lan");
        assertThat(versions.find("card-fee", 2, false).orElseThrow().canonicalHash()).isEqualTo(replaced.canonicalHash());
        assertThat(versions.find("card-fee", 2, false).orElseThrow().lockVersion()).isEqualTo(replaced.lockVersion());
        assertThat(jdbc.sql("SELECT count(*) FROM rle_idempotency WHERE tenant_id = 'bank-a' AND operation = 'replace-draft'")
                .query(Long.class).single()).isEqualTo(1L);
    }

    /** Two replaces based on the same lock version, at the same moment, with different keys: exactly one wins. */
    @Test
    void RLE_047_two_concurrent_replaces_with_the_same_if_match_let_exactly_one_win() throws Exception {
        publishedV1();
        for (int round = 0; round < 10; round++) {
            who.set("bank-a", "lan");
            int number = versions.importVersion("card-fee", ImportRequest.yaml(RuleEnginePostgresIT.cardFee())).version();
            CyclicBarrier together = new CyclicBarrier(2);
            List<Future<String>> outcomes = new ArrayList<>();
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                for (String fee : new String[] {"0 VND", "5000 VND"}) {
                    String key = "k-race-" + round + "-" + fee;
                    outcomes.add(pool.submit(() -> {
                        who.set("bank-a", "lan");
                        together.await();
                        try {
                            versions.replaceDraft("card-fee", number,
                                    ImportRequest.yaml(withR2(fee)), new WriteOptions(key, 0L, null));
                            return fee;
                        } catch (HorusBpmException e) {
                            return e.code();
                        }
                    }));
                }
                List<String> results = new ArrayList<>();
                for (Future<String> outcome : outcomes) {
                    results.add(outcome.get());
                }
                List<String> winners = results.stream().filter(r -> r.endsWith("VND")).toList();
                assertThat(winners).as("round %d %s", round, results).hasSize(1);
                assertThat(results).as("round %d", round).contains("VERSION_CONFLICT");
                // the stored draft holds the winner's document, in the version row and in the rule rows
                RuleSetVersion stored = versions.find("card-fee", number, false).orElseThrow();
                assertThat(stored.lockVersion()).isEqualTo(1L);
                assertThat(stored.canonicalDocument()).contains("\"" + winners.getFirst() + "\"");
                assertThat(jdbc.sql("""
                        SELECT r.then_expr_json->>'fee' FROM rle_rule r
                        JOIN rle_version v ON v.tenant_id = r.tenant_id AND v.id = r.version_id
                        WHERE r.tenant_id = 'bank-a' AND v.version_num = :n AND r.rule_id = 'R2'
                        """).param("n", number).query(String.class).single()).isEqualTo(winners.getFirst());
            }
        }
    }

    @Test
    void RLE_028_version_diff_lists_added_changed_removed_and_moved_rows() {
        // Given card-fee v1 published (R2 30000 VND) and v2 with R2 at 0 VND and R6 before R3, in two tenants
        for (String tenant : new String[] {"bank-a", "bank-b"}) {
            who.set(tenant, "lan");
            versions.importRuleSet(ImportRequest.yaml(withR2("30000 VND")));
            versions.importVersion("card-fee", ImportRequest.yaml(new String(withR2("0 VND"), StandardCharsets.UTF_8)
                    .replace("  - id: R3", "  - id: R6\n    when: { tier: \"== 'PLATINUM'\" }\n    then: { fee: \"0 VND\" }\n"
                            + "  - id: R3").getBytes(StandardCharsets.UTF_8)));
        }
        who.set("bank-b", "lan");
        versions.importVersion("card-fee", ImportRequest.yaml(withR2("99 VND")));
        long outbox = count("rle_decision_outbox");

        // When bob of bank-a diffs v2 against v1, read back from JSONB
        who.set("bank-a", "bob");
        VersionDiff diff = versions.diff("card-fee", 2, 1);

        // Then R2 changed with money in minor units, R6 added at 3, two rows unchanged; repeat is equal
        assertThat(diff.rows()).containsExactly(
                new RowChange("R2", Change.CHANGED, 2, 2, false, List.of(new FieldChange("/then/fee",
                        "{\"currency\":\"VND\",\"amount\":30000}", "{\"currency\":\"VND\",\"amount\":0}", false))),
                new RowChange("R6", Change.ADDED, 3, null, false, List.of()));
        assertThat(diff.unchangedCount()).isEqualTo(2);
        assertThat(diff.header()).isEmpty();
        assertThat(versions.diff("card-fee", 2, 1)).isEqualTo(diff);
        // bank-b's v3 exists only in bank-b
        assertThatThrownBy(() -> versions.diff("card-fee", 3, 1))
                .isInstanceOf(com.horus.bpm.rules.api.VersionNotFoundException.class);
        // and nothing was written
        assertThat(count("rle_decision_outbox")).isEqualTo(outbox);
    }

    /** V6: only DRAFT_UPDATED rows carry both hashes, and the V4 append-only triggers still hold. */
    @Test
    void RLE_047_draft_updated_rows_carry_both_hashes_and_stay_append_only() {
        who.set("bank-a", "lan");
        RuleSetVersion draft = versions.importRuleSet(ImportRequest.yaml(RuleEnginePostgresIT.cardFee()));
        versions.replaceDraft("card-fee", 1, ImportRequest.yaml(r2Zero()), new WriteOptions("k-601", draft.lockVersion(), null));
        String versionId = jdbc.sql("SELECT id FROM rle_version WHERE tenant_id = 'bank-a'").query(String.class).single();
        String hash = "a".repeat(64);

        // a DRAFT_UPDATED row without hashes, and another action with hashes, are refused by the database
        for (String sql : new String[] {
                "INSERT INTO rle_version_history (tenant_id, id, version_id, action, from_status, to_status, actor, "
                        + "lock_version, occurred_at) VALUES ('bank-a', 'X1', :v, 'DRAFT_UPDATED', 'DRAFT', 'DRAFT', 'mallory', 9, now())",
                "INSERT INTO rle_version_history (tenant_id, id, version_id, action, from_status, to_status, actor, "
                        + "lock_version, occurred_at, canonical_hash, previous_canonical_hash) VALUES ('bank-a', 'X2', :v, "
                        + "'REJECTED', 'PENDING_APPROVAL', 'DRAFT', 'mallory', 9, now(), :h, :h)"}) {
            assertThatThrownBy(() -> jdbc.sql(sql).param("v", versionId).param("h", hash).update()).as(sql)
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
        // and the recorded row can be neither rewritten nor removed
        assertThatThrownBy(() -> jdbc.sql("UPDATE rle_version_history SET canonical_hash = :h WHERE action = 'DRAFT_UPDATED'")
                .param("h", hash).update()).rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM rle_version_history WHERE action = 'DRAFT_UPDATED'").update())
                .rootCause().hasMessageContaining("append-only");
        assertThat(jdbc.sql("SELECT count(*) FROM rle_version_history WHERE action = 'DRAFT_UPDATED'").query(Long.class)
                .single()).isEqualTo(1L);
    }

    // ---------------------------------------------------------------- helpers

    void publishedV1() {
        who.set("bank-a", "lan");
        versions.importRuleSet(ImportRequest.yaml(RuleEnginePostgresIT.cardFee()));
        versions.requestPublish("card-fee", 1, WriteOptions.none());
        who.set("bank-a", "bob");
        versions.approve("card-fee", 1, WriteOptions.none());
    }

    static byte[] withR2(String fee) {
        return new String(RuleEnginePostgresIT.cardFee(), StandardCharsets.UTF_8)
                .replace("then: { fee: \"10000 VND\" }", "then: { fee: \"" + fee + "\" }").getBytes(StandardCharsets.UTF_8);
    }

    static byte[] r2Zero() {
        return withR2("0 VND");
    }

    long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    static DataSource freshDatabase() {
        String name = "draft_" + DATABASES.incrementAndGet();
        JdbcClient.create(dataSource(POSTGRES.getDatabaseName())).sql("CREATE DATABASE " + name).update();
        return dataSource(name);
    }

    static DataSource dataSource(String database) {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database));
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }

    /** A caller per thread: lan authors, bob approves and reads. */
    static final class PerThreadCaller implements TenantResolver, CallerResolver {
        private final ThreadLocal<String[]> current = ThreadLocal.withInitial(() -> new String[] {"bank-a", "lan"});

        void set(String tenant, String user) {
            current.set(new String[] {tenant, user});
        }

        @Override
        public String currentTenantId() {
            return current.get()[0];
        }

        @Override
        public String currentCallerId() {
            return current.get()[1];
        }

        @Override
        public Set<String> currentPermissions() {
            return "bob".equals(current.get()[1])
                    ? Set.of("rules:read", "rules:approve")
                    : Set.of("rules:read", "rules:author");
        }
    }
}
