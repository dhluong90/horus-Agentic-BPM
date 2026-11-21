package io.github.dhluong90.bpm.examples.ruleengineonly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Failure paths of TR-EMB-06: the host and the engine restart on a database where both histories exist,
 * and the reverse install order, where the engine's tables exist before the host adds its own Flyway
 * (wiki Getting Started, "Install order").
 */
@Testcontainers
class HostRestartIT {

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(TestImages.postgres());

    private static final AtomicInteger DATABASES = new AtomicInteger();

    @Test
    void TR_EMB_06_restart_with_existing_engine_history_leaves_both_histories_unchanged() {
        String db = freshDatabase();
        List<String> hostBefore;
        List<String> engineBefore;
        try (ConfigurableApplicationContext first = start(db)) {
            JdbcClient jdbc = first.getBean(JdbcClient.class);
            hostBefore = history(jdbc, "flyway_schema_history");
            engineBefore = history(jdbc, "rle_schema_history");
        }

        try (ConfigurableApplicationContext second = start(db)) {
            JdbcClient jdbc = second.getBean(JdbcClient.class);
            assertThat(history(jdbc, "flyway_schema_history")).isEqualTo(hostBefore).hasSize(1);
            assertThat(history(jdbc, "rle_schema_history")).isEqualTo(engineBefore).hasSize(21);   // baseline 0, V1 to V6, V10 to V12, V14 to V21 and V24 to V26
        }
    }

    @Test
    void TR_EMB_06_new_host_migration_with_existing_engine_history_does_not_touch_engine_history() {
        String db = freshDatabase();
        List<String> engineBefore;
        try (ConfigurableApplicationContext first = start(db)) {
            engineBefore = history(first.getBean(JdbcClient.class), "rle_schema_history");
        }

        try (ConfigurableApplicationContext upgraded =
                start(db, "spring.flyway.locations=classpath:db/migration,classpath:db/host-v2")) {
            JdbcClient jdbc = upgraded.getBean(JdbcClient.class);
            assertThat(jdbc.sql("SELECT version FROM flyway_schema_history ORDER BY installed_rank")
                    .query(String.class).list())
                    .containsExactly("1", "2");
            assertThat(history(jdbc, "rle_schema_history")).isEqualTo(engineBefore);
        }
    }

    @Test
    void TR_EMB_06_engine_first_then_host_flyway_with_baseline_version_0_runs_the_host_v1() {
        String db = freshDatabase();
        List<String> engineBefore = installEngineWithoutHostFlyway(db);

        try (ConfigurableApplicationContext withHostFlyway = start(db,
                "spring.flyway.baseline-on-migrate=true", "spring.flyway.baseline-version=0")) {
            JdbcClient jdbc = withHostFlyway.getBean(JdbcClient.class);
            assertThat(jdbc.sql("SELECT version || ' ' || type FROM flyway_schema_history ORDER BY installed_rank")
                    .query(String.class).list())
                    .containsExactly("0 BASELINE", "1 SQL");
            assertThat(tableExists(jdbc, "loan_application")).isTrue();
            assertThat(history(jdbc, "rle_schema_history")).isEqualTo(engineBefore);
        }
    }

    @Test
    void TR_EMB_06_engine_first_then_host_flyway_with_the_default_baseline_silently_skips_the_host_v1() {
        // The hazard the wiki warns about: Spring's default baseline version 1 records the host's V1 as done.
        String db = freshDatabase();
        installEngineWithoutHostFlyway(db);

        try (ConfigurableApplicationContext withHostFlyway = start(db, "spring.flyway.baseline-on-migrate=true")) {
            JdbcClient jdbc = withHostFlyway.getBean(JdbcClient.class);
            assertThat(jdbc.sql("SELECT version || ' ' || type FROM flyway_schema_history ORDER BY installed_rank")
                    .query(String.class).list())
                    .containsExactly("1 BASELINE");
            assertThat(tableExists(jdbc, "loan_application")).isFalse();
        }
    }

    @Test
    void TR_EMB_06_engine_first_then_host_flyway_without_baseline_refuses_to_start() {
        String db = freshDatabase();
        installEngineWithoutHostFlyway(db);

        // Flyway's wording differs between versions; the exception type and one stable fragment do not.
        assertThatThrownBy(() -> start(db).close())
                .hasRootCauseInstanceOf(FlywayException.class)
                .hasStackTraceContaining("schema history table");
    }

    /** The engine alone on an empty database: its tables and history exist, the host has no Flyway yet. */
    private static List<String> installEngineWithoutHostFlyway(String database) {
        try (ConfigurableApplicationContext engineOnly = start(database, "spring.flyway.enabled=false")) {
            JdbcClient jdbc = engineOnly.getBean(JdbcClient.class);
            assertThat(tableExists(jdbc, "flyway_schema_history")).isFalse();
            assertThat(tableExists(jdbc, "rle_rule_set")).isTrue();
            return history(jdbc, "rle_schema_history");
        }
    }

    private static boolean tableExists(JdbcClient jdbc, String table) {
        return jdbc.sql("SELECT to_regclass(?) IS NOT NULL").param(table).query(Boolean.class).single();
    }

    private static ConfigurableApplicationContext start(String database, String... extraProperties) {
        // Command-line arguments outrank application.yml, as a deployment's environment would.
        List<String> args = new ArrayList<>(List.of(
                "--spring.datasource.url=" + jdbcUrl(database),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--server.port=0"));
        for (String property : extraProperties) {
            args.add("--" + property);
        }
        return new SpringApplicationBuilder(RuleEngineOnlyApplication.class).run(args.toArray(String[]::new));
    }

    private static List<String> history(JdbcClient jdbc, String table) {
        return jdbc.sql("SELECT installed_rank || '|' || coalesce(version, '') || '|' || script || '|' "
                        + "|| coalesce(checksum::text, '') || '|' || installed_on::text FROM " + table
                        + " ORDER BY installed_rank")
                .query(String.class).list();
    }

    private static String freshDatabase() {
        String name = "host_restart_" + DATABASES.incrementAndGet();
        try (var connection = java.sql.DriverManager.getConnection(
                        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("cannot create test database " + name, e);
        }
        return name;
    }

    private static String jdbcUrl(String database) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getFirstMappedPort() + "/" + database;
    }
}
