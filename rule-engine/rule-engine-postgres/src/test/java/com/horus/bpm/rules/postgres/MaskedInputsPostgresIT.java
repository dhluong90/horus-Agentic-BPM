package com.horus.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.horus.bpm.rules.api.DecisionInput;
import com.horus.bpm.rules.api.DecisionNotFoundException;
import com.horus.bpm.rules.api.ImportRequest;
import com.horus.bpm.rules.api.LoggedDecision;
import com.horus.bpm.rules.api.MaskedInputQuery;
import com.horus.bpm.rules.api.MaskingKey;
import com.horus.bpm.rules.api.WriteOptions;
import com.horus.bpm.rules.internal.service.EngineSettings;
import com.horus.bpm.rules.internal.service.RuleEngines;
import com.horus.bpm.rules.spi.CallerResolver;
import com.horus.bpm.rules.spi.MaskingPolicy;
import com.horus.bpm.rules.spi.TenantResolver;
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
    static final byte[] KEY_A = filled('a');
    static final byte[] KEY_B = filled('b');
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
        assertThat(rows).containsOnly("hmac:" + hmacHex(KEY_A, NATIONAL_ID)).hasSize(2);
        assertThat(JdbcClient.create(db).sql("SELECT count(*) FROM rle_decision WHERE masked_inputs::text LIKE :plain")
                .param("plain", "%" + NATIONAL_ID + "%").query(Long.class).single()).isZero();

        caller.as("bank-a", "auditor");
        LoggedDecision read = engines.decisionLog().get(first);
        assertThat(read.inputsJson()).contains("hmac:" + hmacHex(KEY_A, NATIONAL_ID)).doesNotContain(NATIONAL_ID);
        assertThat(read.outputsJson()).contains("MANUAL");
        assertThat(engines.decisionLog().findByMaskedInput(query()).items())
                .extracting(LoggedDecision::decisionId).containsExactly(second, first);
    }

    @Test
    void RLE_012_tenant_isolation_of_masked_decision_reads() throws Exception {
        publish("bank-a");
        publish("bank-b");
        String inA = evaluate("bank-a", 16);
        String inB = evaluate("bank-b", 16);

        assertThat(JdbcClient.create(db)
                .sql("SELECT masked_inputs ->> 'nationalId' FROM rle_decision WHERE tenant_id = 'bank-b'")
                .query(String.class).single()).isEqualTo("hmac:" + hmacHex(KEY_B, NATIONAL_ID));

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
        engines.versionService().requestPublish("minor-review", 1, WriteOptions.none());
        caller.as(tenant, "bob");
        engines.versionService().approve("minor-review", 1, WriteOptions.none());
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
            return Set.of("rules:author", "rules:approve", "rules:evaluate", "rules:read", "rules:log:read");
        }
    }
}
