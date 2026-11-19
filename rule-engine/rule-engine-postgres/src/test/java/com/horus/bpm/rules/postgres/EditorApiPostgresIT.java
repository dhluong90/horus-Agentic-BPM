package com.horus.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.horus.bpm.rules.api.ImportRequest;
import com.horus.bpm.rules.api.LifecycleAction;
import com.horus.bpm.rules.api.Page;
import com.horus.bpm.rules.api.PageRequest;
import com.horus.bpm.rules.api.RuleSetNotFoundException;
import com.horus.bpm.rules.api.RuleSetSummary;
import com.horus.bpm.rules.api.ValidationFailedException;
import com.horus.bpm.rules.api.VersionHistoryEntry;
import com.horus.bpm.rules.api.VersionService;
import com.horus.bpm.rules.api.VersionStatus;
import com.horus.bpm.rules.api.VersionSummary;
import com.horus.bpm.rules.api.WriteOptions;
import com.horus.bpm.rules.internal.service.EngineSettings;
import com.horus.bpm.rules.internal.service.RuleEngines;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
 * The authoring API of #147 on PostgreSQL: list SQL (keyset, effective-version selection, latest change),
 * history rows of every transition, the rejection in one transaction, and tenant scope. One fresh database
 * per test; the clock moves from 1 to 10 December 2026.
 */
@Testcontainers
class EditorApiPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static final Instant DEC_01 = Instant.parse("2026-12-01T00:00:00Z");
    static final Instant DEC_10 = Instant.parse("2026-12-10T03:00:00Z");
    private static final AtomicInteger DATABASES = new AtomicInteger();

    final RuleEnginePostgresIT.Caller caller = new RuleEnginePostgresIT.Caller();
    final Movable clock = new Movable(DEC_01);
    JdbcClient jdbc;
    VersionService versions;

    @BeforeEach
    void setUp() {
        DataSource db = freshDatabase();
        new RuleEngineSchemaMigrator(db).migrate();
        RuleEngineTransactions transactions = new RuleEngineTransactions(new DataSourceTransactionManager(db));
        jdbc = JdbcClient.create(db);
        RuleEngines engines = PostgresRuleEngines.create(db, transactions, caller, caller, clock, EngineSettings.defaults());
        versions = engines.versionService();
    }

    @Test
    void RLE_039_rule_sets_are_listed_per_tenant_by_name_with_a_cursor() {
        // Given bank-a: card-fee v1 published 1 Dec, v2 requested 10 Dec; fx-margin and kyc-risk drafts;
        // a future-dated v3 of card-fee approved but not yet effective; bank-b: aml-score
        publish("card-fee", null);
        imported("kyc-risk");
        imported("fx-margin");
        caller.as("bank-a", "lan");
        versions.importVersion("card-fee", ImportRequest.yaml(renamed("card-fee", "2027-01-01T00:00:00Z")));
        caller.as("bank-a", "lan");
        versions.requestPublish("card-fee", 2, WriteOptions.none());
        caller.as("bank-a", "minh");
        versions.approve("card-fee", 2, WriteOptions.none());
        caller.as("bank-a", "lan");
        versions.importVersion("card-fee", ImportRequest.yaml(renamed("card-fee", null)));
        clock.set(DEC_10);
        versions.requestPublish("card-fee", 3, WriteOptions.none());
        caller.as("bank-b", "lan");
        versions.importRuleSet(ImportRequest.yaml(renamed("aml-score", null)));

        // When bank-a reads two at a time
        caller.as("bank-a", "dan");
        Page<RuleSetSummary> first = versions.listRuleSets(new PageRequest(2, null));
        Page<RuleSetSummary> second = versions.listRuleSets(new PageRequest(2, first.nextCursor()));

        // Then v2 is published but effective only in January, so v1 is the published version now
        assertThat(first.items()).containsExactly(
                new RuleSetSummary("card-fee", 3, VersionStatus.PENDING_APPROVAL, 1, DEC_10),
                new RuleSetSummary("fx-margin", 1, VersionStatus.DRAFT, null, DEC_01));
        assertThat(second.items()).extracting(RuleSetSummary::ruleSet).containsExactly("kyc-risk");
        assertThat(second.nextCursor()).isNull();
        assertThat(versions.findRuleSet("card-fee")).contains(first.items().getFirst());

        // and bank-b sees only aml-score, and not bank-a's card-fee
        caller.as("bank-b", "dan");
        assertThat(versions.listRuleSets(PageRequest.first()).items()).extracting(RuleSetSummary::ruleSet)
                .containsExactly("aml-score");
        assertThat(versions.findRuleSet("card-fee")).isEmpty();
    }

    @Test
    void RLE_040_versions_are_listed_newest_first_with_their_actors() {
        publish("card-fee", null);
        caller.as("bank-a", "lan");
        versions.importVersion("card-fee", ImportRequest.yaml(renamed("card-fee", null)));
        versions.requestPublish("card-fee", 2, WriteOptions.none());
        versions.importVersion("card-fee", ImportRequest.yaml(renamed("card-fee", null)));

        caller.as("bank-a", "minh");
        Page<VersionSummary> first = versions.listVersions("card-fee", new PageRequest(2, null));
        Page<VersionSummary> second = versions.listVersions("card-fee", new PageRequest(2, first.nextCursor()));

        assertThat(first.items()).extracting(VersionSummary::version, VersionSummary::status,
                VersionSummary::createdBy, VersionSummary::requestedBy)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(3, VersionStatus.DRAFT, "lan", null),
                        org.assertj.core.groups.Tuple.tuple(2, VersionStatus.PENDING_APPROVAL, "lan", "lan"));
        assertThat(second.items()).extracting(VersionSummary::version, VersionSummary::approvedBy, VersionSummary::status)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, "minh", VersionStatus.PUBLISHED));
        assertThat(second.nextCursor()).isNull();

        caller.as("bank-b", "minh");
        assertThatThrownBy(() -> versions.listVersions("card-fee", PageRequest.first()))
                .isInstanceOf(RuleSetNotFoundException.class);
    }

    @Test
    void RLE_025_reject_returns_pending_version_to_draft_with_reason() {
        // Given card-fee v2 pending, requested by lan
        publish("card-fee", null);
        caller.as("bank-a", "lan");
        versions.importVersion("card-fee", ImportRequest.yaml(renamed("card-fee", null)));
        long lock = versions.requestPublish("card-fee", 2, WriteOptions.none()).lockVersion();
        long events = count("rle_decision_outbox");

        // When minh rejects with k-201 twice
        caller.as("bank-a", "minh");
        WriteOptions reject = new WriteOptions("k-201", lock, "R2 waiver needs pricing committee minutes");
        versions.reject("card-fee", 2, reject);
        versions.reject("card-fee", 2, reject);

        // Then the row is a draft with the request cleared, one history row and one event
        assertThat(jdbc.sql("""
                SELECT v.status || ' ' || v.lock_version || ' ' || coalesce(v.requested_by, '-') || ' '
                  || coalesce(v.requested_at::text, '-')
                FROM rle_version v JOIN rle_rule_set s ON s.tenant_id = v.tenant_id AND s.id = v.rule_set_id
                WHERE v.tenant_id = 'bank-a' AND s.name = 'card-fee' AND v.version_num = 2
                """).query(String.class).single()).isEqualTo("DRAFT " + (lock + 1) + " - -");
        assertThat(versions.history("card-fee", 2)).extracting(VersionHistoryEntry::action, VersionHistoryEntry::actor)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(LifecycleAction.CREATED, "lan"),
                        org.assertj.core.groups.Tuple.tuple(LifecycleAction.REQUESTED, "lan"),
                        org.assertj.core.groups.Tuple.tuple(LifecycleAction.REJECTED, "minh"));
        assertThat(jdbc.sql("SELECT reason || ' ' || from_status || ' ' || to_status FROM rle_version_history "
                        + "WHERE tenant_id = 'bank-a' AND action = 'REJECTED'").query(String.class).single())
                .isEqualTo("R2 waiver needs pricing committee minutes PENDING_APPROVAL DRAFT");
        assertThat(count("rle_decision_outbox")).isEqualTo(events + 1);
        assertThat(jdbc.sql("SELECT payload->>'rejectedBy' || ' ' || (payload->>'reason') FROM rle_decision_outbox "
                        + "WHERE tenant_id = 'bank-a' AND payload->>'eventType' = 'rules.version.rejected.v1'")
                .query(String.class).single()).isEqualTo("minh R2 waiver needs pricing committee minutes");
        assertThat(jdbc.sql("SELECT count(*) FROM rle_idempotency WHERE tenant_id = 'bank-a' AND operation = 'reject'")
                .query(Long.class).single()).isEqualTo(1L);
    }

    /**
     * JSONB reorders keys. A command answered from a row read back (approve, reject) must carry the same canonical
     * document as its idempotent replay, which is rebuilt from the recorded response.
     */
    @Test
    void RLE_014_a_replay_of_a_command_on_a_stored_version_returns_the_same_canonical_document() {
        caller.as("bank-a", "lan");
        com.horus.bpm.rules.api.RuleSetVersion imported = versions.importRuleSet(ImportRequest.yaml(renamed("card-fee", null)));
        versions.requestPublish("card-fee", 1, WriteOptions.none());
        caller.as("bank-a", "minh");

        com.horus.bpm.rules.api.RuleSetVersion approved = versions.approve("card-fee", 1, WriteOptions.idempotencyKey("k-301"));
        com.horus.bpm.rules.api.RuleSetVersion replay = versions.approve("card-fee", 1, WriteOptions.idempotencyKey("k-301"));

        assertThat(approved.canonicalDocument()).isEqualTo(imported.canonicalDocument());
        assertThat(replay).isEqualTo(approved);
        assertThat(versions.find("card-fee", 1, false).orElseThrow().canonicalDocument())
                .isEqualTo(imported.canonicalDocument());
        assertThat(com.horus.bpm.rules.internal.json.Hashes.sha256(approved.canonicalDocument()))
                .isEqualTo(approved.canonicalHash());
    }

    /** V3 backfills one CREATED row per version that existed before it, so their history starts at creation. */
    @Test
    void RLE_025_versions_created_before_the_history_table_get_their_created_row() {
        // Given a database at V2 with card-fee v1 by lan (published) and v2 written before authors were recorded
        DataSource db = freshDatabase();
        org.flywaydb.core.Flyway.configure().dataSource(db).table("rle_schema_history")
                .locations(RuleEngineSchemaMigrator.MIGRATION_LOCATION).baselineOnMigrate(true).baselineVersion("0")
                .target("2").load().migrate();
        JdbcClient old = JdbcClient.create(db);
        old.sql("INSERT INTO rle_rule_set (tenant_id, id, name, created_at) VALUES "
                + "('bank-a', '01KD00000000000000000RS001', 'card-fee', '2026-11-01T00:00:00Z')").update();
        String insert = """
                INSERT INTO rle_version (tenant_id, id, rule_set_id, version_num, status, hit_policy, canonical_json,
                  canonical_hash, source_format, source_document, source_hash, lock_version, requested_by, approved_by,
                  created_by, created_at)
                VALUES ('bank-a', :id, '01KD00000000000000000RS001', :num, :status, 'FIRST', '{}', :hash, 'YAML', '',
                  :hash, :lock, :requestedBy, :approvedBy, :createdBy, CAST(:at AS timestamptz))
                """;
        String hash = "0".repeat(64);
        old.sql(insert).param("id", "01KD00000000000000000VS001").param("num", 1).param("status", "PUBLISHED")
                .param("hash", hash).param("lock", 2).param("requestedBy", "lan").param("approvedBy", "minh")
                .param("createdBy", "lan").param("at", "2026-11-01T00:00:00Z").update();
        old.sql(insert).param("id", "01KD00000000000000000VS002").param("num", 2).param("status", "DRAFT")
                .param("hash", hash).param("lock", 0).param("requestedBy", null).param("approvedBy", null)
                .param("createdBy", null).param("at", "2026-11-05T00:00:00Z").update();

        // When V3 runs
        new RuleEngineSchemaMigrator(db).migrate();

        // Then each version has exactly one CREATED row: actor the author, or system when none was recorded
        assertThat(old.sql("""
                SELECT string_agg(version_id || ' ' || action || ' ' || to_status || ' ' || actor || ' ' || lock_version
                  || ' ' || to_char(occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD'), ', ' ORDER BY version_id)
                FROM rle_version_history WHERE tenant_id = 'bank-a'
                """).query(String.class).single()).isEqualTo(
                "01KD00000000000000000VS001 CREATED DRAFT lan 0 2026-11-01, "
                        + "01KD00000000000000000VS002 CREATED DRAFT system 0 2026-11-05");
        assertThat(old.sql("SELECT count(*) FROM rle_version_history WHERE from_status IS NOT NULL")
                .query(Long.class).single()).isZero();
    }

    @Test
    void RLE_042_money_output_without_currency_is_refused_at_import() {
        caller.as("bank-a", "lan");
        byte[] bad = new String(RuleEnginePostgresIT.cardFee(), StandardCharsets.UTF_8)
                .replace("then: { fee: \"10000 VND\" }", "then: { fee: \"30000\" }").getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> versions.importRuleSet(ImportRequest.yaml(bad)))
                .isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.violations())
                        .singleElement().satisfies(v -> assertThat(v.ruleId() + "/" + v.column()).isEqualTo("R2/fee")));
        assertThat(count("rle_rule_set") + count("rle_version") + count("rle_version_history")).isZero();
    }

    void publish(String name, String effectiveFrom) {
        caller.as("bank-a", "lan");
        versions.importRuleSet(ImportRequest.yaml(renamed(name, effectiveFrom)));
        versions.requestPublish(name, 1, WriteOptions.none());
        caller.as("bank-a", "minh");
        versions.approve(name, 1, WriteOptions.none());
    }

    void imported(String name) {
        caller.as("bank-a", "lan");
        versions.importRuleSet(ImportRequest.yaml(renamed(name, null)));
    }

    static byte[] renamed(String name, String effectiveFrom) {
        String yaml = new String(RuleEnginePostgresIT.cardFee(), StandardCharsets.UTF_8)
                .replace("ruleSet: card-fee", "ruleSet: " + name);
        if (effectiveFrom != null) {
            yaml = yaml.replace("hitPolicy: first", "hitPolicy: first\neffectiveFrom: \"" + effectiveFrom + "\"");
        }
        return yaml.getBytes(StandardCharsets.UTF_8);
    }

    long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    static DataSource freshDatabase() {
        String name = "editor_" + DATABASES.incrementAndGet();
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

    /** A clock the test moves between steps. */
    static final class Movable extends Clock {
        private volatile Instant now;

        Movable(Instant start) {
            this.now = start;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
