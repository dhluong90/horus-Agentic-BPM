package io.github.dhluong90.bpm.examples.ruleandprocess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.horus.bpm.host.spi.TenantResolver;
import io.github.dhluong90.bpm.process.api.InstanceNotFoundException;
import io.github.dhluong90.bpm.process.api.NoEffectiveVersionException;
import io.github.dhluong90.bpm.process.api.ProcessEngine;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.api.StepFailedException;
import io.github.dhluong90.bpm.process.api.TaskNotFoundException;
import io.github.dhluong90.bpm.process.api.TaskService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The sample end to end on PostgreSQL: the BPMN definition imported at start-up, the card-fee rule set published
 * by the dev seeder, the automatic path and the officer path over HTTP, tenant isolation through the Java API,
 * and the rollback of host and engine rows when a synchronous step fails.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "HORUS_DEMO_PASSWORD=it-pass")
@ActiveProfiles("dev")
@Testcontainers
class RuleAndProcessIT {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String PASSWORD = "it-pass";

    @Container
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(System.getProperty("bpm.test.postgres.image", "postgres:16-alpine"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /**
     * The host's tenant resolver for this test: a {@code TENANT_<id>} authority names the tenant, otherwise the
     * demo tenant. It replaces the default resolver through the SPI, as a host with its own identity model does.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class TenantFromAuthority {
        @Bean
        TenantResolver tenantFromAuthority() {
            return caller -> caller.authorities().stream().filter(a -> a.startsWith("TENANT_"))
                    .map(a -> a.substring(7)).findFirst().or(() -> Optional.of("bank-a"));
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ProcessEngine processes;

    @Autowired
    TaskService tasks;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void startup_imports_the_bpmn_definition_into_its_own_tables_and_history() {
        assertThat(jdbc.sql("SELECT version_num || ' ' || status || ' ' || source_format || ' ' || source FROM pre_definition "
                + "WHERE tenant_id = 'bank-a' AND key_name = 'card-fee-review'").query(String.class).list())
                .containsExactly("1 ACTIVE BPMN_XML STARTUP");
        assertThat(jdbc.sql("SELECT version FROM pre_schema_history WHERE success ORDER BY installed_rank")
                .query(String.class).list()).containsExactly("0", "1");
        assertThat(jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE script LIKE '%pre_%'").query(Long.class)
                .single()).isZero();
    }

    @Test
    void gold_customer_is_priced_at_zero_and_approved_automatically() throws Exception {
        JsonNode submitted = post("tuan", "/api/cards/applications", "{\"applicationId\":\"IT-1001\",\"customerId\":\"C-001\"}", 201);
        assertThat(submitted.get("instanceStatus").stringValue()).isEqualTo("COMPLETED");

        JsonNode instance = get("tuan", "/api/bpm/processes/v1/instances/" + submitted.get("instanceId").stringValue());
        assertThat(instance.at("/variables/pricing/fee/amount").longValue()).isZero();
        assertThat(instance.at("/variables/pricing/fee/currency").stringValue()).isEqualTo("VND");
        assertThat(instance.get("tokens")).allSatisfy(t -> assertThat(t.get("status").stringValue()).isEqualTo("COMPLETED"));
        assertThat(get("tuan", "/api/cards/applications/IT-1001").get("status").stringValue()).isEqualTo("AUTO_APPROVED");
        String decisionId = instance.at("/variables/pricing/decisionId").stringValue();
        assertThat(jdbc.sql("SELECT rule_set_name FROM rle_decision WHERE tenant_id = 'bank-a' AND id = ?").param(decisionId)
                .query(String.class).single()).isEqualTo("card-fee");
        assertThat(jdbc.sql("SELECT event_type FROM pre_history WHERE tenant_id = 'bank-a' AND instance_id = ? "
                + "ORDER BY occurred_at, id").param(instance.get("id").stringValue()).query(String.class).list())
                .contains("INSTANCE_STARTED", "DECISION_EVALUATED", "INSTANCE_ENDED");
    }

    @Test
    void charged_fee_waits_for_a_card_officer_who_approves_it() throws Exception {
        JsonNode submitted = post("tuan", "/api/cards/applications", "{\"applicationId\":\"IT-1002\",\"customerId\":\"C-002\"}", 201);
        assertThat(submitted.get("instanceStatus").stringValue()).isEqualTo("RUNNING");
        JsonNode instance = get("an", "/api/bpm/processes/v1/instances/" + submitted.get("instanceId").stringValue());
        assertThat(instance.at("/variables/pricing/fee/amount").longValue()).isEqualTo(10_000);
        JsonNode task = instance.get("tasks").get(0);
        assertThat(task.get("assignee").stringValue()).isEqualTo("role:CARD_OFFICER");
        String complete = "/api/bpm/processes/v1/tasks/" + task.get("id").stringValue() + ":complete";

        assertThat(send("tuan", complete, "{\"result\":{\"approved\":true}}", "\"0\"").statusCode()).isEqualTo(403);
        JsonNode done = post("an", complete, "{\"result\":{\"approved\":true}}", 200, "\"0\"");

        assertThat(done.get("status").stringValue()).isEqualTo("COMPLETED");
        assertThat(done.at("/tasks/0/completedBy").stringValue()).isEqualTo("an");
        assertThat(get("tuan", "/api/cards/applications/IT-1002").get("status").stringValue()).isEqualTo("APPROVED");
    }

    @Test
    void PRE_003_stale_task_completion_returns_conflict() throws Exception {
        JsonNode submitted = post("tuan", "/api/cards/applications", "{\"applicationId\":\"IT-1003\",\"customerId\":\"C-003\"}", 201);
        JsonNode instance = get("an", "/api/bpm/processes/v1/instances/" + submitted.get("instanceId").stringValue());
        String complete = "/api/bpm/processes/v1/tasks/" + instance.at("/tasks/0/id").stringValue() + ":complete";

        post("an", complete, "{\"result\":{\"approved\":false}}", 200, "\"0\"");
        HttpResponse<String> second = send("an", complete, "{\"result\":{\"approved\":true}}", "\"0\"");

        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(JSON.readTree(second.body()).get("code").stringValue()).isEqualTo("VERSION_CONFLICT");
        assertThat(get("tuan", "/api/cards/applications/IT-1003").get("status").stringValue()).isEqualTo("REJECTED");
        assertThat(jdbc.sql("SELECT count(*) FROM pre_history WHERE tenant_id = 'bank-a' AND instance_id = ? "
                + "AND event_type = 'TASK_COMPLETED'").param(instance.get("id").stringValue()).query(Long.class).single())
                .isEqualTo(1);
    }

    @Test
    void a_failed_step_rolls_back_the_host_row_and_every_engine_row() throws Exception {
        HttpResponse<String> response = send("tuan", "/api/cards/applications",
                "{\"applicationId\":\"IT-1099\",\"customerId\":\"C-404\"}", null);

        assertThat(response.statusCode()).isEqualTo(422);
        JsonNode problem = JSON.readTree(response.body());
        assertThat(problem.get("code").stringValue()).isEqualTo("STEP_FAILED");
        assertThat(problem.get("nodeId").stringValue()).isEqualTo("load-profile");
        assertThat(problem.get("reason").stringValue()).isEqualTo("UNHANDLED_BUSINESS_ERROR");
        assertThat(jdbc.sql("SELECT count(*) FROM card_application WHERE application_id = 'IT-1099'").query(Long.class)
                .single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM pre_instance WHERE business_key = 'IT-1099'").query(Long.class)
                .single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM pre_token t JOIN pre_instance i ON i.tenant_id = t.tenant_id "
                + "AND i.id = t.instance_id WHERE i.business_key = 'IT-1099'").query(Long.class).single()).isZero();
    }

    @Test
    void tenants_never_see_each_others_instances_tasks_or_definitions() throws Exception {
        JsonNode submitted = post("tuan", "/api/cards/applications", "{\"applicationId\":\"IT-2001\",\"customerId\":\"C-002\"}", 201);
        String instanceId = submitted.get("instanceId").stringValue();
        String taskId = get("an", "/api/bpm/processes/v1/instances/" + instanceId).at("/tasks/0/id").stringValue();

        as("officer-b", List.of("ROLE_CARD_OFFICER", "TENANT_bank-b"), () -> {
            assertThatThrownBy(() -> processes.instance(instanceId)).isInstanceOf(InstanceNotFoundException.class);
            assertThatThrownBy(() -> tasks.complete(taskId, Map.of("approved", true), null, null))
                    .isInstanceOf(TaskNotFoundException.class);
            return null;
        });
        as("teller-b", List.of("ROLE_TELLER", "TENANT_bank-b"), () -> {
            assertThatThrownBy(() -> processes.start(StartRequest.of("card-fee-review").withBusinessKey("IT-2001")))
                    .isInstanceOf(NoEffectiveVersionException.class);
            return null;
        });
        // The same task stays open for bank-a.
        assertThat(get("an", "/api/bpm/processes/v1/instances/" + instanceId).at("/tasks/0/status").stringValue())
                .isEqualTo("OPEN");
    }

    @Test
    void a_decision_the_caller_may_not_evaluate_fails_closed_at_the_business_rule_task() {
        StepFailedException failure = as("starter-without-rules", List.of("processes:start"), () -> {
            try {
                processes.start(StartRequest.of("card-fee-review").withBusinessKey("IT-3001")
                        .withVariables(Map.of("applicationId", "IT-3001", "customerId", "C-001")));
                return null;
            } catch (StepFailedException e) {
                return e;
            }
        });
        assertThat(failure).isNotNull();
        assertThat(failure.nodeId()).isEqualTo("price-card");
        assertThat(failure.reason()).isEqualTo("PERMISSION_DENIED");
        assertThat(jdbc.sql("SELECT count(*) FROM pre_instance WHERE business_key = 'IT-3001'").query(Long.class)
                .single()).isZero();
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private static <T> T as(String user, List<String> authorities, Supplier<T> work) {
        TestingAuthenticationToken token = new TestingAuthenticationToken(user, "n/a",
                authorities.stream().map(SimpleGrantedAuthority::new).toList());
        token.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(token);
        try {
            return work.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private JsonNode post(String user, String path, String body, int expected) throws Exception {
        return post(user, path, body, expected, null);
    }

    private JsonNode post(String user, String path, String body, int expected, String ifMatch) throws Exception {
        HttpResponse<String> response = send(user, path, body, ifMatch);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> send(String user, String path, String body, String ifMatch) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", basic(user)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode get(String user, String path) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", basic(user)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static String basic(String user) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
    }
}
