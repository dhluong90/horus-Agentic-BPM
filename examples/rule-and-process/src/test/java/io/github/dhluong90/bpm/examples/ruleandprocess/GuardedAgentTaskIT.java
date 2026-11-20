package io.github.dhluong90.bpm.examples.ruleandprocess;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.internal.service.AsyncStepExecutor;
import io.github.dhluong90.bpm.process.spi.AdapterResult;
import io.github.dhluong90.bpm.process.spi.AgentAdapter;
import io.github.dhluong90.bpm.process.test.StubAgentAdapter;
import io.github.dhluong90.bpm.rules.api.ImportRequest;
import io.github.dhluong90.bpm.rules.api.RuleSetExistsException;
import io.github.dhluong90.bpm.rules.api.RuleSetVersion;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A guarded agent step end to end, over HTTP on PostgreSQL (#52, PRE-010): the host imports
 * {@code loan-document-assessment} with its agent step, a teller starts a loan, the job poller's executor asks the
 * stub document assessor, and the answer is checked against the output schema and the bank's
 * {@code agent-output-guardrails} rule set before the loan moves on.
 *
 * <p>The stub assessor is naive on purpose: it recommends approval for every loan, with a risk score that grows with
 * the amount. The guardrail is what stops it: a risk score of 70 or more (R2) opens a four-eyes review task for a
 * compliance reviewer instead of moving the loan on.
 *
 * <p>Users: {@code kim} is a teller who also holds the compliance reviewer role, so she can start a loan but may not
 * review her own; {@code thu} is a compliance reviewer. The job poller is off; each test runs the agent step once
 * through the executor, so nothing waits on a timer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "HORUS_DEMO_PASSWORD=it-pass",
    "horus.bpm.processes.jobs.enabled=false",
    "horus.bpm.processes.definitions.locations=classpath:processes/card-fee-review.bpmn,"
            + "classpath:processes/loan-document-assessment.bpmn",
    "horus.bpm.processes.security.role-permissions.ROLE_COMPLIANCE_REVIEWER=processes:read,processes:task:work"
})
@ActiveProfiles("dev")
@Testcontainers
class GuardedAgentTaskIT {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String PASSWORD = "it-pass";
    static final String INSTANCES = "/api/bpm/processes/v1/instances";
    static final String TASKS = "/api/bpm/processes/v1/tasks/";
    /** A loan of 1.5 billion VND: the stub assessor scores it 85 and still recommends approval. */
    static final long LARGE_LOAN = 1_500_000_000L;
    /** A loan of 250 million VND: scored 20. */
    static final long SMALL_LOAN = 250_000_000L;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Agents {

        /** The naive document assessor: always APPROVE, risk score 85 from one billion VND, else 20. */
        @Bean
        AgentAdapter documentAssessor() {
            return StubAgentAdapter.of("documents.assess", invocation -> {
                long amount = ((Number) invocation.inputs().get("requestedAmount")).longValue();
                return AdapterResult.completed(Map.of("riskScore", amount >= 1_000_000_000L ? 85 : 20,
                        "recommendation", "APPROVE", "summary", "Salary slips and ID card of applicant "
                                + invocation.inputs().get("applicationId")));
            });
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    InMemoryUserDetailsManager users;

    @Autowired
    AsyncStepExecutor executor;

    @Autowired
    VersionService ruleSets;

    private static boolean published;

    @BeforeEach
    void reviewersAndGuardrails() {
        user("kim", "ROLE_TELLER", "ROLE_COMPLIANCE_REVIEWER");
        user("thu", "ROLE_COMPLIANCE_REVIEWER");
        publishGuardrailsOnce();
    }

    @Test
    void PRE_010_agent_task_guardrail_violation_routes_to_human() throws Exception {
        // Given a loan of 1.5 billion VND, started by kim, which the naive assessor approves with a risk score of 85
        String loan = start("kim", "LOAN-AGENT-0001", LARGE_LOAN);

        // When the agent step runs
        runAgentStep();

        // Then the guardrail refuses the answer (R2, HIGH_RISK_SCORE) and a compliance reviewer gets a task
        JsonNode instance = instance(loan);
        assertThat(instance.at("/variables/assessment").isMissingNode()).isTrue();
        assertThat(instance.at("/tasks/0/nodeId").stringValue()).isEqualTo("assess-documents");
        assertThat(instance.at("/tasks/0/assignee").stringValue()).isEqualTo("role:COMPLIANCE_REVIEWER");
        String task = instance.at("/tasks/0/id").stringValue();
        JsonNode view = task("thu", task);
        assertThat(view.get("fourEyes").booleanValue()).isTrue();
        JsonNode review = view.get("review");
        assertThat(review.get("code").stringValue()).isEqualTo("GUARDRAIL_VIOLATION");
        assertThat(review.get("reasons").toString()).isEqualTo("[\"HIGH_RISK_SCORE\"]");
        assertThat(review.get("ruleIds").toString()).isEqualTo("[\"R2\"]");
        assertThat(review.at("/output/riskScore").asInt()).isEqualTo(85);
        assertThat(review.at("/output/recommendation").stringValue()).isEqualTo("APPROVE");
        assertThat(review.at("/output/summary").stringValue()).isEqualTo("***");
        assertThat(review.at("/inputs/requestedAmount").asLong()).isEqualTo(LARGE_LOAN);
        assertThat(review.get("guardrailDecisionId").stringValue()).isNotBlank();
        assertThat(history(loan, "ASYNC_RESULT_REFUSED")).isEqualTo(1);
        assertThat(history(loan, "ASYNC_RESULT_ACCEPTED")).isZero();

        // When kim, who started the loan, tries to review it, and then thu refers it
        HttpResponse<String> ownReview = send("kim", "POST", TASKS + task + ":claim", null,
                Map.of("If-Match", "\"0\"", "Idempotency-Key", "kim-claims-" + task));
        HttpResponse<String> claimed = send("thu", "POST", TASKS + task + ":claim", null,
                Map.of("If-Match", "\"0\"", "Idempotency-Key", "thu-claims-" + task));
        HttpResponse<String> referred = send("thu", "POST", TASKS + task + ":complete",
                "{\"result\":{\"riskScore\":85,\"recommendation\":\"REFER\"}}",
                Map.of("If-Match", "\"1\"", "Idempotency-Key", "thu-refers-" + task, "Content-Type", "application/json"));

        // Then four eyes hold, and the reviewer's answer completes the step: the loan moves on to underwriting
        assertThat(problem(ownReview)).isEqualTo("403 SELF_APPROVAL_FORBIDDEN");
        assertThat(claimed.statusCode()).as(claimed.body()).isEqualTo(200);
        assertThat(referred.statusCode()).as(referred.body()).isEqualTo(200);
        JsonNode after = instance(loan);
        assertThat(after.at("/variables/assessment/recommendation").stringValue()).isEqualTo("REFER");
        assertThat(openTaskNodes(after)).containsExactly("underwrite");
    }

    @Test
    void PRE_010_an_answer_the_guardrail_allows_moves_the_loan_on_without_a_review() throws Exception {
        String loan = start("kim", "LOAN-AGENT-0002", SMALL_LOAN);

        runAgentStep();

        JsonNode instance = instance(loan);
        assertThat(instance.at("/variables/assessment/riskScore").asInt()).isEqualTo(20);
        assertThat(openTaskNodes(instance)).containsExactly("underwrite");
        assertThat(history(loan, "ASYNC_RESULT_ACCEPTED")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT details #>> '{output,summary}' FROM pre_history WHERE instance_id = ? "
                + "AND event_type = 'ASYNC_RESULT_ACCEPTED'").param(loan).query(String.class).single()).isEqualTo("***");
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private void runAgentStep() throws InterruptedException {
        assertThat(executor.pollOnce()).isEqualTo(1);
        assertThat(executor.awaitIdle(Duration.ofSeconds(30))).isTrue();
    }

    private String start(String user, String applicationId, long amount) throws Exception {
        HttpResponse<String> started = send(user, "POST", INSTANCES, "{\"definitionKey\":\"loan-document-assessment\","
                + "\"businessKey\":\"" + applicationId + "\",\"variables\":{\"applicationId\":\"" + applicationId
                + "\",\"amount\":" + amount + "}}", Map.of("Content-Type", "application/json",
                "Idempotency-Key", "start-" + applicationId));
        assertThat(started.statusCode()).as(started.body()).isEqualTo(201);
        return JSON.readTree(started.body()).get("id").stringValue();
    }

    private static List<String> openTaskNodes(JsonNode instance) {
        List<String> nodes = new java.util.ArrayList<>();
        instance.get("tasks").forEach(t -> {
            if (!"COMPLETED".equals(t.get("status").stringValue())) {
                nodes.add(t.get("nodeId").stringValue());
            }
        });
        return nodes;
    }

    private JsonNode instance(String id) throws Exception {
        HttpResponse<String> read = send("thu", "GET", INSTANCES + "/" + id, null, Map.of());
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        return JSON.readTree(read.body());
    }

    private JsonNode task(String user, String id) throws Exception {
        HttpResponse<String> read = send(user, "GET", TASKS + id, null, Map.of());
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        return JSON.readTree(read.body());
    }

    private long history(String instanceId, String type) {
        return jdbc.sql("SELECT count(*) FROM pre_history WHERE instance_id = ? AND event_type = ?")
                .params(instanceId, type).query(Long.class).single();
    }

    private void user(String name, String... authorities) {
        if (!users.userExists(name)) {
            users.createUser(User.withUsername(name).password("{noop}" + PASSWORD).authorities(authorities).build());
        }
    }

    /** Publishes the guardrail rule set through maker-checker: one identity requests, another approves. */
    private void publishGuardrailsOnce() {
        if (published) {
            return;
        }
        byte[] source;
        try (InputStream in = new ClassPathResource("rules/agent-output-guardrails.yaml").getInputStream()) {
            source = in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            RuleSetVersion draft = as("risk-author", "rules:author", () -> ruleSets.importRuleSet(ImportRequest.yaml(source)));
            RuleSetVersion requested = as("risk-author", "rules:author",
                    () -> ruleSets.requestPublish("agent-output-guardrails", draft.version(), WriteOptions.none()));
            as("risk-approver", "rules:approve", () -> ruleSets.approve("agent-output-guardrails", draft.version(),
                    new WriteOptions(null, requested.lockVersion(), null)));
        } catch (RuleSetExistsException e) {
            // published by an earlier test in this context
        }
        published = true;
    }

    private static <T> T as(String user, String permission, Supplier<T> work) {
        TestingAuthenticationToken token = new TestingAuthenticationToken(user, "n/a",
                List.of(new SimpleGrantedAuthority(permission)));
        token.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(token);
        try {
            return work.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static String problem(HttpResponse<String> response) {
        assertThat(response.headers().firstValue("Content-Type")).as(response.body()).hasValue("application/problem+json");
        return response.statusCode() + " " + JSON.readTree(response.body()).get("code").stringValue();
    }

    private HttpResponse<String> send(String user, String method, String path, String body, Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString((user + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8)))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
