package io.github.dhluong90.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.expr.internal.EvaluationLimits;
import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionResult;
import io.github.dhluong90.bpm.rules.api.DecisionNotFoundException;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.LifecycleAction;
import io.github.dhluong90.bpm.rules.api.LoggedDecision;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.VersionHistoryEntry;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.service.EngineSettings;
import io.github.dhluong90.bpm.rules.internal.service.RuleEngines;
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
 * RLE-228 (#357) and RLE-229 (#360) on PostgreSQL: the file name an import came from is kept with the version, on its
 * history row and in the created event, outside every hash; and a decision that fails on the evaluation budget keeps
 * the limit it reached, which only its own tenant reads back.
 */
@Testcontainers
class ImportFileNameAndBudgetLimitPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    static final Set<String> AUTHOR = Set.of("rules:read", "rules:author");
    static final Set<String> APPROVER = Set.of("rules:read", "rules:approve");
    static final Set<String> TELLER = Set.of("rules:evaluate", "rules:read", "rules:log:read");

    final RetireVersionPostgresIT.Caller caller = new RetireVersionPostgresIT.Caller();
    final EditorApiPostgresIT.Movable clock = new EditorApiPostgresIT.Movable(Instant.parse("2027-02-01T09:00:00Z"));
    JdbcClient jdbc;
    DataSource db;

    @BeforeEach
    void setUp() {
        String name = "filename_" + System.nanoTime();
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

    RuleEngines engines(EngineSettings settings) {
        RuleEngines engines = PostgresRuleEngines.create(db,
                new RuleEngineTransactions(new DataSourceTransactionManager(db)), caller, caller, clock, settings);
        engines.cursorKeys().load();
        return engines;
    }

    static byte[] feeTable(String ruleSet, String fee) {
        return ("""
                schemaVersion: 1
                ruleSet: %s
                hitPolicy: first
                inputs:
                  - { name: tier, type: string }
                outputs:
                  - { name: fee, type: money }
                rules:
                  - { id: R1, when: {}, then: { fee: "%s" } }
                """).formatted(ruleSet, fee).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void RLE_228_an_import_keeps_its_file_name_on_the_version_its_history_row_and_the_event_and_no_hash_moves() {
        // Given alice imports card-fee from "Biểu phí thẻ 2027.yaml" and a second rule set without any name
        RuleEngines engine = engines(EngineSettings.defaults().withRequireContentHash(false));
        caller.as("bank-a", "alice", AUTHOR);
        RuleSetVersion named = engine.versionService().importRuleSet(new ImportRequest(
                io.github.dhluong90.bpm.rules.api.SourceFormat.YAML, feeTable("card-fee", "50000 VND"), null, null,
                "C:\\Users\\alice\\Downloads\\Biểu phí thẻ 2027.yaml"));
        RuleSetVersion plain = engine.versionService().importRuleSet(ImportRequest.yaml(feeTable("loan-fee", "50000 VND")));
        RuleSetVersion again = engine.versionService().importVersion("card-fee", new ImportRequest(
                io.github.dhluong90.bpm.rules.api.SourceFormat.YAML, feeTable("card-fee", "50000 VND"), null, null,
                "card-fee-copy.yaml"));

        // Then the version, a fresh read of it, its CREATED history row and the event carry the bare name
        assertThat(named.sourceFileName()).isEqualTo("Biểu phí thẻ 2027.yaml");
        assertThat(engine.versionService().find("card-fee", 1, false).orElseThrow().sourceFileName())
                .isEqualTo("Biểu phí thẻ 2027.yaml");
        assertThat(jdbc.sql("SELECT source_file_name FROM rle_version_history WHERE action = 'CREATED' "
                + "AND version_id = (SELECT id FROM rle_version WHERE version_num = 1 AND source_file_name IS NOT NULL)")
                .query(String.class).single()).isEqualTo("Biểu phí thẻ 2027.yaml");
        List<VersionHistoryEntry> history = engine.versionService().history("card-fee", 1);
        assertThat(history).filteredOn(e -> e.action() == LifecycleAction.CREATED).singleElement()
                .satisfies(e -> assertThat(e.sourceFileName()).isEqualTo("Biểu phí thẻ 2027.yaml"));
        assertThat(jdbc.sql("SELECT count(*) FROM rle_decision_outbox WHERE payload->>'eventType' = "
                + "'rules.version.created.v1' AND payload->>'sourceFileName' = 'Biểu phí thẻ 2027.yaml'")
                .query(Long.class).single()).isEqualTo(1L);

        // And an import with no name works as before, with no name anywhere
        assertThat(plain.sourceFileName()).isNull();
        assertThat(jdbc.sql("SELECT count(*) FROM rle_decision_outbox WHERE payload->>'sourceFileName' IS NOT NULL")
                .query(Long.class).single()).isEqualTo(2L);

        // And the same document under another name has the same hashes: the name is outside both
        assertThat(again.canonicalHash()).isEqualTo(named.canonicalHash());
        assertThat(again.sourceHash()).isEqualTo(named.sourceHash());
        assertThat(again.sourceFileName()).isEqualTo("card-fee-copy.yaml");
    }

    @Test
    void RLE_228_the_name_is_cleaned_to_a_bare_display_name_and_a_too_long_name_is_refused() {
        RuleEngines engine = engines(EngineSettings.defaults().withRequireContentHash(false));
        caller.as("bank-a", "alice", AUTHOR);

        RuleSetVersion cleaned = engine.versionService().importRuleSet(new ImportRequest(
                io.github.dhluong90.bpm.rules.api.SourceFormat.YAML, feeTable("card-fee", "50000 VND"), null, null,
                "../../etc/pass\u0000wd\u202E.yaml"));

        assertThat(cleaned.sourceFileName()).isEqualTo("passwd.yaml");
        assertThatThrownBy(() -> new ImportRequest(io.github.dhluong90.bpm.rules.api.SourceFormat.YAML,
                feeTable("card-fee", "50000 VND"), null, null, "x".repeat(256)))
                .isInstanceOf(io.github.dhluong90.bpm.rules.api.InputInvalidException.class)
                .hasMessageContaining("255");
        // the database refuses a name that is not clean, whoever writes it
        assertThatThrownBy(() -> jdbc.sql("UPDATE rle_version SET source_file_name = 'a/b.yaml'").update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void RLE_229_a_step_budget_failure_keeps_limit_steps_in_the_log_and_only_its_tenant_reads_it() {
        // Given a loyalty table whose twelve terms exceed a step budget of 10, approved by a second person
        EngineSettings tight = EngineSettings.defaults().withRequireContentHash(false)
                .withEvaluationLimits(new EvaluationLimits(10, 10_000));
        RuleEngines authoring = engines(EngineSettings.defaults().withRequireContentHash(false));
        caller.as("bank-a", "alice", AUTHOR);
        authoring.versionService().importRuleSet(ImportRequest.yaml(("""
                schemaVersion: 1
                ruleSet: loyalty-score
                hitPolicy: first
                inputs:
                  - { name: tier, type: string, mask: false }
                outputs:
                  - { name: points, type: integer }
                rules:
                  - { id: R1, when: {}, then: { points: "1 + 1 + 1 + 1 + 1 + 1 + 1 + 1 + 1 + 1 + 1 + 1" } }
                """).getBytes(StandardCharsets.UTF_8)));
        long lock = authoring.versionService().requestPublish("loyalty-score", 1, WriteOptions.none()).lockVersion();
        caller.as("bank-a", "bob", APPROVER);
        authoring.versionService().approve("loyalty-score", 1, new WriteOptions(null, lock, null));

        // When a host with the tight budget evaluates it, and the default host evaluates it too
        RuleEngines host = engines(tight);
        caller.as("bank-a", "svc", TELLER);
        DecisionResult failed = host.ruleEngine().evaluate("loyalty-score", DecisionInput.of(Map.of("tier", "GOLD")));
        DecisionResult passed = authoring.ruleEngine().evaluate("loyalty-score", DecisionInput.of(Map.of("tier", "GOLD")));

        // Then the failed row carries STEPS, in the column, in the decision read and in the search; the other is null
        assertThat(failed.outcome()).isInstanceOfSatisfying(DecisionResult.Failed.class,
                f -> assertThat(f.limit()).isEqualTo("STEPS"));
        assertThat(jdbc.sql("SELECT budget_limit FROM rle_decision WHERE id = :id").param("id", failed.decisionId())
                .query(String.class).single()).isEqualTo("STEPS");
        assertThat(jdbc.sql("SELECT budget_limit FROM rle_decision WHERE id = :id").param("id", passed.decisionId())
                .query(String.class).optional()).isEmpty();
        LoggedDecision read = host.decisionLog().get(failed.decisionId());
        assertThat(read.errorCode()).isEqualTo("EVALUATION_BUDGET_EXCEEDED");
        assertThat(read.limit()).isEqualTo("STEPS");
        assertThat(host.decisionLog().get(passed.decisionId()).limit()).isNull();

        // And another tenant cannot read the row
        caller.as("bank-b", "svc", TELLER);
        assertThatThrownBy(() -> host.decisionLog().get(failed.decisionId())).isInstanceOf(DecisionNotFoundException.class);
    }
}
