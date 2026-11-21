package io.github.dhluong90.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.LifecycleAction;
import io.github.dhluong90.bpm.rules.api.RuleSetNotFoundException;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.SelfApprovalForbiddenException;
import io.github.dhluong90.bpm.rules.api.VersionHistoryEntry;
import io.github.dhluong90.bpm.rules.api.VersionNotFoundException;
import io.github.dhluong90.bpm.rules.api.VersionStatus;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.service.EngineSettings;
import io.github.dhluong90.bpm.rules.internal.service.RuleEngines;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * RLE-062 and RLE-063 on PostgreSQL (#40): a restore keeps the earlier version's canonical document and hash, records
 * {@code copied_from} on the version and its CREATED history row, replays by key, and stays in the caller's tenant.
 */
@Testcontainers
class RestoreVersionPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    static final Set<String> AUTHOR = Set.of("rules:read", "rules:author");
    static final Set<String> APPROVER = Set.of("rules:read", "rules:approve");

    final RetireVersionPostgresIT.Caller caller = new RetireVersionPostgresIT.Caller();
    final EditorApiPostgresIT.Movable clock = new EditorApiPostgresIT.Movable(Instant.parse("2027-01-10T00:00:00Z"));
    JdbcClient jdbc;
    RuleEngines engines;

    @BeforeEach
    void setUp() {
        String name = "restore_" + System.nanoTime();
        JdbcClient.create(dataSource(POSTGRES.getDatabaseName()))
                .sql("CREATE DATABASE " + name).update();
        DataSource db = dataSource(name);
        new RuleEngineSchemaMigrator(db).migrate();
        jdbc = JdbcClient.create(db);
        RuleEngineTransactions transactions = new RuleEngineTransactions(new DataSourceTransactionManager(db));
        engines = PostgresRuleEngines.create(db, transactions, caller, caller, clock, EngineSettings.defaults().withRequireContentHash(false));
        engines.cursorKeys().load();
    }

    static DataSource dataSource(String database) {
        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database));
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }

    static byte[] cardFee(String fee) {
        return ("""
                schemaVersion: 1
                ruleSet: card-fee
                hitPolicy: first
                inputs:
                  - { name: tier, type: string, mask: false }
                outputs:
                  - { name: fee, type: money }
                rules:
                  - { id: R1, when: { tier: "== 'SILVER'" }, then: { fee: "%s" } }
                  - { id: R2, when: {}, then: { fee: "50000 VND" } }
                """).formatted(fee).getBytes(StandardCharsets.UTF_8);
    }

    RuleSetVersion publish(String tenant, byte[] document, Instant approvedAt) {
        caller.as(tenant, "lan", AUTHOR);
        var versions = engines.versionService();
        int number = versions.findRuleSet("card-fee").isPresent()
                ? versions.importVersion("card-fee", ImportRequest.yaml(document)).version()
                : versions.importRuleSet(ImportRequest.yaml(document)).version();
        long reviewed = versions.requestPublish("card-fee", number, WriteOptions.none()).lockVersion();
        clock.set(approvedAt);
        caller.as(tenant, "minh", APPROVER);
        return versions.approve("card-fee", number, new WriteOptions(null, reviewed, null));
    }

    @Test
    void RLE_062_a_restore_is_stored_with_its_source_and_replays_by_key() {
        // Given card-fee v1 (10000 VND) retired by v2 (30000 VND), both published
        var versions = engines.versionService();
        publish("bank-a", cardFee("10000 VND"), Instant.parse("2027-01-11T00:00:00Z"));
        publish("bank-a", cardFee("30000 VND"), Instant.parse("2027-01-20T00:00:00Z"));
        caller.as("bank-a", "lan", AUTHOR);
        RuleSetVersion v1 = versions.find("card-fee", 1, true).orElseThrow();

        // When lan restores v1 under k-601 and repeats it
        RuleSetVersion v3 = versions.copyVersion("card-fee", 1, new WriteOptions("k-601", null, null));
        RuleSetVersion repeat = versions.copyVersion("card-fee", 1, new WriteOptions("k-601", null, null));

        // Then the draft holds v1's document and hash, names v1, and the repeat creates nothing
        assertThat(v3.version()).isEqualTo(3);
        assertThat(v3.status()).isEqualTo(VersionStatus.DRAFT);
        assertThat(v3.canonicalHash()).isEqualTo(v1.canonicalHash());
        assertThat(repeat).isEqualTo(v3);
        assertThat(jdbc.sql("SELECT count(*) FROM rle_version").query(Integer.class).single()).isEqualTo(3);
        assertThat(jdbc.sql("SELECT copied_from FROM rle_version WHERE version_num = 3").query(Integer.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM rle_version WHERE copied_from IS NOT NULL").query(Integer.class)
                .single()).isEqualTo(1);
        RuleSetVersion read = versions.find("card-fee", 3, true).orElseThrow();
        assertThat(read.copiedFrom()).isEqualTo(1);
        assertThat(read.sourceDocument()).isEqualTo(v1.sourceDocument());
        assertThat(read.sourceHash()).isEqualTo(v1.sourceHash());
        List<VersionHistoryEntry> history = versions.history("card-fee", 3);
        assertThat(history).singleElement().satisfies(row -> {
            assertThat(row.action()).isEqualTo(LifecycleAction.CREATED);
            assertThat(row.actor()).isEqualTo("lan");
            assertThat(row.copiedFrom()).isEqualTo(1);
        });
        assertThat(versions.history("card-fee", 1)).allSatisfy(row -> assertThat(row.copiedFrom()).isNull());
        assertThat(jdbc.sql("SELECT count(*) FROM rle_decision_outbox WHERE payload->>'copiedFrom' = '1'")
                .query(Integer.class).single()).isEqualTo(1);

        // And the four-eyes rule treats the restorer as the maker, while another user approves
        long reviewed = versions.requestPublish("card-fee", 3, WriteOptions.none()).lockVersion();
        caller.as("bank-a", "lan", Set.of("rules:read", "rules:author", "rules:approve"));
        assertThatThrownBy(() -> versions.approve("card-fee", 3, new WriteOptions(null, reviewed, null)))
                .isInstanceOf(SelfApprovalForbiddenException.class);
        caller.as("bank-a", "minh", APPROVER);
        assertThat(versions.approve("card-fee", 3, new WriteOptions(null, reviewed, null)).status())
                .isEqualTo(VersionStatus.PUBLISHED);
    }

    @Test
    void RLE_063_a_restore_reads_the_callers_tenant_only_and_a_failure_writes_nothing() {
        // Given card-fee v1 published at bank-a
        var versions = engines.versionService();
        publish("bank-a", cardFee("10000 VND"), Instant.parse("2027-01-11T00:00:00Z"));
        int rows = jdbc.sql("SELECT count(*) FROM rle_version").query(Integer.class).single();
        int history = jdbc.sql("SELECT count(*) FROM rle_version_history").query(Integer.class).single();
        int events = jdbc.sql("SELECT count(*) FROM rle_decision_outbox").query(Integer.class).single();
        int keys = jdbc.sql("SELECT count(*) FROM rle_idempotency").query(Integer.class).single();

        // When a bank-b author restores it, and a bank-a author asks for version 9
        caller.as("bank-b", "lan", AUTHOR);
        assertThatThrownBy(() -> versions.copyVersion("card-fee", 1, new WriteOptions("k-9", null, null)))
                .isInstanceOf(RuleSetNotFoundException.class);
        caller.as("bank-a", "lan", AUTHOR);
        assertThatThrownBy(() -> versions.copyVersion("card-fee", 9, new WriteOptions("k-9", null, null)))
                .isInstanceOf(VersionNotFoundException.class);

        // Then nothing was written: no version, history row, event or idempotency record
        assertThat(jdbc.sql("SELECT count(*) FROM rle_version").query(Integer.class).single()).isEqualTo(rows);
        assertThat(jdbc.sql("SELECT count(*) FROM rle_version_history").query(Integer.class).single())
                .isEqualTo(history);
        assertThat(jdbc.sql("SELECT count(*) FROM rle_decision_outbox").query(Integer.class).single()).isEqualTo(events);
        assertThat(jdbc.sql("SELECT count(*) FROM rle_idempotency").query(Integer.class).single()).isEqualTo(keys);
    }
}
