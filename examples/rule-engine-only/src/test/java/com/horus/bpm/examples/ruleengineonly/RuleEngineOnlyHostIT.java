package com.horus.bpm.examples.ruleengineonly;

import static org.assertj.core.api.Assertions.assertThat;

import com.horus.bpm.rules.postgres.RuleEngineSchemaMigrator;
import com.horus.bpm.rules.postgres.RuleEngineTransactions;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The example host started once on a clean PostgreSQL database with the starter and one configuration block. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class RuleEngineOnlyHostIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private ConfigurableApplicationContext context;

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void tr_emb_01_host_boots_healthy_on_clean_database_and_rle_tables_are_usable() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + environment.getProperty("local.server.port")
                        + "/actuator/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode health = JsonMapper.builder().build().readTree(response.body());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(health.path("status").asString()).isEqualTo("UP");
        assertThat(health.path("components").path("ruleEngine").path("status").asString()).isEqualTo("UP");
        assertThat(health.path("components").path("db").path("status").asString()).isEqualTo("UP");

        // Usable: a rule set and a decision of a Gold-tier card fee are written and read back.
        context.getBean(RuleEngineTransactions.class).inNewTransaction(status -> {
            jdbc.sql("""
                    INSERT INTO rle_rule_set (tenant_id, id, name, description, created_at)
                    VALUES ('bank-a', '01JD00000000000000000000RS', 'card-fee', 'Card fee by customer tier', now())
                    """).update();
            jdbc.sql("""
                    INSERT INTO rle_decision (tenant_id, created_at, id, rule_set_name, version_num, hit_policy, outcome,
                        masked_inputs, outputs, matched_rule_ids, eval_clock, duration_micros, log_mode)
                    VALUES ('bank-a', now(), '01JD00000000000000000000DC', 'card-fee', 1, 'FIRST', 'OK',
                        '{"tier":"GOLD"}', '{"fee":{"amount":"0","currency":"VND"}}', '["R1"]', now(), 37, 'TRANSACTIONAL')
                    """).update();
            return null;
        });
        assertThat(jdbc.sql("SELECT name FROM rle_rule_set WHERE tenant_id = 'bank-a'").query(String.class).list())
                .containsExactly("card-fee");
        assertThat(jdbc.sql("SELECT matched_rule_ids::text FROM rle_decision WHERE tenant_id = 'bank-a' AND id = ?")
                .param("01JD00000000000000000000DC").query(String.class).single())
                .isEqualTo("[\"R1\"]");
    }

    @Test
    void tr_emb_06_engine_migrations_recorded_only_in_rle_schema_history() {
        List<String> hostHistory = jdbc.sql(
                "SELECT version || ' ' || script FROM flyway_schema_history ORDER BY installed_rank")
                .query(String.class).list();
        List<String> engineHistory = jdbc.sql(
                "SELECT coalesce(version, '') || ' ' || script FROM rle_schema_history WHERE success ORDER BY installed_rank")
                .query(String.class).list();

        assertThat(hostHistory).containsExactly("1 V1__create_loan_application.sql");
        // The host's tables existed first, so the engine baselines at 0 and still applies V1.
        assertThat(engineHistory).containsExactly("0 Rule Engine baseline", "1 V1__rle_baseline.sql");
    }

    @Test
    void tr_emb_04_engine_uses_host_datasource_and_transaction_manager_and_no_pool_of_its_own() {
        ConfigurableListableBeanFactory beans = context.getBeanFactory();

        // Exactly one DataSource, the host's pool.
        assertThat(context.getBeanNamesForType(DataSource.class)).containsExactly("dataSource");
        DataSource hostDataSource = context.getBean(DataSource.class);
        assertThat(hostDataSource).isInstanceOf(HikariDataSource.class);
        assertThat(Arrays.asList(beans.getDependenciesForBean("ruleEngineSchemaMigrator"))).contains("dataSource");

        // The engine's transactions run on the host's transaction manager over the same pool.
        PlatformTransactionManager hostTransactionManager = context.getBean(PlatformTransactionManager.class);
        assertThat(context.getBeanNamesForType(PlatformTransactionManager.class)).hasSize(1);
        assertThat(context.getBean(RuleEngineTransactions.class).transactionManager()).isSameAs(hostTransactionManager);
        assertThat(hostTransactionManager).isInstanceOfSatisfying(DataSourceTransactionManager.class,
                tm -> assertThat(tm.getDataSource()).isSameAs(hostDataSource));

        // The host's own Flyway auto-configuration still applies: the engine registered no Flyway bean.
        assertThat(context.getBeanNamesForType(Flyway.class)).containsExactly("flyway");
        assertThat(context.getBeanNamesForType(RuleEngineSchemaMigrator.class)).containsExactly("ruleEngineSchemaMigrator");
    }
}
