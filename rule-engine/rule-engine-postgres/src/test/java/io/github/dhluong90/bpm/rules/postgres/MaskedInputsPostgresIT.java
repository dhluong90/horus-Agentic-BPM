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
                EngineSettings.defaults(), policy);
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
                Clock.fixed(NOW, ZoneOffset.UTC), EngineSettings.defaults(),
                tenant -> tenant.equals("bank-b") ? Optional.of(MaskingKey.of(KEY_B)) : Optional.empty());

        engines.maskingCheck().verify(List.of());
        assertThatThrownBy(() -> withoutKeyForA.maskingCheck().verify(List.of()))
                .hasMessageContaining("rule set minor-review of tenant bank-a masks input nationalId")
                .hasMessageContaining("horus.bpm.rules.masking.hmac-key");
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
