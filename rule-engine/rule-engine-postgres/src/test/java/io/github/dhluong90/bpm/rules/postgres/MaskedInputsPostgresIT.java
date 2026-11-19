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
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

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
        MaskingPolicy perTenant = tenant -> Optional.ofNullable(Map.of("bank-a", KEY_A, "bank-b", KEY_B).get(tenant))
                .map(MaskingKey::of);
        engines = PostgresRuleEngines.create(db, transactions, caller, caller, Clock.fixed(NOW, ZoneOffset.UTC),
                EngineSettings.defaults(), perTenant);
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
                Clock.fixed(NOW, ZoneOffset.UTC), EngineSettings.defaults(),
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

    RuleEngines engines(MaskingPolicy policy) {
        RuleEngineTransactions transactions = new RuleEngineTransactions(new DataSourceTransactionManager(db));
        return PostgresRuleEngines.create(db, transactions, caller, caller, Clock.fixed(NOW, ZoneOffset.UTC),
                EngineSettings.defaults(), policy);
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
        engines.versionService().requestPublish("minor-review", 1, WriteOptions.none());
        caller.as(tenant, "bob");
        engines.versionService().approve("minor-review", 1, new WriteOptions(null, 1L, null));
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
