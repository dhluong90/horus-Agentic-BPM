package io.github.dhluong90.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.InvalidStateException;
import io.github.dhluong90.bpm.rules.api.LifecycleAction;
import io.github.dhluong90.bpm.rules.api.PageRequest;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.api.VersionSummary;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.service.EngineSettings;
import io.github.dhluong90.bpm.rules.internal.service.RuleEngines;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * Discarding a draft on PostgreSQL (RLE-098, V28): the status and action lists accept DISCARDED, nothing is deleted,
 * the version list and the rule set summary skip the discarded version, and its history row is append-only.
 */
@Testcontainers
class DiscardDraftPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-12-01T00:00:00Z"), ZoneOffset.UTC);

    final DraftReplaceAndDiffPostgresIT.PerThreadCaller who = new DraftReplaceAndDiffPostgresIT.PerThreadCaller();
    JdbcClient jdbc;
    VersionService versions;

    @BeforeEach
    void setUp() {
        DataSource db = freshDatabase();
        new RuleEngineSchemaMigrator(db).migrate();
        jdbc = JdbcClient.create(db);
        RuleEngines engines = PostgresRuleEngines.create(db,
                new RuleEngineTransactions(new DataSourceTransactionManager(db)), who, who, CLOCK,
                EngineSettings.defaults().withRequireContentHash(false).withDraftAuthoring(true));
        engines.cursorKeys().load();
        versions = engines.versionService();
    }

    static DataSource freshDatabase() {
        String name = "discard_" + System.nanoTime();
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

    @Test
    void RLE_098_a_discarded_draft_is_kept_with_its_history_and_left_out_of_the_lists() {
        // Given card-fee v1 published, v2 a draft by lan,
        publishedV1();
        who.set("bank-a", "lan");
        RuleSetVersion draft = versions.importVersion("card-fee", ImportRequest.yaml(RuleEnginePostgresIT.cardFee()));
        long versionRows = count("rle_version");
        long ruleRows = count("rle_rule");

        // When lan discards v2 with k-910, twice
        WriteOptions options = new WriteOptions("k-910", draft.lockVersion(), "Wrong base");
        RuleSetVersion discarded = versions.discard("card-fee", 2, options);
        RuleSetVersion replay = versions.discard("card-fee", 2, options);

        // Then v2 is DISCARDED, the repeat is the same answer, and no row of the version or its rules was deleted
        assertThat(replay).isEqualTo(discarded);
        assertThat(discarded.status()).isEqualTo(VersionStatus.DISCARDED);
        assertThat(count("rle_version")).isEqualTo(versionRows);
        assertThat(count("rle_rule")).isEqualTo(ruleRows);
        assertThat(jdbc.sql("""
                SELECT v.status || ' ' || v.lock_version || ' ' || v.created_by || ' ' || v.canonical_hash
                FROM rle_version v WHERE v.tenant_id = 'bank-a' AND v.version_num = 2
                """).query(String.class).single()).isEqualTo("DISCARDED " + discarded.lockVersion() + " lan "
                + draft.canonicalHash());
        // and one DISCARDED history row records who, why and the statuses; one event is in the outbox
        assertThat(jdbc.sql("""
                SELECT actor || ' ' || reason || ' ' || from_status || ' ' || to_status
                FROM rle_version_history WHERE tenant_id = 'bank-a' AND action = 'DISCARDED'
                """).query(String.class).single()).isEqualTo("lan Wrong base DRAFT DISCARDED");
        assertThat(versions.history("card-fee", 2)).extracting(e -> e.action())
                .containsExactly(LifecycleAction.CREATED, LifecycleAction.DISCARDED);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM rle_decision_outbox
                WHERE tenant_id = 'bank-a' AND payload->>'eventType' = 'rules.version.discarded.v1'
                  AND payload->>'discardedBy' = 'lan'
                """).query(Long.class).single()).isEqualTo(1L);
        // and the version list and the summary skip it, and the number is not used again
        assertThat(versions.listVersions("card-fee", PageRequest.first()).items())
                .extracting(VersionSummary::version).containsExactly(1);
        assertThat(versions.listRuleSets(PageRequest.first()).items()).singleElement().satisfies(summary -> {
            assertThat(summary.latestVersion()).isEqualTo(1);
            assertThat(summary.latestStatus()).isEqualTo(VersionStatus.PUBLISHED);
        });
        assertThat(versions.importVersion("card-fee", ImportRequest.yaml(RuleEnginePostgresIT.cardFee())).version())
                .isEqualTo(3);
        // and the history row cannot be changed or removed
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM rle_version_history WHERE action = 'DISCARDED'").update())
                .rootCause().hasMessageContaining("append-only");
        // and a discarded version is not a draft any more
        assertThatThrownBy(() -> versions.discard("card-fee", 2, new WriteOptions("k-911", discarded.lockVersion(), null)))
                .isInstanceOf(InvalidStateException.class);
    }

    @Test
    void RLE_098_a_published_version_is_never_discarded() {
        publishedV1();
        who.set("bank-a", "lan");
        assertThatThrownBy(() -> versions.discard("card-fee", 1, new WriteOptions("k-912", 2L, null)))
                .isInstanceOf(InvalidStateException.class);
        assertThat(jdbc.sql("SELECT status FROM rle_version WHERE version_num = 1").query(String.class).single())
                .isEqualTo("PUBLISHED");
        assertThat(count("rle_version_history")).isEqualTo(3L);
    }

    long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    void publishedV1() {
        who.set("bank-a", "lan");
        versions.importRuleSet(ImportRequest.yaml(RuleEnginePostgresIT.cardFee()));
        versions.requestPublish("card-fee", 1, WriteOptions.none());
        who.set("bank-a", "bob");
        versions.approve("card-fee", 1, new WriteOptions(null, 1L, null));
    }
}
