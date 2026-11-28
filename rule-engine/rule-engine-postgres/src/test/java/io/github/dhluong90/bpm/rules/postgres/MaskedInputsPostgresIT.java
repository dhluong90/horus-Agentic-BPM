package io.github.dhluong90.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionNotFoundException;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.LoggedDecision;
import io.github.dhluong90.bpm.rules.api.MaskedInputQuery;
import io.github.dhluong90.bpm.rules.api.MaskingKey;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.service.EngineSettings;
import io.github.dhluong90.bpm.rules.internal.service.RuleEngines;
import io.github.dhluong90.bpm.rules.spi.CallerResolver;
import io.github.dhluong90.bpm.rules.spi.MaskingPolicy;
import io.github.dhluong90.bpm.rules.spi.TenantResolver;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
 * Masked inputs in {@code rle_decision} and their lookup on PostgreSQL (RLE-012, TR-SEC-05, TR-TEN-01): two tenants,
 * each with its own key, one fresh database per test. The clock is fixed at 15 January 2027, 09:00 in Ho Chi Minh
 * City.
 */
@Testcontainers
class MaskedInputsPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    static final Instant NOW = Instant.parse("2027-01-15T02:00:00Z");
    static final String NATIONAL_ID = "079090001234";
    /** Non-uniform keys: a key read in the wrong byte order would give a different fingerprint. */
    static final byte[] KEY_A = seeded(11L);
    static final byte[] KEY_B = seeded(22L);
    private static final AtomicInteger DATABASES = new AtomicInteger();

    static final String MINOR_REVIEW = """
            schemaVersion: 1
            ruleSet: minor-review
            hitPolicy: first
            inputs:
              - { name: nationalId, type: string, mask: true }
              - { name: age, type: integer }
            outputs:
              - { name: route, type: string }
            rules:
              - { id: R1, when: { nationalId: "is not null", age: "< 18" }, then: { route: "'MANUAL'" } }
              - { id: R2, when: { age: ">= 18" }, then: { route: "'AUTO'" } }
            """;

    final Caller caller = new Caller();
    DataSource db;
    RuleEngines engines;

    @BeforeEach
    void setUp() {
        db = freshDatabase();
        new RuleEngineSchemaMigrator(db).migrate();
        RuleEngineTransactions transactions = new RuleEngineTransactions(new DataSourceTransactionManager(db));
        new DecisionLogPartitions(db, transactions).ensureCurrentAndNextMonth(NOW);
        engines = engines(declared(Map.of("bank-a", List.of(MaskingKey.of(KEY_A)),
                "bank-b", List.of(MaskingKey.of(KEY_B)))));
    }

    RuleEngines engines(MaskingPolicy policy) {
        RuleEngineTransactions transactions = new RuleEngineTransactions(new DataSourceTransactionManager(db));
        return PostgresRuleEngines.create(db, transactions, caller, caller, Clock.fixed(NOW, ZoneOffset.UTC),
                EngineSettings.defaults().withRequireContentHash(false), policy);
    }

    /** A host policy with one key per tenant, each declared under its tenant, as a vault-backed policy would. */
    static MaskingPolicy declared(Map<String, List<MaskingKey>> keys) {
        return new MaskingPolicy() {
            @Override
            public Optional<MaskingKey> hmacKey(String tenant) {
                return Optional.ofNullable(keys.get(tenant)).map(List::getFirst);
            }

            @Override
            public Map<String, List<MaskingKey>> configuredKeys() {
                return keys;
            }
        };
    }

    @Test
    void TR_SEC_10_key_ids_never_move_between_the_host_and_a_tenant_and_a_refused_start_records_nothing() {
        // bank-a wrote under the host key k1; the host has moved to k2 and dropped k1.
        engines(MaskingPolicy.withKey(MaskingKey.of("k1", KEY_A))).maskingCheck().verify(List.of());
        MaskingPolicy reused = new MaskingPolicy() {
            @Override
            public Optional<MaskingKey> hmacKey(String tenant) {
                return Optional.empty();
            }

            @Override
            public Map<String, List<MaskingKey>> configuredKeys() {
                return Map.of(HOST_SCOPE, List.of(MaskingKey.of("k2", KEY_B)),
                        "bank-a", List.of(MaskingKey.of("k1", seeded(33L))));
            }
        };
        assertThatThrownBy(() -> engines(reused).maskingCheck().verify(List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("masking key id k1 of tenant bank-a")
                .hasMessageContaining("already recorded as a host key id");
        // Rolled back: neither the new host key k2 nor bank-a's k1 was recorded.
        assertThat(JdbcClient.create(db).sql("SELECT tenant_id || ' ' || key_id FROM rle_masking_key_check")
                .query(String.class).list()).containsExactly("* k1");
        // The reverse: bank-b's own key t1, then a host key with id t1.
        engines(declared(Map.of("bank-b", List.of(MaskingKey.of("t1", KEY_B))))).maskingCheck().verify(List.of());
        assertThatThrownBy(() -> engines(MaskingPolicy.withKey(MaskingKey.of("t1", seeded(34L)))).maskingCheck()
                .verify(List.of()))
                .hasMessageContaining("host masking key id t1")
                .hasMessageContaining("already recorded as a key id of tenant bank-b");
    }

    @Test
    void RLE_012_masked_inputs_stored_as_keyed_hmac_and_found_by_plain_value() throws Exception {
        publish("bank-a");
        String first = evaluate("bank-a", 16);
        String second = evaluate("bank-a", 30);

        List<String> rows = JdbcClient.create(db)
                .sql("SELECT masked_inputs ->> 'nationalId' FROM rle_decision WHERE tenant_id = 'bank-a'")
                .query(String.class).list();
        assertThat(rows).containsOnly("hmac:k1:" + hmacHex(KEY_A, NATIONAL_ID)).hasSize(2);
        assertThat(JdbcClient.create(db).sql("SELECT count(*) FROM rle_decision WHERE masked_inputs::text LIKE :plain")
                .param("plain", "%" + NATIONAL_ID + "%").query(Long.class).single()).isZero();

        caller.as("bank-a", "auditor");
        LoggedDecision read = engines.decisionLog().get(first);
        assertThat(read.inputsJson()).contains("hmac:k1:" + hmacHex(KEY_A, NATIONAL_ID)).doesNotContain(NATIONAL_ID);
        assertThat(read.outputsJson()).contains("MANUAL");
        assertThat(engines.decisionLog().findByMaskedInput(query()).items())
                .extracting(LoggedDecision::decisionId).containsExactly(second, first);
        // The search left one audit entry in the outbox, in the tenant, without the value or its fingerprint.
        String audit = JdbcClient.create(db).sql("SELECT payload::text FROM rle_decision_outbox WHERE tenant_id = "
                + "'bank-a' AND payload ->> 'eventType' = 'rules.decision-log.masked-input-searched.v1'")
                .query(String.class).single();
        assertThat(audit).contains("\"matches\": 2", "\"actor\": \"auditor\"")
                .doesNotContain(NATIONAL_ID, hmacHex(KEY_A, NATIONAL_ID));
    }

    @Test
    void RLE_012_tenant_isolation_of_masked_decision_reads() throws Exception {
        publish("bank-a");
        publish("bank-b");
        String inA = evaluate("bank-a", 16);
        String inB = evaluate("bank-b", 16);

        assertThat(JdbcClient.create(db)
                .sql("SELECT masked_inputs ->> 'nationalId' FROM rle_decision WHERE tenant_id = 'bank-b'")
                .query(String.class).single()).isEqualTo("hmac:k1:" + hmacHex(KEY_B, NATIONAL_ID));

        caller.as("bank-b", "auditor");
        assertThat(engines.decisionLog().findByMaskedInput(query()).items())
                .extracting(LoggedDecision::decisionId).containsExactly(inB);
        assertThatThrownBy(() -> engines.decisionLog().get(inA)).isInstanceOf(DecisionNotFoundException.class);

        caller.as("bank-c", "auditor");
        assertThatThrownBy(() -> engines.decisionLog().findByMaskedInput(query()))
                .hasMessageContaining("No masking key");
    }

    @Test
    void RLE_035_startup_check_reads_live_versions_of_every_tenant() {
        publish("bank-a");
        RuleEngineTransactions transactions = new RuleEngineTransactions(new DataSourceTransactionManager(db));
        RuleEngines withoutKeyForA = PostgresRuleEngines.create(db, transactions, caller, caller,
                Clock.fixed(NOW, ZoneOffset.UTC), EngineSettings.defaults().withRequireContentHash(false),
                tenant -> tenant.equals("bank-b") ? Optional.of(MaskingKey.of(KEY_B)) : Optional.empty());

        engines.maskingCheck().verify(List.of());
        assertThatThrownBy(() -> withoutKeyForA.maskingCheck().verify(List.of()))
                .hasMessageContaining("rule set minor-review of tenant bank-a masks input nationalId")
                .hasMessageContaining("horus.bpm.rules.masking.hmac-key");
    }

    @Test
    void RLE_052_the_masking_default_each_tenant_started_with_is_recorded_and_a_change_logged() {
        // Given bank-a has minor-review and the host starts with mask-default false
        publish("bank-a");
        engines.maskingCheck().recordMaskDefaults(Set.of("bank-b"), NOW);
        JdbcClient jdbc = JdbcClient.create(db);
        assertThat(jdbc.sql("SELECT tenant_id || '=' || value FROM rle_setting_seen"
                + " WHERE setting = 'decision-log.mask-default' ORDER BY tenant_id").query(String.class).list())
                .containsExactly("=false", "bank-a=false", "bank-b=false");

        // When the host restarts with mask-default true for bank-a only
        RuleEngines restarted = engines(maskByDefault("bank-a"));
        List<String> lines = restarted.maskingCheck().recordMaskDefaults(Set.of("bank-b"), NOW.plusSeconds(3_600));

        // Then one line names bank-a with the old and new value, and the new value and time are recorded
        assertThat(lines).containsExactly(
                "decision-log.mask-default for tenant bank-a changed from false to true since the last start-up");
        assertThat(jdbc.sql("SELECT value FROM rle_setting_seen WHERE tenant_id = 'bank-a'").query(String.class)
                .single()).isEqualTo("true");
        assertThat(jdbc.sql("SELECT recorded_at FROM rle_setting_seen WHERE tenant_id = 'bank-a'")
                .query(java.sql.Timestamp.class).single().toInstant()).isEqualTo(NOW.plusSeconds(3_600));
        // And a third start with the same values logs nothing
        assertThat(engines(maskByDefault("bank-a")).maskingCheck().recordMaskDefaults(Set.of("bank-b"),
                NOW.plusSeconds(7_200))).isEmpty();
    }

    @Test
    void RLE_050_a_strict_mode_refusal_stores_nothing_in_postgres() {
        // Given bank-a in strict mode
        RuleEngines strict = engines(new MaskingPolicy() {
            @Override
            public Optional<MaskingKey> hmacKey(String tenantId) {
                return Optional.of(MaskingKey.of(KEY_A));
            }

            @Override
            public boolean requireExplicitMask(String tenantId) {
                return true;
            }
        });
        caller.as("bank-a", "alice");

        // When alice imports minor-review, whose age declares no mask
        assertThatThrownBy(() -> strict.versionService().importRuleSet(
                ImportRequest.yaml(MINOR_REVIEW.getBytes(StandardCharsets.UTF_8))))
                .hasMessageContaining("age");

        // Then no rule set, version or event row exists
        JdbcClient jdbc = JdbcClient.create(db);
        assertThat(jdbc.sql("SELECT count(*) FROM rle_rule_set").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM rle_version").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM rle_decision_outbox").query(Long.class).single()).isZero();
    }

    @Test
    void RLE_053_the_acknowledgement_is_stored_on_the_approved_row_and_guarded_by_the_database() {
        // Given kyc-routing pending at bank-a, whose nationalId declares no mask
        String kyc = """
                schemaVersion: 1
                ruleSet: kyc-routing
                hitPolicy: first
                inputs:
                  - { name: nationalId, type: string }
                  - { name: age, type: integer, mask: false }
                outputs:
                  - { name: route, type: string }
                rules:
                  - { id: R1, when: {}, then: { route: "'AUTO'" } }
                """;
        caller.as("bank-a", "alice");
        engines.versionService().importRuleSet(ImportRequest.yaml(kyc.getBytes(StandardCharsets.UTF_8)));
        engines.versionService().requestPublish("kyc-routing", 1, WriteOptions.none());
        caller.as("bank-a", "bob");

        // When bob approves without and then with the acknowledgement
        assertThatThrownBy(() -> engines.versionService().approve("kyc-routing", 1, new WriteOptions(null, 1L, null)))
                .isInstanceOf(io.github.dhluong90.bpm.rules.api.WarningsNotAcknowledgedException.class);
        engines.versionService().approve("kyc-routing", 1,
                new WriteOptions(null, 1L, "ok").acknowledging(List.of(new io.github.dhluong90.bpm.rules.api.AcknowledgedWarning(
                        "UNMASKED_PERSONAL_DATA", "nationalId"))));

        // Then exactly one APPROVED row stores the acknowledgement and an empty masked-by-default list
        JdbcClient jdbc = JdbcClient.create(db);
        assertThat(jdbc.sql("SELECT acknowledged_warnings::text || ' ' || masked_by_default_inputs::text"
                + " FROM rle_version_history WHERE action = 'APPROVED'").query(String.class).list())
                .containsExactly("[{\"code\": \"UNMASKED_PERSONAL_DATA\", \"input\": \"nationalId\"}] []");
        assertThat(jdbc.sql("SELECT count(*) FROM rle_version_history WHERE action <> 'APPROVED'"
                + " AND (acknowledged_warnings IS NOT NULL OR masked_by_default_inputs IS NOT NULL)")
                .query(Long.class).single()).isZero();
        // And the history read returns it
        assertThat(engines.versionService().history("kyc-routing", 1).stream()
                .filter(e -> e.action() == io.github.dhluong90.bpm.rules.api.LifecycleAction.APPROVED)
                .findFirst().orElseThrow().acknowledgedWarnings())
                .containsExactly(new io.github.dhluong90.bpm.rules.api.AcknowledgedWarning("UNMASKED_PERSONAL_DATA",
                        "nationalId"));
        // And the published event carries it, and an empty maskedByDefaultInputs
        assertThat(jdbc.sql("SELECT (payload -> 'acknowledgedWarnings')::text || ' ' || (payload -> 'maskedByDefaultInputs')::text"
                + " FROM rle_decision_outbox WHERE payload ->> 'eventType' = 'rules.version.published.v1'")
                .query(String.class).single())
                .isEqualTo("[{\"code\": \"UNMASKED_PERSONAL_DATA\", \"input\": \"nationalId\"}] []");

        // And the database refuses an acknowledgement on another action, and a value that is not an array
        String versionId = jdbc.sql("SELECT version_id FROM rle_version_history WHERE action = 'APPROVED'")
                .query(String.class).single();
        String insert = "INSERT INTO rle_version_history (tenant_id, id, version_id, action, from_status, to_status,"
                + " actor, lock_version, occurred_at, acknowledged_warnings) VALUES ('bank-a', :id, :v, :action,"
                + " 'DRAFT', 'PENDING_APPROVAL', 'mallory', 9, now(), CAST(:ack AS jsonb))";
        assertThatThrownBy(() -> jdbc.sql(insert).param("id", "01JX0000000000000000000001").param("v", versionId)
                .param("action", "REQUESTED").param("ack", "[]").update())
                .hasMessageContaining("rle_version_history_ack_on_approval");
        assertThatThrownBy(() -> jdbc.sql(insert.replace("'DRAFT', 'PENDING_APPROVAL'",
                        "'PENDING_APPROVAL', 'PUBLISHED'")).param("id", "01JX0000000000000000000002")
                .param("v", versionId).param("action", "APPROVED").param("ack", "{\"code\": \"X\"}").update())
                .hasMessageContaining("rle_version_history_ack_arrays");
    }

    @Test
    void RLE_053_turning_the_default_off_names_the_published_versions_whose_approval_relied_on_it() {
        // Given bank-a masks undeclared inputs by default, and kyc-routing (nationalId undeclared) is approved there
        String kyc = """
                schemaVersion: 1
                ruleSet: kyc-routing
                hitPolicy: first
                inputs:
                  - { name: nationalId, type: string }
                  - { name: age, type: integer, mask: false }
                outputs:
                  - { name: route, type: string }
                rules:
                  - { id: R1, when: {}, then: { route: "'AUTO'" } }
                """;
        RuleEngines masking = engines(maskByDefault("bank-a"));
        caller.as("bank-a", "alice");
        masking.versionService().importRuleSet(ImportRequest.yaml(kyc.getBytes(StandardCharsets.UTF_8)));
        masking.versionService().requestPublish("kyc-routing", 1, WriteOptions.none());
        caller.as("bank-a", "bob");
        masking.versionService().approve("kyc-routing", 1, new WriteOptions(null, 1L, null));
        assertThat(JdbcClient.create(db).sql("SELECT masked_by_default_inputs::text FROM rle_version_history"
                + " WHERE action = 'APPROVED'").query(String.class).single()).isEqualTo("[\"nationalId\"]");
        masking.maskingCheck().recordMaskDefaults(Set.of(), NOW);

        // When the host restarts with the default off for bank-a
        List<String> lines = engines(maskByDefault("nobody")).maskingCheck().recordMaskDefaults(Set.of(),
                NOW.plusSeconds(3_600));

        // Then the change line is followed by the WARN naming kyc-routing v1 and nationalId
        assertThat(lines).containsExactly(
                "decision-log.mask-default for tenant bank-a changed from true to false since the last start-up",
                "Tenant bank-a no longer masks undeclared inputs; these published versions now log in plain text"
                        + " inputs that only the default masked: kyc-routing v1 [nationalId]");
    }

    @Test
    void TR_SEC_10_a_key_first_used_after_start_up_is_checked_against_the_id_recorded_for_the_host() {
        // Given k2 recorded for the host with the check value of KEY_A, and a policy that declares nothing and returns
        // k2 with the bytes of KEY_B for bank-a, which had no rule set at start-up
        JdbcClient jdbc = JdbcClient.create(db);
        jdbc.sql("INSERT INTO rle_masking_key_check (tenant_id, key_id, check_value, first_seen_at)"
                + " VALUES ('*', 'k2', :check, now())").param("check", MaskingKey.of("k2", KEY_A).checkValue()).update();
        engines = engines(tenantId -> Optional.of(MaskingKey.of("k2", KEY_B)));
        publish("bank-a");

        // When the first decision with a masked nationalId is evaluated
        String refused = evaluate("bank-a", 16);

        // Then it is refused as MASKING_KEY_MISMATCH, no row holds the plain value, and nothing was recorded for bank-a
        assertThat(jdbc.sql("SELECT error_code FROM rle_decision WHERE id = :id").param("id", refused)
                .query(String.class).single()).isEqualTo("MASKING_KEY_MISMATCH");
        assertThat(jdbc.sql("SELECT count(*) FROM rle_decision WHERE masked_inputs::text LIKE :plain")
                .param("plain", "%" + NATIONAL_ID + "%").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM rle_masking_key_check WHERE tenant_id = 'bank-a'")
                .query(Long.class).single()).isZero();

        // and the same key id with the bytes it was recorded with is accepted, in a transaction of its own
        engines = engines(tenantId -> Optional.of(MaskingKey.of("k2", KEY_A)));
        assertThat(jdbc.sql("SELECT error_code FROM rle_decision WHERE id = :id").param("id", evaluate("bank-a", 16))
                .query(String.class).optional()).isEmpty();
    }

    static MaskingPolicy maskByDefault(String tenant) {
        return new MaskingPolicy() {
            @Override
            public Optional<MaskingKey> hmacKey(String tenantId) {
                return Optional.of(MaskingKey.of(tenantId.equals("bank-a") ? KEY_A : KEY_B));
            }

            @Override
            public boolean maskByDefault(String tenantId) {
                return tenantId.equals(tenant);
            }
        };
    }

    MaskedInputQuery query() {
        return new MaskedInputQuery("minor-review", "nationalId", NATIONAL_ID, NOW.minusSeconds(86_400),
                NOW.plusSeconds(86_400), 50);
    }

    void publish(String tenant) {
        caller.as(tenant, "alice");
        engines.versionService().importRuleSet(ImportRequest.yaml(MINOR_REVIEW.getBytes(StandardCharsets.UTF_8)));
        long lock = engines.versionService().requestPublish("minor-review", 1, WriteOptions.none()).lockVersion();
        caller.as(tenant, "bob");
        engines.versionService().approve("minor-review", 1, new WriteOptions(null, lock, null));
    }

    String evaluate(String tenant, int age) {
        caller.as(tenant, "teller-01");
        return engines.ruleEngine().evaluate("minor-review",
                DecisionInput.of(Map.of("nationalId", NATIONAL_ID, "age", age))).decisionId();
    }

    static String hmacHex(byte[] key, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    static byte[] seeded(long seed) {
        byte[] bytes = new byte[32];
        new java.util.Random(seed).nextBytes(bytes);
        return bytes;
    }

    static byte[] filled(char c) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, (byte) c);
        return bytes;
    }

    private static DataSource freshDatabase() {
        String name = "rle_masking_it_" + DATABASES.incrementAndGet();
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

    static final class Caller implements TenantResolver, CallerResolver {
        private volatile String tenant = "bank-a";
        private volatile String user = "alice";

        void as(String tenant, String user) {
            this.tenant = tenant;
            this.user = user;
        }

        @Override
        public String currentTenantId() {
            return tenant;
        }

        @Override
        public String currentCallerId() {
            return user;
        }

        @Override
        public Set<String> currentPermissions() {
            return Set.of("rules:author", "rules:approve", "rules:evaluate", "rules:read", "rules:log:read",
                    "rules:masked:search");
        }
    }
}
