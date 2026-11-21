package io.github.dhluong90.bpm.rules.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.rules.api.DecisionInput;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.RuleEngine;
import io.github.dhluong90.bpm.rules.api.RuleReadings;
import io.github.dhluong90.bpm.rules.api.RuleSetNotFoundException;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.VersionService;
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
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Readings on PostgreSQL (RLE-091 to RLE-093): the rows of a stored version, a diff and a simulation are read from the
 * stored canonical documents, another tenant sees nothing, and no {@code rle_} table changes.
 */
@Testcontainers
class ReadingsPostgresIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-12-01T00:00:00Z"), ZoneOffset.UTC);

    /** lan authors and simulates and reads, bob approves; bank-b has its own users. */
    static final class Callers implements TenantResolver, CallerResolver {
        private final ThreadLocal<String[]> current = ThreadLocal.withInitial(() -> new String[] {"bank-a", "lan"});

        void set(String tenant, String user) {
            current.set(new String[] {tenant, user});
        }

        @Override
        public String currentTenantId() {
            return current.get()[0];
        }

        @Override
        public String currentCallerId() {
            return current.get()[1];
        }

        @Override
        public Set<String> currentPermissions() {
            return "bob".equals(current.get()[1])
                    ? Set.of("rules:read", "rules:approve")
                    : Set.of("rules:read", "rules:author", "rules:simulate");
        }
    }

    final Callers who = new Callers();
    JdbcClient jdbc;
    VersionService versions;
    RuleEngine engine;

    @BeforeEach
    void setUp() {
        DataSource db = dataSource(POSTGRES.getDatabaseName());
        String name = "readings_" + System.nanoTime();
        JdbcClient.create(db).sql("CREATE DATABASE " + name).update();
        db = dataSource(name);
        new RuleEngineSchemaMigrator(db).migrate();
        jdbc = JdbcClient.create(db);
        RuleEngines engines = PostgresRuleEngines.create(db, new RuleEngineTransactions(new DataSourceTransactionManager(db)),
                who, who, CLOCK, EngineSettings.defaults().withDraftAuthoring(true));
        engines.cursorKeys().load();
        versions = engines.versionService();
        engine = engines.ruleEngine();
    }

    static DataSource dataSource(String database) {
        org.postgresql.ds.PGSimpleDataSource ds = new org.postgresql.ds.PGSimpleDataSource();
        ds.setUrl(POSTGRES.getJdbcUrl().replace("/" + POSTGRES.getDatabaseName(), "/" + database));
        ds.setUser(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }

    List<String> snapshot() {
        List<String> tables = jdbc.sql("SELECT table_name FROM information_schema.tables"
                + " WHERE table_schema = current_schema() AND table_name LIKE 'rle\\_%' ORDER BY table_name")
                .query(String.class).list();
        return tables.stream().map(t -> t + " " + jdbc.sql("SELECT count(*) || ' ' || md5(coalesce(string_agg(x::text, '|'"
                + " ORDER BY x::text), '')) FROM " + t + " x").query(String.class).single()).toList();
    }

    @Test
    void RLE_091_to_093_a_stored_version_a_diff_and_a_simulation_are_read_in_both_languages_and_write_nothing() {
        // Given card-fee v1 published and v2 as lan's draft with R2 at 0 VND
        who.set("bank-a", "lan");
        versions.importRuleSet(ImportRequest.yaml(RuleEnginePostgresIT.cardFee()));
        RuleSetVersion requested = versions.requestPublish("card-fee", 1, WriteOptions.none());
        who.set("bank-a", "bob");
        versions.approve("card-fee", 1, new WriteOptions(null, requested.lockVersion(), null)
                .expectingContent(requested.canonicalHash()));
        who.set("bank-a", "lan");
        String text = new String(RuleEnginePostgresIT.cardFee(), StandardCharsets.UTF_8);
        versions.importVersion("card-fee", ImportRequest.yaml(
                text.replace("\"10000 VND\"", "\"0 VND\"").getBytes(StandardCharsets.UTF_8)));
        List<String> before = snapshot();

        // When the version, the diff and a simulation are read
        RuleReadings.RuleSetReadings rows = versions.readings("card-fee", 2);
        RuleReadings.ReadingsDiff diff = versions.readingsDiff("card-fee", 2, 1);
        RuleReadings.SimulationReadings simulation = versions.readSimulation(engine.simulate("card-fee", 2,
                List.of(DecisionInput.of(Map.of("tier", "SILVER", "txCount", 5)))),
                List.of("Silver, 5 transactions"));

        // Then every row reads in both languages
        assertThat(rows.languages()).containsExactly("en", "vi");
        assertThat(rows.rows()).extracting(RuleReadings.Row::ruleId).containsExactly("R1", "R2", "R3");
        assertThat(rows.rows().get(1).readings().get("en").full())
                .isEqualTo("Rule R2 applies when tx count is at least 3. Then fee is 0 VND.");
        assertThat(rows.rows().get(1).readings().get("vi").shortForm()).isEqualTo("Tx count ít nhất 3: fee 0 VND");
        // and the diff lists R2 with the old and the new reading
        assertThat(diff.rows()).singleElement().satisfies(row -> {
            assertThat(row.ruleId()).isEqualTo("R2");
            assertThat(row.readingChanged()).isTrue();
            assertThat(row.previous().get("en").full()).endsWith("Then fee is 10,000 VND.");
            assertThat(row.current().get("en").full()).endsWith("Then fee is 0 VND.");
        });
        // and the simulation reads each side in the version that decided it
        assertThat(simulation.baselineVersion()).isEqualTo(1);
        assertThat(simulation.samples().get(0).simulated().ruleIds()).containsExactly("R2");
        assertThat(simulation.samples().get(0).simulated().readings().get("en").shortForm())
                .isEqualTo("R2: fee 0 VND");
        assertThat(simulation.samples().get(0).reference()).isEqualTo("S1");
        assertThat(simulation.samples().get(0).label()).isEqualTo("Silver, 5 transactions");
        assertThat(simulation.samples().get(0).baseline().readings().get("en").shortForm())
                .isEqualTo("R2: fee 10,000 VND");
        // and nothing was written
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void RLE_091_another_tenant_cannot_read_the_rule_set() {
        who.set("bank-a", "lan");
        versions.importRuleSet(ImportRequest.yaml(RuleEnginePostgresIT.cardFee()));

        who.set("bank-b", "eva");
        assertThatThrownBy(() -> versions.readings("card-fee", 1)).isInstanceOf(RuleSetNotFoundException.class);
        assertThatThrownBy(() -> versions.readingsDiff("card-fee", 1, 2)).isInstanceOf(RuleSetNotFoundException.class);
    }
}
