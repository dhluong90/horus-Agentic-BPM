package io.github.dhluong90.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.expr.api.Money;
import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.DecisionRequest;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.RuleSetNotFoundException;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.SimulationReport;
import io.github.dhluong90.bpm.rules.api.SimulationReportNotFoundException;
import io.github.dhluong90.bpm.rules.api.SimulationReportRequest;
import io.github.dhluong90.bpm.rules.api.SimulationReportRequiredException;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import io.github.dhluong90.bpm.rules.internal.service.EngineSettings;
import io.github.dhluong90.bpm.rules.internal.service.RuleEngines;
import java.time.Duration;
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
 * Simulation reports on PostgreSQL (PD-115, RLE-199 to RLE-201, migrations V37 and V38): a replay over the November
 * decisions of the partitioned log stored once and bound to the draft's hash, attached to the publication request,
 * refused to be changed by the database, and invisible to another tenant.
 */
@Testcontainers
class SimulationReportPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    static final Instant NOV_01 = Instant.parse("2026-11-01T00:00:00Z");
    static final Instant DEC_01 = Instant.parse("2026-12-01T00:00:00Z");
    static final Set<String> AUTHOR = Set.of("rules:read", "rules:author", "rules:simulate");
    static final Set<String> APPROVER = Set.of("rules:read", "rules:approve");
    static final Set<String> TELLER = Set.of("rules:evaluate");

    final EffectiveDatesAndReplayPostgresIT.Caller caller = new EffectiveDatesAndReplayPostgresIT.Caller();
    final EditorApiPostgresIT.Movable clock = new EditorApiPostgresIT.Movable(Instant.parse("2026-10-20T00:00:00Z"));
    DataSource fresh;
    JdbcClient jdbc;
    RuleEngines engines;

    @BeforeEach
    void setUp() {
        String name = "reports_" + System.nanoTime();
        JdbcClient.create(dataSource(POSTGRES.getDatabaseName())).sql("CREATE DATABASE " + name).update();
        fresh = dataSource(name);
        new RuleEngineSchemaMigrator(fresh).migrate();
        jdbc = JdbcClient.create(fresh);
        engines = engines(EngineSettings.defaults());
        new DecisionLogPartitions(fresh, new RuleEngineTransactions(new DataSourceTransactionManager(fresh)))
                .ensureCurrentAndNextMonth(Instant.parse("2026-11-15T00:00:00Z"));
    }

    RuleEngines engines(EngineSettings settings) {
        RuleEngineTransactions transactions = new RuleEngineTransactions(new DataSourceTransactionManager(fresh));
        RuleEngines created = PostgresRuleEngines.create(fresh, transactions, caller, caller, clock, settings);
        created.cursorKeys().load();
        return created;
    }

    static byte[] cardFee(String fee) {
        return HistorySimulationPostgresIT.cardFee(fee);
    }

    void givenNovemberHistoryAndDraft(String tenant) {
        caller.as(tenant, "lan", AUTHOR);
        engines.versionService().importRuleSet(ImportRequest.yaml(cardFee("50000 VND")));
        RuleSetVersion requested = engines.versionService().requestPublish("card-fee", 1, WriteOptions.none());
        caller.as(tenant, "minh", APPROVER);
        engines.versionService().approve("card-fee", 1,
                new WriteOptions(null, requested.lockVersion(), null).expectingContent(requested.canonicalHash()));
        caller.as(tenant, "teller", TELLER);
        for (int i = 0; i < 100; i++) {
            clock.set(NOV_01.plus(Duration.ofHours(7 * i + 1)));
            String tier = (i % 6 == 0 || i == 99) ? "STANDARD" : "GOLD";
            engines.ruleEngine().evaluate(DecisionRequest.of("card-fee", DecisionInput.of(Map.of("tier", tier,
                    "balance", Money.ofMinor("VND", 1_250_000)))));
        }
        clock.set(Instant.parse("2026-12-15T02:00:00Z"));
        caller.as(tenant, "lan", AUTHOR);
        engines.versionService().importVersion("card-fee", ImportRequest.yaml(cardFee("30000 VND")));
    }

    static DataSource dataSource(String database) {
        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database));
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }

    @Test
    void RLE_199_a_replay_report_is_stored_once_bound_to_the_hash_and_read_back() {
        // Given November history of card-fee v1 and draft v2
        givenNovemberHistoryAndDraft("bank-a");

        // When Lan runs a report over November, with a key, twice
        SimulationReportRequest request = SimulationReportRequest.replay(NOV_01, DEC_01, 100).withIdempotencyKey("r-1");
        SimulationReport created = engines.simulationReports().create("card-fee", 2, request);
        SimulationReport again = engines.simulationReports().create("card-fee", 2, request);

        // Then one row, 18 of 100 changed, and the read returns the same report
        assertThat(again.id()).isEqualTo(created.id());
        assertThat(jdbc.sql("SELECT count(*) FROM rle_simulation_report WHERE tenant_id = 'bank-a'")
                .query(Long.class).single()).isEqualTo(1L);
        assertThat(created.counts()).isEqualTo(new SimulationReport.Counts(100, 18, 82, 0, 0));
        SimulationReport read = engines.simulationReports().get("card-fee", 2, created.id());
        assertThat(read.rows()).hasSize(18);
        assertThat(read.rows().getFirst().after().readings().get("en").shortForm()).contains("30,000 VND");
        assertThat(read.status()).isEqualTo(SimulationReport.Status.CURRENT);
        assertThat(read.canonicalHash()).isEqualTo(
                engines.versionService().find("card-fee", 2, false).orElseThrow().canonicalHash());
        assertThat(engines.simulationReports().list("card-fee", 2)).singleElement()
                .satisfies(listed -> assertThat(listed.rows()).isEmpty());
    }

    @Test
    void RLE_200_the_database_refuses_to_change_or_delete_a_report() {
        // Given a stored report
        givenNovemberHistoryAndDraft("bank-a");
        SimulationReport report = engines.simulationReports().create("card-fee", 2,
                SimulationReportRequest.replay(NOV_01, DEC_01, 100));

        // When SQL tries to rewrite or delete it
        // Then both are refused with SQLSTATE 55000, and the counts are as they were
        assertThatThrownBy(() -> jdbc.sql("UPDATE rle_simulation_report SET changed_count = 0, unchanged_count = 100,"
                + " report_rows = '[]'::jsonb WHERE id = :id").param("id", report.id()).update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM rle_simulation_report WHERE id = :id")
                .param("id", report.id()).update()).hasMessageContaining("append-only");
        // TRUNCATE is refused too: by the foreign key of the attachment table, and by the trigger where it has none
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE rle_simulation_report").update())
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(engines.simulationReports().get("card-fee", 2, report.id()).counts().changed()).isEqualTo(18);
    }

    @Test
    void RLE_200_a_report_stores_no_decision_input() {
        // Given a stored report over November
        givenNovemberHistoryAndDraft("bank-a");
        SimulationReport report = engines.simulationReports().create("card-fee", 2,
                SimulationReportRequest.replay(NOV_01, DEC_01, 100));

        // When the stored row is read as text
        String stored = jdbc.sql("SELECT report_rows::text FROM rle_simulation_report WHERE id = :id")
                .param("id", report.id()).query(String.class).single();

        // Then it holds readings and ids, and neither the tier nor the balance of any decision
        assertThat(stored).contains("30,000 VND").doesNotContain("STANDARD").doesNotContain("1250000")
                .doesNotContain("balance");
    }

    @Test
    void RLE_200_another_tenant_never_finds_the_report() {
        // Given a report of bank-a
        givenNovemberHistoryAndDraft("bank-a");
        SimulationReport report = engines.simulationReports().create("card-fee", 2,
                SimulationReportRequest.replay(NOV_01, DEC_01, 100));

        // When bank-b, who has its own card-fee v1, asks for it
        caller.as("bank-b", "lan", AUTHOR);
        engines.versionService().importRuleSet(ImportRequest.yaml(cardFee("10000 VND")));

        // Then not found, and bank-a's report names nothing of bank-b
        assertThatThrownBy(() -> engines.simulationReports().get("card-fee", 1, report.id()))
                .isInstanceOf(SimulationReportNotFoundException.class);
        assertThatThrownBy(() -> engines.simulationReports().get("card-fee", 2, report.id()))
                .isInstanceOf(io.github.dhluong90.bpm.rules.api.VersionNotFoundException.class);
        assertThat(engines.simulationReports().list("card-fee", 1)).isEmpty();
        caller.as("bank-c", "lan", AUTHOR);
        assertThatThrownBy(() -> engines.simulationReports().list("card-fee", 2))
                .isInstanceOf(RuleSetNotFoundException.class);
    }

    @Test
    void RLE_201_the_publication_request_carries_the_report_and_the_attachment_is_appended() {
        // Given a report on draft v2
        givenNovemberHistoryAndDraft("bank-a");
        SimulationReport report = engines.simulationReports().create("card-fee", 2,
                SimulationReportRequest.replay(NOV_01, DEC_01, 100));

        // When Lan requests publication with it
        engines.versionService().requestPublish("card-fee", 2, WriteOptions.none().withSimulationReport(report.id()));

        // Then the version is pending, the report reads as attached, one attachment row exists, and the event names it
        assertThat(engines.simulationReports().get("card-fee", 2, report.id()).attached()).isTrue();
        assertThat(jdbc.sql("SELECT report_id FROM rle_simulation_report_attachment WHERE tenant_id = 'bank-a'")
                .query(String.class).list()).containsExactly(report.id());
        assertThat(jdbc.sql("""
                SELECT count(*) FROM rle_decision_outbox
                WHERE tenant_id = 'bank-a' AND payload ->> 'eventType' = 'rules.version.publish-requested.v1'
                  AND payload ->> 'simulationReportId' = :id
                """).param("id", report.id()).query(Long.class).single()).isEqualTo(1L);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM rle_simulation_report_attachment").update())
                .hasMessageContaining("append-only");
    }

    @Test
    void RLE_201_a_listed_rule_set_cannot_be_requested_without_a_report() {
        // Given the host lists card-fee, with v1 already published and v2 a draft
        givenNovemberHistoryAndDraft("bank-a");
        engines = engines(EngineSettings.defaults().withRequireSimulationReport(List.of("card-fee")));
        caller.as("bank-a", "lan", AUTHOR);

        // When Lan requests publication of v2 without a report
        // Then 409, and the draft is still a draft with no attachment
        assertThatThrownBy(() -> engines.versionService().requestPublish("card-fee", 2, WriteOptions.none()))
                .isInstanceOf(SimulationReportRequiredException.class);
        assertThat(engines.versionService().find("card-fee", 2, false).orElseThrow().status().name())
                .isEqualTo("DRAFT");
        assertThat(jdbc.sql("SELECT count(*) FROM rle_simulation_report_attachment").query(Long.class).single())
                .isZero();
    }
}
