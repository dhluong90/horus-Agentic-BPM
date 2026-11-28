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
    static final ApprovalSealKey.Key K1 = new ApprovalSealKey.Key("k1", KEY);
    static final ApprovalSealKey.Key K2 = new ApprovalSealKey.Key("k2",
            "a-second-vault-key-of-32-bytes-!".getBytes(StandardCharsets.UTF_8));

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
    void RLE_223_an_approval_at_a_nanosecond_instant_is_sealed_over_the_stored_microseconds_and_still_served() {
        // Given a clock with nanoseconds, which PostgreSQL would round to microseconds when it stores the approval
        RuleEngines engines = engines(ApprovalSealKey.none());
        clock.set(Instant.parse("2026-11-25T10:00:00.653124694Z"));
        publish(engines, "50000 VND", null);

        // When the version is evaluated, Then the seal verifies against the stored row and the rule set is served
        assertThat(evaluate(engines, Instant.parse("2026-11-26T00:00:00Z")).version()).isEqualTo(1);
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
        // Given a host that holds the seal key k1 outside the database
        ApprovalSealKey key = ApprovalSealKey.of(K1);
        RuleEngines keyed = engines(key);
        publish(keyed, "50000 VND", null);
        assertThat(seal(1)).matches("hmac-sha256:k1:[0-9a-f]{64}");
        assertThat(evaluate(engines(key), NOV_20).outcome())
                .isNotInstanceOf(DecisionResult.Failed.class);

        // When a host drops the key, an engine without it cannot verify an HMAC seal and refuses the version
        assertThat(evaluate(engines(ApprovalSealKey.none()), NOV_20).outcome()).isInstanceOf(DecisionResult.Failed.class);

        // And when a database administrator writes a plain seal over the row, the keyed engine refuses it too
        jdbc.sql("UPDATE rle_version SET approval_seal = 'sha256:' || repeat('a', 64) WHERE version_num = 1").update();
        assertThat(evaluate(engines(key), NOV_20).outcome()).isInstanceOf(DecisionResult.Failed.class);
        assertThatThrownBy(() -> new ApprovalSealKey.Key("short", new byte[8]))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("at least 32 bytes");
    }

    @Test
    void RLE_223_rotating_the_key_keeps_approved_versions_served_and_a_second_person_reseals_them() {
        // Given v1 approved under k1
        publish(engines(ApprovalSealKey.of(K1)), "50000 VND", null);
        ApprovalSealKey rotated = new ApprovalSealKey() {
            public Optional<Key> current() {
                return Optional.of(K2);
            }

            public List<Key> previous() {
                return List.of(K1);
            }
        };

        // When the host rotates to k2 and lists k1 as previous, v1 is still served
        RuleEngines engines = engines(rotated);
        assertThat(evaluate(engines, NOV_20).outcome()).isNotInstanceOf(DecisionResult.Failed.class);
        // but a host that dropped k1 from the ring refuses it
        assertThat(evaluate(engines(ApprovalSealKey.of(K2)), NOV_20).outcome()).isInstanceOf(DecisionResult.Failed.class);

        // The approver and the author cannot re-seal; a third person can
        caller.as("bank-a", "minh", APPROVER);
        assertThatThrownBy(() -> engines.versionService().reseal("card-fee", 1, WriteOptions.none()))
                .isInstanceOf(io.github.dhluong90.bpm.rules.api.SelfApprovalForbiddenException.class);
        caller.as("bank-a", "omar", APPROVER);
        engines.versionService().reseal("card-fee", 1, new WriteOptions(null, null, "rotation to k2"));

        // Then the seal is under k2, one RESEALED row names omar, and k2 alone now serves v1
        assertThat(seal(1)).matches("hmac-sha256:k2:[0-9a-f]{64}");
        assertThat(jdbc.sql("SELECT actor FROM rle_version_history WHERE action = 'RESEALED'")
                .query(String.class).single()).isEqualTo("omar");
        assertThat(evaluate(engines(ApprovalSealKey.of(K2)), NOV_20).outcome())
                .isNotInstanceOf(DecisionResult.Failed.class);

        // and a second re-seal changes nothing and adds no history row
        caller.as("bank-a", "omar", APPROVER);
        engines.versionService().reseal("card-fee", 1, WriteOptions.none());
        assertThat(count("rle_version_history WHERE action = 'RESEALED'")).isEqualTo(1);
    }

    @Test
    void RLE_223_a_version_changed_in_the_table_is_not_resealed() {
        RuleEngines engines = engines(ApprovalSealKey.of(K1));
        publish(engines, "50000 VND", null);
        jdbc.sql("UPDATE rle_version SET effective_from = effective_from - interval '1 day' WHERE version_num = 1")
                .update();
        caller.as("bank-a", "omar", APPROVER);
        assertThatThrownBy(() -> engines.versionService().reseal("card-fee", 1, WriteOptions.none()))
                .hasMessageContaining("SEAL_MISMATCH");
        assertThat(seal(1)).matches("hmac-sha256:k1:[0-9a-f]{64}");
    }

    @Test
    void RLE_223_switching_a_key_on_after_versions_were_approved_needs_the_window_and_a_reseal() {
        // Given v1 approved with plain seals
        publish(engines(ApprovalSealKey.none()), "50000 VND", null);
        assertThat(seal(1)).startsWith("sha256:");

        // When the host sets a key, v1 is refused: a plain seal is not trusted next to a key
        assertThat(evaluate(engines(ApprovalSealKey.of(K1)), NOV_20).outcome()).isInstanceOf(DecisionResult.Failed.class);

        // With the window open it is served, and a second person re-seals it under k1
        ApprovalSealKey window = new ApprovalSealKey() {
            public Optional<Key> current() {
                return Optional.of(K1);
            }

            public boolean acceptUnkeyedSeals() {
                return true;
            }
        };
        RuleEngines engines = engines(window);
        java.util.concurrent.atomic.AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger();
        engines.meters().bind(new io.github.dhluong90.bpm.rules.internal.service.EngineMeters.Recorder() {
            public void evaluation(io.github.dhluong90.bpm.rules.internal.service.EngineMeters.Outcome o, String c,
                    long n) {
            }

            public void retired(io.github.dhluong90.bpm.rules.internal.service.EngineMeters.RetireReason r, int n) {
            }

            public void unkeyedSealAccepted() {
                accepted.incrementAndGet();
            }
        });
        assertThat(evaluate(engines, NOV_20).outcome()).isNotInstanceOf(DecisionResult.Failed.class);
        // the window is counted (and logged without the seal value) for each version served on an unkeyed seal
        assertThat(accepted.get()).isEqualTo(1);
        caller.as("bank-a", "omar", APPROVER);
        engines.versionService().reseal("card-fee", 1, WriteOptions.none());

        // Then the window can close
        assertThat(seal(1)).matches("hmac-sha256:k1:[0-9a-f]{64}");
        assertThat(evaluate(engines(ApprovalSealKey.of(K1)), NOV_20).outcome())
                .isNotInstanceOf(DecisionResult.Failed.class);
    }

    @Test
    void RLE_223_an_approved_version_with_a_null_seal_is_refused_in_plain_mode() {
        // Given v1 approved with a plain seal
        publish(engines(ApprovalSealKey.none()), "50000 VND", null);
        // the database itself refuses a NULL seal on an approved version
        assertThatThrownBy(() -> jdbc.sql("UPDATE rle_version SET approval_seal = NULL WHERE version_num = 1").update())
                .hasMessageContaining("rle_version_approval_seal_check");

        // When an administrator drops the constraint, clears the seal and moves the date
        jdbc.sql("ALTER TABLE rle_version DROP CONSTRAINT rle_version_approval_seal_check").update();
        jdbc.sql("UPDATE rle_version SET approval_seal = NULL, effective_from = effective_from - interval '1 day'"
                + " WHERE version_num = 1").update();

        // Then the engine refuses the version: only the marker written by V2 stands for "approved before seals"
        assertThat(evaluate(engines(ApprovalSealKey.none()), NOV_20).outcome()).isInstanceOf(DecisionResult.Failed.class);
    }

    @Test
    void RLE_223_the_pre_seal_marker_cannot_be_written_after_the_migration_and_a_keyed_host_refuses_it() {
        publish(engines(ApprovalSealKey.none()), "50000 VND", null);
        assertThatThrownBy(() -> jdbc.sql("UPDATE rle_version SET approval_seal = 'legacy:pre-seal'"
                + " WHERE version_num = 1").update()).hasMessageContaining("rle_version_legacy_seal_guard");

        // and an approved row cannot be inserted with the marker either
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO rle_version SELECT (jsonb_populate_record(NULL::rle_version, to_jsonb(s)
                  || '{"id":"X0000000000000000000000000","version_num":9,"approval_seal":"legacy:pre-seal"}'::jsonb)).*
                FROM rle_version s WHERE s.version_num = 1""").update())
                .hasMessageContaining("rle_version_legacy_seal_guard");

        // A version the migration marked (simulated by switching the guard off) is served in plain mode, as before
        jdbc.sql("ALTER TABLE rle_version DISABLE TRIGGER rle_version_legacy_seal_guard").update();
        jdbc.sql("UPDATE rle_version SET approval_seal = 'legacy:pre-seal' WHERE version_num = 1").update();
        assertThat(evaluate(engines(ApprovalSealKey.none()), NOV_20).outcome())
                .isNotInstanceOf(DecisionResult.Failed.class);
        // a keyed host refuses it until the window opens and a second person re-seals it
        assertThat(evaluate(engines(ApprovalSealKey.of(K1)), NOV_20).outcome()).isInstanceOf(DecisionResult.Failed.class);
    }

    String seal(int version) {
        return jdbc.sql("SELECT approval_seal FROM rle_version WHERE version_num = :v").param("v", version)
                .query(String.class).single();
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
