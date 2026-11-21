package io.github.dhluong90.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.expr.spi.ExpressionFunction;
import io.github.dhluong90.bpm.expr.spi.ValueType;
import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.ReplayNotPossibleException;
import io.github.dhluong90.bpm.rules.api.ValidationFailedException;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.service.CatalogueExplainer;
import io.github.dhluong90.bpm.rules.internal.service.DecisionQuerySettings;
import io.github.dhluong90.bpm.rules.internal.service.EngineSettings;
import io.github.dhluong90.bpm.rules.internal.service.HostFunctions;
import io.github.dhluong90.bpm.rules.internal.service.RuleEngines;
import io.github.dhluong90.bpm.rules.internal.store.FunctionVersionStore;
import io.github.dhluong90.bpm.rules.spi.MaskingPolicy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * Host functions on PostgreSQL (RLE-055 to RLE-058, PD-119): V25 {@code rle_decision.function_versions} and V26
 * {@code rle_version_function}, through the real SQL, on one fresh database per test. Card fee table of bank-a that
 * waives the fee of PRIORITY customers with the host's {@code segmentOf(balance, tenureMonths)}.
 */
@Testcontainers
class HostFunctionsPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    static final Set<String> AUTHOR = Set.of("rules:read", "rules:author");
    static final Set<String> APPROVER = Set.of("rules:read", "rules:approve");
    static final Set<String> TELLER = Set.of("rules:evaluate");
    static final Set<String> AUDITOR = Set.of("rules:log:read");
    static final String CARD_FEE = """
            schemaVersion: 1
            ruleSet: card-fee
            hitPolicy: first
            inputs:
              - { name: balance,      type: money }
              - { name: tenureMonths, type: integer }
            outputs:
              - { name: fee, type: money }
            rules:
              - { id: R1, when: { balance: "segmentOf(balance, tenureMonths) == 'PRIORITY'" }, then: { fee: "0 VND" } }
              - { id: R2, when: {}, then: { fee: "50000 VND" } }
            """;

    final RetireVersionPostgresIT.Caller caller = new RetireVersionPostgresIT.Caller();
    final EditorApiPostgresIT.Movable clock = new EditorApiPostgresIT.Movable(Instant.parse("2027-01-05T02:00:00Z"));
    DataSource db;
    JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        String name = "functions_" + System.nanoTime();
        JdbcClient.create(dataSource(POSTGRES.getDatabaseName()))
                .sql("CREATE DATABASE " + name).update();
        db = dataSource(name);
        new RuleEngineSchemaMigrator(db).migrate();
        jdbc = JdbcClient.create(db);
    }

    static DataSource dataSource(String database) {
        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database));
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }

    static ExpressionFunction segmentOf(String version) {
        return new ExpressionFunction("segmentOf", List.of(ValueType.MONEY, ValueType.NUMBER), ValueType.STRING, version, 1,
                arguments -> ((Money) arguments.get(0)).minorUnits() >= 500_000_000L
                        && ((BigDecimal) arguments.get(1)).intValue() >= 24 ? "PRIORITY" : "MASS");
    }

    RuleEngines engines(HostFunctions.VersionChange change, ExpressionFunction... functions) {
        RuleEngineTransactions transactions = new RuleEngineTransactions(new DataSourceTransactionManager(db));
        RuleEngines engines = PostgresRuleEngines.create(db, transactions, caller, caller, clock,
                EngineSettings.defaults(), MaskingPolicy.none(), DecisionQuerySettings.defaults(),
                CatalogueExplainer.bundled(), List.of(functions), change);
        engines.cursorKeys().load();
        return engines;
    }

    void publish(RuleEngines engines, String tenant) {
        caller.as(tenant, "lan", AUTHOR);
        engines.versionService().importRuleSet(ImportRequest.yaml(CARD_FEE.getBytes(StandardCharsets.UTF_8)));
        long reviewed = engines.versionService().requestPublish("card-fee", 1, WriteOptions.none()).lockVersion();
        caller.as(tenant, "bao", APPROVER);
        engines.versionService().approve("card-fee", 1, new WriteOptions(null, reviewed, null));
    }

    DecisionResult evaluate(RuleEngines engines, String tenant) {
        caller.as(tenant, "teller-01", TELLER);
        return engines.ruleEngine().evaluate("card-fee", DecisionInput.of(Map.of(
                "balance", Money.ofMinor("VND", 600_000_000L), "tenureMonths", 36)));
    }

    @Test
    void RLE_058_postgres_keeps_the_approved_function_versions_and_the_version_each_decision_used() {
        RuleEngines v1 = engines(HostFunctions.VersionChange.WARN, segmentOf("2027.1"));
        publish(v1, "bank-a");
        DecisionResult d1 = evaluate(v1, "bank-a");

        // V26: one row for the function the approved version calls, in bank-a only
        assertThat(jdbc.sql("SELECT tenant_id || ':' || function_name || '@' || function_version FROM rle_version_function")
                .query(String.class).list()).containsExactly("bank-a:segmentOf@2027.1");
        // V25: the decision row records the version it used
        assertThat(jdbc.sql("SELECT function_versions FROM rle_decision WHERE id = :id").param("id", d1.decisionId())
                .query(String.class).single()).isEqualTo("segmentOf@2027.1");

        // the host redeploys with 2027.2: start-up sees one change, and bank-b has none of its own
        RuleEngines v2 = engines(HostFunctions.VersionChange.WARN, segmentOf("2027.2"));
        assertThat(v2.functionCheck().changes()).singleElement().satisfies(change -> {
            assertThat(change.function()).isEqualTo("segmentOf");
            assertThat(change.approvedVersion()).isEqualTo("2027.1");
            assertThat(change.currentVersion()).isEqualTo("2027.2");
            assertThat(change.describedUses()).containsExactly("bank-a/card-fee v1");
        });
        FunctionVersionStore store = new JdbcFunctionVersionStore(db);
        assertThat(store.approvedWith("bank-a", "card-fee", 1)).containsExactly(Map.entry("segmentOf", "2027.1"));
        assertThat(store.approvedWith("bank-b", "card-fee", 1)).as("another tenant's baseline is never read").isEmpty();

        // warn: the decision is made and records 2027.2; the replay of D1 is refused naming both labels
        DecisionResult d2 = evaluate(v2, "bank-a");
        assertThat(d2.isError()).isFalse();
        assertThat(jdbc.sql("SELECT function_versions FROM rle_decision WHERE id = :id").param("id", d2.decisionId())
                .query(String.class).single()).isEqualTo("segmentOf@2027.2");
        caller.as("bank-a", "audrey", AUDITOR);
        assertThatThrownBy(() -> v2.ruleEngine().replay(d1.decisionId())).isInstanceOf(ReplayNotPossibleException.class)
                .hasMessageContaining("segmentOf").hasMessageContaining("2027.1").hasMessageContaining("2027.2");

        // refuse: the decision fails closed and the row says why
        RuleEngines refusing = engines(HostFunctions.VersionChange.REFUSE, segmentOf("2027.2"));
        DecisionResult refused = evaluate(refusing, "bank-a");
        assertThat(refused.isError()).isTrue();
        assertThat(jdbc.sql("SELECT error_code FROM rle_decision WHERE id = :id").param("id", refused.decisionId())
                .query(String.class).single()).isEqualTo("FUNCTION_CHANGED");
    }

    @Test
    void RLE_056_postgres_refuses_a_call_to_a_function_the_host_removed_and_approves_nothing() {
        RuleEngines registered = engines(HostFunctions.VersionChange.WARN, segmentOf("2027.1"));
        caller.as("bank-a", "lan", AUTHOR);
        registered.versionService().importRuleSet(ImportRequest.yaml(CARD_FEE.getBytes(StandardCharsets.UTF_8)));
        long reviewed = registered.versionService().requestPublish("card-fee", 1, WriteOptions.none()).lockVersion();
        RuleEngines removed = engines(HostFunctions.VersionChange.WARN);

        caller.as("bank-a", "bao", APPROVER);
        assertThatThrownBy(() -> removed.versionService().approve("card-fee", 1, new WriteOptions(null, reviewed, null)))
                .isInstanceOf(ValidationFailedException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM rle_version_function").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT status FROM rle_version").query(String.class).single()).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void RLE_058_postgres_replaces_nothing_it_does_not_own_when_a_version_calls_no_function() {
        RuleEngines engines = engines(HostFunctions.VersionChange.WARN, segmentOf("2027.1"));
        caller.as("bank-a", "lan", AUTHOR);
        engines.versionService().importRuleSet(ImportRequest.yaml(("""
                schemaVersion: 1
                ruleSet: flat-fee
                hitPolicy: first
                inputs:
                  - { name: tier, type: string }
                outputs:
                  - { name: fee, type: money }
                rules:
                  - { id: R1, when: {}, then: { fee: "10000 VND" } }
                """).getBytes(StandardCharsets.UTF_8)));
        long reviewed = engines.versionService().requestPublish("flat-fee", 1, WriteOptions.none()).lockVersion();
        caller.as("bank-a", "bao", APPROVER);
        engines.versionService().approve("flat-fee", 1, new WriteOptions(null, reviewed, null));

        caller.as("bank-a", "teller-01", TELLER);
        DecisionResult result = engines.ruleEngine().evaluate("flat-fee", DecisionInput.of(Map.of("tier", "GOLD")));

        assertThat(result.isError()).isFalse();
        assertThat(jdbc.sql("SELECT count(*) FROM rle_version_function").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT function_versions IS NULL FROM rle_decision WHERE id = :id")
                .param("id", result.decisionId()).query(Boolean.class).single()).as("no function, no version recorded")
                .isTrue();
    }
}
