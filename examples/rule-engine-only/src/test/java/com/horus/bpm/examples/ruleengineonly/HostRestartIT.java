package com.horus.bpm.examples.ruleengineonly;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Failure paths of TR-EMB-06: the host and the engine restart on a database where both histories exist. */
@Testcontainers
class HostRestartIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

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
            assertThat(history(jdbc, "rle_schema_history")).isEqualTo(engineBefore).hasSize(2);
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
