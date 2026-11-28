package io.github.dhluong90.bpm.examples.ruleandprocess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.postgres.DatabaseRoleCheck;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Two database roles (issue 700). The roles come from {@code deploy/postgres/app-role-grants.sql}, the script an
 * operator runs. A separate migration step runs as {@code horus_owner} (the host's own Flyway, and both engines); the host
 * serves traffic as {@code horus_app} and holds no owner setting, with {@code role-check=enforce} so the host does not start if the app role could own a table.
 * The test then works through the host (a card application over HTTP) and tries, as the app role, every way to
 * switch off a trigger or change the audit and seal tables.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "HORUS_DEMO_PASSWORD=it-pass")
@ActiveProfiles("dev")
@Testcontainers
class TwoRoleDatabaseIT {

    static final String DATABASE = "bank";
    static final String OWNER = "horus_owner";
    static final String OWNER_PASSWORD = "owner-pw";
    static final String APP = "horus_app";
    static final String APP_PASSWORD = "app-pw";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        try (Connection admin = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + DATABASE);
        }
        String url = POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + DATABASE + "$1");
        try (Connection admin = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
                Statement statement = admin.createStatement()) {
            statement.execute(script());
        }
        migrateAsOwner(url);
        // The serving host gets the application role only: no owner settings, no migrations of its own.
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> APP);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.enabled", () -> "false");
        for (String engine : List.of("rules", "processes")) {
            String prefix = "horus.bpm." + engine + ".";
            registry.add(prefix + "migrate-on-start", () -> "false");
            registry.add(prefix + "database.role-check", () -> "enforce");
        }
    }

    /**
     * The migration step: the same application, connected as the owner, which migrates the host's tables and both
     * engines, grants the application role its rights and ends. The serving host below never sees the owner's password.
     */
    static void migrateAsOwner(String url) {
        new SpringApplicationBuilder(RuleAndProcessApplication.class).web(WebApplicationType.SERVLET).profiles("dev")
                .run("--server.port=0", "--spring.datasource.url=" + url, "--spring.datasource.username=" + OWNER,
                        "--spring.datasource.password=" + OWNER_PASSWORD, "--HORUS_DEMO_PASSWORD=it-pass",
                        "--horus.bpm.rules.database.app-role=" + APP, "--horus.bpm.processes.database.app-role=" + APP)
                .close();
    }

    /** The operator's script with the psql variables filled in. */
    private static String script() throws Exception {
        Path file = Path.of("..", "..", "deploy", "postgres", "app-role-grants.sql");
        return Files.readString(file)
                .replace(":\"database\"", "\"" + DATABASE + "\"")
                .replace(":\"owner\"", "\"" + OWNER + "\"")
                .replace(":\"app\"", "\"" + APP + "\"")
                .replace(":'owner_password'", "'" + OWNER_PASSWORD + "'")
                .replace(":'app_password'", "'" + APP_PASSWORD + "'");
    }

    @LocalServerPort
    int port;

    /** The host's data source: the application role. */
    @Autowired
    JdbcClient jdbc;

    @Test
    void the_host_serves_traffic_as_the_application_role_and_the_engines_work() throws Exception {
        assertThat(jdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo(APP);

        String body = "{\"applicationId\":\"IT-2001\",\"customerId\":\"C-001\"}";
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/cards/applications"))
                        .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                                "tuan:it-pass".getBytes(StandardCharsets.UTF_8)))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(response.body()).contains("COMPLETED");
        // the engines wrote through the application role
        assertThat(jdbc.sql("SELECT count(*) FROM pre_history").query(Long.class).single()).isPositive();
        assertThat(jdbc.sql("SELECT count(*) FROM rle_decision").query(Long.class).single()).isNotNegative();
    }

    @Test
    void the_owner_owns_every_table_and_the_application_role_owns_none_and_holds_no_trigger_privilege() {
        assertThat(jdbc.sql("""
                SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tableowner <> :owner
                  AND tablename NOT LIKE 'pg\\_%'""").param("owner", OWNER).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tableowner = :app")
                .param("app", APP).query(Long.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM pg_class c WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('r', 'p')
                  AND (has_table_privilege(current_user, c.oid, 'TRIGGER')
                    OR has_table_privilege(current_user, c.oid, 'TRUNCATE'))""")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT rolsuper OR rolcreaterole OR rolbypassrls FROM pg_roles WHERE rolname = current_user")
                .query(Boolean.class).single()).isFalse();
    }

    @Test
    void the_application_role_cannot_switch_off_a_trigger_or_alter_the_audit_and_seal_tables() {
        List<String> refused = List.of(
                // the seal of an approved rule version
                "ALTER TABLE rle_version DISABLE TRIGGER ALL",
                "ALTER TABLE rle_version DISABLE TRIGGER rle_version_legacy_seal_guard",
                "DROP TRIGGER rle_version_legacy_seal_guard ON rle_version",
                // the append-only decision log (a partitioned table) and the rule version history
                "ALTER TABLE rle_decision DISABLE TRIGGER rle_decision_no_change",
                "ALTER TABLE rle_version_history DISABLE TRIGGER ALL",
                "DROP TRIGGER rle_version_history_no_change ON rle_version_history",
                "TRUNCATE rle_decision",
                "UPDATE rle_version_history SET tenant_id = tenant_id",
                "DELETE FROM rle_version_history",
                // the process engine's audit trail and approval record
                "ALTER TABLE pre_history DISABLE TRIGGER pre_history_no_change",
                "ALTER TABLE pre_definition_approval DISABLE TRIGGER ALL",
                "DROP TRIGGER pre_definition_approval_no_change ON pre_definition_approval",
                "UPDATE pre_history SET tenant_id = tenant_id",
                "DELETE FROM pre_definition_approval",
                "TRUNCATE pre_history",
                // the schema
                "ALTER TABLE rle_rule_set ADD COLUMN sneaky text",
                "ALTER TABLE pre_task DROP COLUMN tenant_id",
                "DROP TABLE pre_task",
                "ALTER TABLE rle_decision OWNER TO " + APP,
                "CREATE TRIGGER sneaky BEFORE INSERT ON pre_task FOR EACH ROW EXECUTE FUNCTION pre_history_append_only()",
                "CREATE TABLE sneaky (id int)");
        for (String statement : refused) {
            try {
                jdbc.sql(statement).update();
            } catch (org.springframework.dao.DataAccessException e) {
                Throwable cause = e.getMostSpecificCause();
                assertThat(cause).as(statement).isInstanceOf(org.postgresql.util.PSQLException.class);
                assertThat(((org.postgresql.util.PSQLException) cause).getSQLState())
                        .as(statement + " -> " + cause.getMessage()).isEqualTo("42501");
                continue;
            }
            throw new AssertionError("the application role was allowed to run: " + statement);
        }
    }

    @Test
    void the_role_check_clears_the_application_role_and_flags_the_owner() throws SQLException {
        DataSource app = new DriverManagerDataSource(POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + DATABASE + "$1"),
                APP, APP_PASSWORD);
        DataSource owner = new DriverManagerDataSource(POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + DATABASE + "$1"),
                OWNER, OWNER_PASSWORD);
        assertThat(new DatabaseRoleCheck(app).problems()).isEmpty();
        assertThat(new io.github.dhluong90.bpm.rules.postgres.DatabaseRoleCheck(app).problems()).isEmpty();
        assertThat(new DatabaseRoleCheck(owner).problems()).singleElement().asString().contains("owns").contains("pre_");
        assertThat(new io.github.dhluong90.bpm.rules.postgres.DatabaseRoleCheck(owner).problems()).singleElement()
                .asString().contains("owns").contains("rle_");
        // and the owner could in fact switch a trigger off, which is what the app role must never be able to do
        assertThat(JdbcClient.create(owner).sql("SELECT has_table_privilege('pre_history', 'TRIGGER')")
                .query(Boolean.class).single()).isTrue();
    }

    private static String url() {
        return POSTGRES.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + DATABASE + "$1");
    }

    /** Outside the dev profile an unset role-check is enforce: a serving role that owns the tables stops the start. */
    @Test
    void outside_dev_a_serving_role_that_owns_the_tables_refuses_to_start_with_a_named_code() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(RuleAndProcessApplication.class)
                .web(WebApplicationType.SERVLET)
                .run("--server.port=0", "--spring.datasource.url=" + url(), "--spring.datasource.username=" + OWNER,
                        "--spring.datasource.password=" + OWNER_PASSWORD, "--spring.flyway.enabled=false",
                        "--horus.bpm.rules.migrate-on-start=false", "--horus.bpm.processes.migrate-on-start=false",
                        "--HORUS_DEMO_PASSWORD=it-pass").close())
                .hasStackTraceContaining("HORUS-DB-ROLE-001");
    }

    /** The documented opt-out outside dev starts, and says so loudly (HORUS-DB-ROLE-002, logged at ERROR). */
    @Test
    void outside_dev_the_opt_out_starts_the_host() {
        new SpringApplicationBuilder(RuleAndProcessApplication.class)
                .web(WebApplicationType.SERVLET)
                .run("--server.port=0", "--spring.datasource.url=" + url(), "--spring.datasource.username=" + OWNER,
                        "--spring.datasource.password=" + OWNER_PASSWORD, "--spring.flyway.enabled=false",
                        "--horus.bpm.rules.migrate-on-start=false", "--horus.bpm.processes.migrate-on-start=false",
                        "--horus.bpm.rules.database.role-check=warn", "--horus.bpm.processes.database.role-check=warn",
                        "--HORUS_DEMO_PASSWORD=it-pass").close();
    }
}
