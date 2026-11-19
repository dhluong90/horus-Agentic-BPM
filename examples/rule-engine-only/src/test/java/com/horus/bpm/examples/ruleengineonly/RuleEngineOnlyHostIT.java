package com.horus.bpm.examples.ruleengineonly;

import static org.assertj.core.api.Assertions.assertThat;

import com.horus.bpm.rules.postgres.RuleEngineSchemaMigrator;
import com.horus.bpm.rules.postgres.RuleEngineTransactions;
import com.zaxxer.hikari.HikariDataSource;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The example host started once on a clean PostgreSQL database with the starter and one configuration
 * block. The host's clock is fixed at 26 January 2027, so the decision log partitions it creates at
 * start-up are known in advance.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.endpoint.health.show-components=always",
                "management.endpoint.health.show-details=always"})
@Testcontainers
class RuleEngineOnlyHostIT {

    /** Tests of what exists on one Spring Boot line only; the other leg skips them (TR-EMB-02). */
    static final String SPRING_BOOT_3_5_ONLY = "spring-boot-3.5-only";

    private static final Instant HOST_NOW = Instant.parse("2027-01-26T00:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedHostClock {
        @Bean
        Clock clock() {
            return Clock.fixed(HOST_NOW, ZoneOffset.UTC);
        }
    }

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(System.getProperty("bpm.test.postgres.image", "postgres:16-alpine"));

    // Plain properties, not @ServiceConnection: the same test runs on both Spring Boot lines (TR-EMB-02).
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private ConfigurableApplicationContext context;

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void TR_EMB_01_host_boots_healthy_on_clean_database_and_rle_tables_are_usable() throws Exception {
        HttpResponse<String> response = get("/actuator/health");
        JsonNode health = JsonMapper.builder().build().readTree(response.body());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(health.path("status").asString()).isEqualTo("UP");
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
    void TR_EMB_01_rule_engine_health_component_is_up_with_the_same_details_on_both_lines() throws Exception {
        // One health check, one adapter per Spring Boot line (#116): same component name and details.
        JsonNode engine = JsonMapper.builder().build().readTree(get("/actuator/health").body())
                .path("components").path("ruleEngine");

        assertThat(engine.path("status").asString()).isEqualTo("UP");
        assertThat(engine.path("details").path("historyTable").asString()).isEqualTo("rle_schema_history");
        assertThat(engine.path("details").path("schemaVersion").asString()).isEqualTo("5");
        assertThat(engine.path("details").path("pendingMigrations").asInt()).isZero();
        assertThat(engine.path("details").path("nextMonthPartition").asString()).isEqualTo("rle_decision_2027_02");
        assertThat(engine.path("details").path("nextMonthPartitionPresent").asBoolean()).isTrue();
    }

    @Test
    void TR_EMB_07_jackson_3_parses_a_rule_set_definition_with_exact_decimals() throws Exception {
        // The engine's own mapper (ADR-0021): Jackson 3, every number as BigDecimal, never through double.
        JsonMapper engineMapper = JsonMapper.builder()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .build();
        JsonNode ruleSet;
        try (InputStream definition = getClass().getResourceAsStream("/rule-sets/card-fee.rule-set.json")) {
            ruleSet = engineMapper.readTree(definition);
        }

        assertThat(tools.jackson.databind.cfg.PackageVersion.VERSION.getMajorVersion()).isEqualTo(3);
        assertThat(ruleSet.path("ruleSet").asString()).isEqualTo("card-fee");
        assertThat(ruleSet.path("hitPolicy").asString()).isEqualTo("first");
        assertThat(ruleSet.path("rules")).hasSize(3);
        assertThat(ruleSet.path("rules").path(1).path("then").path("discountRate").decimalValue())
                .isEqualTo(new BigDecimal("0.015"));
        assertThat(ruleSet.path("rules").path(2).path("then").path("discountRate").decimalValue())
                .isEqualTo(new BigDecimal("0.1000000000000000055511151231257827"));
    }

    @Test
    @Tag(SPRING_BOOT_3_5_ONLY)
    void TR_EMB_07_spring_boot_3_5_host_keeps_its_own_jackson_2_beside_the_engine_jackson_3() throws Exception {
        // Read by name: the Spring Boot 4 build that compiles this test has no Jackson 2 databind.
        String databind = jacksonVersion("com.fasterxml.jackson.databind.cfg.PackageVersion");
        String core = jacksonVersion("com.fasterxml.jackson.core.json.PackageVersion");
        Object hostMapper = context.getBean(Class.forName("com.fasterxml.jackson.databind.ObjectMapper"));

        // Both majors on one classpath; the host's set is consistent and its mapper is still Jackson 2.
        assertThat(databind).startsWith("2.");
        assertThat(core).isEqualTo(databind);
        assertThat(jacksonVersion("tools.jackson.databind.cfg.PackageVersion")).startsWith("3.");
        assertThat(hostMapper.getClass().getName()).startsWith("com.fasterxml.jackson.databind.");

        // The host's own endpoint still answers.
        assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
    }

    private static String jacksonVersion(String packageVersionClass) throws ReflectiveOperationException {
        return String.valueOf(Class.forName(packageVersionClass).getField("VERSION").get(null));
    }

    @Test
    void TR_OPS_04_host_start_creates_partitions_for_the_host_clock_month_and_the_next() {
        List<String> partitions = jdbc.sql("""
                SELECT child.relname FROM pg_inherits i
                JOIN pg_class child ON child.oid = i.inhrelid
                WHERE i.inhparent = 'rle_decision'::regclass
                """).query(String.class).list();

        assertThat(partitions).containsExactlyInAnyOrder(
                "rle_decision_default", "rle_decision_2027_01", "rle_decision_2027_02");
    }

    @Test
    void TR_EMB_06_engine_migrations_recorded_only_in_rle_schema_history() {
        List<String> hostHistory = jdbc.sql(
                "SELECT version || ' ' || script FROM flyway_schema_history ORDER BY installed_rank")
                .query(String.class).list();
        List<String> engineHistory = jdbc.sql(
                "SELECT coalesce(version, '') || ' ' || script FROM rle_schema_history WHERE success ORDER BY installed_rank")
                .query(String.class).list();

        assertThat(hostHistory).containsExactly("1 V1__create_loan_application.sql");
        // The host's tables existed first, so the engine baselines at 0 and still applies V1 to V5.
        assertThat(engineHistory).containsExactly("0 Rule Engine baseline", "1 V1__rle_baseline.sql",
                "2 V2__rle_version_created_by.sql", "3 V3__rle_version_history.sql",
                "4 V4__rle_version_history_append_only.sql", "5 V5__rle_engine_key.sql");
    }

    @Test
    void TR_EMB_04_engine_uses_host_datasource_and_transaction_manager_and_no_pool_of_its_own() {
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

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + environment.getProperty("local.server.port")
                        + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
