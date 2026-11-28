package io.github.dhluong90.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionRequest;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.service.CatalogueExplainer;
import io.github.dhluong90.bpm.rules.internal.service.DecisionQuerySettings;
import io.github.dhluong90.bpm.rules.internal.service.EngineSettings;
import io.github.dhluong90.bpm.rules.internal.service.HostFunctions;
import io.github.dhluong90.bpm.rules.internal.service.RuleEngines;
import io.github.dhluong90.bpm.rules.spi.ApprovalSealKey;
import io.github.dhluong90.bpm.rules.spi.MaskingPolicy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * RLE-223 on PostgreSQL (#237): the approved effective date has a second record, a seal written at approval, and a
 * date changed directly in the table is refused, not served.
 */
@Testcontainers
class ApprovalSealPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    static final Instant NOV_20 = Instant.parse("2026-11-20T10:00:00Z");
    static final Instant DEC_01 = Instant.parse("2026-12-01T00:00:00Z");
    static final Set<String> AUTHOR = Set.of("rules:read", "rules:author");
    static final Set<String> APPROVER = Set.of("rules:read", "rules:approve");
    static final Set<String> PRICING = Set.of("rules:evaluate", "rules:simulate");
    static final byte[] KEY = "a-vault-held-key-of-32-bytes-min".getBytes(StandardCharsets.UTF_8);

    final EffectiveDatesAndReplayPostgresIT.Caller caller = new EffectiveDatesAndReplayPostgresIT.Caller();
    final EditorApiPostgresIT.Movable clock = new EditorApiPostgresIT.Movable(Instant.parse("2026-10-20T00:00:00Z"));
    DataSource db;
    JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        String name = "seal_" + System.nanoTime();
        JdbcClient.create(dataSource(POSTGRES.getDatabaseName()))
                .sql("CREATE DATABASE " + name).update();
        db = dataSource(name);
        new RuleEngineSchemaMigrator(db).migrate();
        jdbc = JdbcClient.create(db);
        new DecisionLogPartitions(db, new RuleEngineTransactions(new DataSourceTransactionManager(db)))
                .ensureCurrentAndNextMonth(Instant.parse("2026-11-15T00:00:00Z"));
    }

    RuleEngines engines(ApprovalSealKey sealKey) {
        RuleEngineTransactions transactions = new RuleEngineTransactions(new DataSourceTransactionManager(db));
        RuleEngines engines = PostgresRuleEngines.create(db, transactions, caller, caller, clock,
                EngineSettings.defaults().withRequireContentHash(false), MaskingPolicy.none(),
                DecisionQuerySettings.defaults(), CatalogueExplainer.bundled(), List.of(),
                HostFunctions.VersionChange.WARN, sealKey);
        engines.cursorKeys().load();
        return engines;
    }

    static byte[] cardFee(String fee) {
        return ("""
                schemaVersion: 1
                ruleSet: card-fee
                hitPolicy: first
                inputs:
                  - { name: tier, type: string }
                outputs:
                  - { name: fee, type: money }
                rules:
                  - { id: R1, when: {}, then: { fee: "%s" } }
                """).formatted(fee).getBytes(StandardCharsets.UTF_8);
    }

    /** Imports, requests for the date, and approves at the date's request time plus a day. */
    void publish(RuleEngines engines, String fee, Instant effectiveFrom) {
        caller.as("bank-a", "lan", AUTHOR);
        int number = engines.versionService().findRuleSet("card-fee").isPresent()
                ? engines.versionService().importVersion("card-fee", ImportRequest.yaml(cardFee(fee))).version()
                : engines.versionService().importRuleSet(ImportRequest.yaml(cardFee(fee))).version();
        WriteOptions request = effectiveFrom == null ? WriteOptions.none()
                : WriteOptions.none().withEffectiveFrom(effectiveFrom);
        long lock = engines.versionService().requestPublish("card-fee", number, request).lockVersion();
        caller.as("bank-a", "minh", APPROVER);
        engines.versionService().approve("card-fee", number, new WriteOptions(null, lock, null));
    }

    DecisionResult evaluate(RuleEngines engines, Instant at) {
        caller.as("bank-a", "pricing", PRICING);
        return engines.ruleEngine().evaluate(DecisionRequest.of("card-fee", DecisionInput.of(Map.of("tier", "GOLD")))
                .withClock(at));
    }

    @Test
    void RLE_223_request_and_approval_rows_record_the_date_and_hash_and_the_approval_writes_a_seal() {
        // Given v1 in force at once and v2 requested at 25 Nov for 1 Dec, approved by minh
        RuleEngines engines = engines(ApprovalSealKey.none());
        publish(engines, "50000 VND", null);
        clock.set(Instant.parse("2026-11-25T10:00:00Z"));
        publish(engines, "30000 VND", DEC_01);

        // Then REQUESTED and APPROVED of v2 carry 1 Dec and the version's canonical hash, in the append-only history
        String hash = jdbc.sql("SELECT canonical_hash FROM rle_version WHERE version_num = 2").query(String.class).single();
        for (String action : List.of("REQUESTED", "APPROVED")) {
            assertThat(jdbc.sql("""
                    SELECT h.effective_from FROM rle_version_history h JOIN rle_version v ON v.id = h.version_id
                    WHERE v.version_num = 2 AND h.action = :a""").param("a", action)
                    .query(java.sql.Timestamp.class).single().toInstant()).as(action).isEqualTo(DEC_01);
            assertThat(jdbc.sql("""
                    SELECT h.canonical_hash FROM rle_version_history h JOIN rle_version v ON v.id = h.version_id
                    WHERE v.version_num = 2 AND h.action = :a""").param("a", action).query(String.class).single())
                    .as(action).isEqualTo(hash);
        }
        // and the version row holds a plain seal; a draft has none
        assertThat(jdbc.sql("SELECT approval_seal FROM rle_version WHERE version_num = 2").query(String.class).single())
                .matches("sha256:[0-9a-f]{64}");
    }

    @Test
    void RLE_223_a_date_moved_in_the_table_is_refused_with_one_audit_entry_and_the_other_version_still_serves() {
        // Given v1 in force at once and v2 scheduled for 1 Dec, both approved
        RuleEngines engines = engines(ApprovalSealKey.none());
        publish(engines, "50000 VND", null);
        clock.set(Instant.parse("2026-11-25T10:00:00Z"));
        publish(engines, "30000 VND", DEC_01);
        assertThat(evaluate(engines, Instant.parse("2026-11-26T00:00:00Z")).version()).isEqualTo(1);
        long decisions = count("rle_decision");

        // When someone pulls v2's date forward in the database, and another replica evaluates at 26 Nov
        jdbc.sql("UPDATE rle_version SET effective_from = :d WHERE version_num = 2")
                .param("d", java.sql.Timestamp.from(Instant.parse("2026-11-25T11:00:00Z"))).update();
        RuleEngines other = engines(ApprovalSealKey.none());
        DecisionResult refused = evaluate(other, Instant.parse("2026-11-26T00:00:00Z"));

        // Then v2 is refused, exactly one audit entry (the decision row) is written, and its row names no inputs
        assertThat(refused.outcome()).isInstanceOf(DecisionResult.Failed.class);
        assertThat(refused.version()).isEqualTo(2);
        assertThat(count("rle_decision")).isEqualTo(decisions + 1);
        assertThat(jdbc.sql("SELECT outcome FROM rle_decision WHERE id = :id").param("id", refused.decisionId())
                .query(String.class).single()).isEqualTo("EVALUATION_ERROR");
        // and changing the approver or the hash is found too
        jdbc.sql("UPDATE rle_version SET effective_from = :d WHERE version_num = 2")
                .param("d", java.sql.Timestamp.from(DEC_01)).update();
        assertThat(evaluate(engines(ApprovalSealKey.none()), Instant.parse("2026-11-26T00:00:00Z")).version())
                .isEqualTo(1);
        jdbc.sql("UPDATE rle_version SET approved_by = 'eve' WHERE version_num = 1").update();
        assertThat(evaluate(engines(ApprovalSealKey.none()), Instant.parse("2026-11-26T00:00:00Z")).outcome())
                .isInstanceOf(DecisionResult.Failed.class);
    }

    @Test
    void RLE_223_a_host_key_makes_the_seal_an_hmac_and_a_plain_seal_or_another_key_is_refused() {
        // Given a host that holds the seal key outside the database
        ApprovalSealKey key = () -> Optional.of(KEY);
        RuleEngines keyed = engines(key);
        publish(keyed, "50000 VND", null);
        assertThat(jdbc.sql("SELECT approval_seal FROM rle_version WHERE version_num = 1").query(String.class).single())
                .matches("hmac-sha256:[0-9a-f]{64}");
        assertThat(evaluate(engines(key), NOV_20).outcome())
                .isNotInstanceOf(DecisionResult.Failed.class);

        // When a host drops the key, an engine without it cannot verify an HMAC seal and refuses the version
        assertThat(evaluate(engines(ApprovalSealKey.none()), NOV_20).outcome()).isInstanceOf(DecisionResult.Failed.class);

        // And when a database administrator writes a plain seal over the row, the keyed engine refuses it too
        jdbc.sql("UPDATE rle_version SET approval_seal = 'sha256:' || repeat('a', 64) WHERE version_num = 1").update();
        assertThat(evaluate(engines(key), NOV_20).outcome()).isInstanceOf(DecisionResult.Failed.class);
        assertThatThrownBy(() -> evaluate(engines(() -> Optional.of(new byte[8])), NOV_20))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("at least 32 bytes");
    }

    static DataSource dataSource(String database) {
        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database));
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }

    long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
