package io.github.dhluong90.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.service.EngineSettings;
import io.github.dhluong90.bpm.rules.internal.service.RuleEngines;
import io.github.dhluong90.bpm.rules.spi.CallerResolver;
import io.github.dhluong90.bpm.rules.spi.TenantResolver;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A regular expression stopped by its bounds fails one decision on PostgreSQL (RLE-007, TR-SEC-02): the row is
 * written in the host transaction with {@code outcome = EVALUATION_ERROR} and {@code error_code = REGEX_TIMEOUT}, in
 * the caller's tenant only, and the connection is released for the next decision. The clock is fixed at 15 January
 * 2027.
 */
@Testcontainers
class RegexBoundsPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static final Instant NOW = Instant.parse("2027-01-15T02:00:00Z");
    private static final AtomicInteger DATABASES = new AtomicInteger();

    static final String REFERENCE_CHECK = """
            schemaVersion: 1
            ruleSet: reference-check
            hitPolicy: first
            inputs:
              - { name: reference, type: string }
            outputs:
              - { name: route, type: string }
            rules:
              - { id: R1, when: { reference: "matches('^(a+?)+?$')" }, then: { route: "'REVIEW'" } }
              - { id: R2, when: { reference: "matches('^FT[0-9]{8}$')" }, then: { route: "'AUTO'" } }
              - { id: R3, when: {}, then: { route: "'MANUAL'" } }
            """;

    final Caller caller = new Caller();
    DataSource db;
    TransactionTemplate host;
    RuleEngines engines;

    @BeforeEach
    void setUp() {
        db = freshDatabase();
        new RuleEngineSchemaMigrator(db).migrate();
        DataSourceTransactionManager transactionManager = new DataSourceTransactionManager(db);
        RuleEngineTransactions transactions = new RuleEngineTransactions(transactionManager);
        new DecisionLogPartitions(db, transactions).ensureCurrentAndNextMonth(NOW);
        host = new TransactionTemplate(transactionManager);
        engines = PostgresRuleEngines.create(db, transactions, caller, caller, Clock.fixed(NOW, ZoneOffset.UTC),
                EngineSettings.defaults());
    }

    @Test
    void RLE_007_a_stopped_match_is_a_logged_evaluation_error_in_the_callers_tenant() {
        publish("bank-a");
        publish("bank-b");
        caller.as("bank-a", "teller-01");

        DecisionResult stopped = host.execute(status -> engines.ruleEngine().evaluate("reference-check",
                DecisionInput.of(Map.of("reference", "a".repeat(28) + "!"))));
        DecisionResult next = host.execute(status -> engines.ruleEngine().evaluate("reference-check",
                DecisionInput.of(Map.of("reference", "FT20270115"))));

        assertThat(stopped.outcome()).isInstanceOf(DecisionResult.Failed.class);
        assertThat(next.outputs()).containsEntry("route", "AUTO");
        List<String> rows = JdbcClient.create(db).sql("""
                SELECT tenant_id || ' ' || outcome || ' ' || coalesce(error_code, '-') || ' ' || matched_rule_ids::text
                FROM rle_decision ORDER BY id""").query(String.class).list();
        assertThat(rows).containsExactly("bank-a EVALUATION_ERROR REGEX_TIMEOUT []", "bank-a OK - [\"R2\"]");
        assertThat(JdbcClient.create(db).sql("SELECT count(*) FROM rle_decision WHERE tenant_id = 'bank-b'")
                .query(Long.class).single()).isZero();
    }

    void publish(String tenant) {
        caller.as(tenant, "alice");
        engines.versionService().importRuleSet(ImportRequest.yaml(REFERENCE_CHECK.getBytes(StandardCharsets.UTF_8)));
        engines.versionService().requestPublish("reference-check", 1, WriteOptions.none());
        caller.as(tenant, "bob");
        engines.versionService().approve("reference-check", 1, WriteOptions.none());
    }

    private static DataSource freshDatabase() {
        String name = "rle_regex_it_" + DATABASES.incrementAndGet();
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
            return Set.of("rules:author", "rules:approve", "rules:evaluate", "rules:read");
        }
    }
}
