package io.github.dhluong90.bpm.examples.guardedagent;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.internal.service.AsyncStepExecutor;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
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
 * The guarded agent journey of the example host, over HTTP on PostgreSQL (#66, PRE-185, PRE-186): the dev seeder
 * publishes the guardrail rule set through maker-checker and deploys {@code loan-document-review}; a teller sends the
 * three seeded loan applications to review; the agent step asks the document assessor; the guardrail lets two answers
 * through and refuses the third, which a compliance reviewer reviews under four eyes; and the case can be drawn on
 * its diagram from the engine APIs alone.
 *
 * <p>Users: {@code kim} is a teller, {@code thu} a compliance reviewer. The test adds {@code hoa}, a teller who also
 * holds the reviewer role, to show she cannot review the loan she sent. The job poller is off; each test runs the
 * agent step once through the executor, so nothing waits on a timer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "HORUS_DEMO_PASSWORD=it-pass",
    "horus.bpm.processes.jobs.enabled=false"
})
@ActiveProfiles("dev")
@Testcontainers
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.MethodName.class)
class GuardedAgentJourneyIT {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String PASSWORD = "it-pass";
    static final String API = "/api/bpm/processes/v1";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    InMemoryUserDetailsManager users;

    @Autowired
    AsyncStepExecutor executor;

    @BeforeEach
    void tellerWhoAlsoReviews() {
        if (!users.userExists("hoa")) {
            users.createUser(User.withUsername("hoa").password("{noop}" + PASSWORD)
                    .roles("TELLER", "COMPLIANCE_REVIEWER").build());
        }
    }

    @Test
    void PRE_185_an_agent_answer_the_guardrail_allows_moves_the_loan_on_and_a_refused_one_goes_to_a_second_person()
            throws Exception {
        // Given the three seeded applications of bank-a
        HttpResponse<String> listed = send("kim", "GET", "/loan-applications", null, Map.of());
        assertThat(listed.statusCode()).as(listed.body()).isEqualTo(200);
        assertThat(JSON.readTree(listed.body()).findValuesAsString("id"))
                .containsExactly("LN-2026-0101", "LN-2026-0102", "LN-2026-0103");

        // When kim sends LN-2026-0101 (250,000,000 VND, complete file) to review and the agent step runs
        String small = review("kim", "LN-2026-0101");
        runAgentStep();

        // Then the assessor's answer (risk 20, APPROVE) passes the guardrail and the loan waits for an underwriter
        JsonNode allowed = instance("thu", small);
        assertThat(allowed.at("/variables/assessment/riskScore").asInt()).isEqualTo(20);
        assertThat(allowed.at("/variables/assessment/recommendation").stringValue()).isEqualTo("APPROVE");
        assertThat(openTaskNodes(allowed)).containsExactly("underwrite");
        assertThat(history(small, "ASYNC_RESULT_ACCEPTED")).isEqualTo(1);

        // When hoa sends LN-2026-0102 (1,500,000,000 VND), which the naive assessor scores 85 and still approves
        String large = review("hoa", "LN-2026-0102");
        runAgentStep();

        // Then the guardrail refuses the answer (R2, HIGH_RISK_SCORE): nothing of it is accepted, and a compliance
        // reviewer gets a four-eyes review task with the masked answer
        JsonNode refused = instance("thu", large);
        assertThat(refused.at("/variables/assessment").isMissingNode()).isTrue();
        assertThat(refused.at("/tasks/0/nodeId").stringValue()).isEqualTo("assess-documents");
        assertThat(refused.at("/tasks/0/assignee").stringValue()).isEqualTo("role:COMPLIANCE_REVIEWER");
        String task = refused.at("/tasks/0/id").stringValue();
        JsonNode view = json(send("thu", "GET", API + "/tasks/" + task, null, Map.of()));
        assertThat(view.get("fourEyes").booleanValue()).isTrue();
        assertThat(view.at("/review/code").stringValue()).isEqualTo("GUARDRAIL_VIOLATION");
        assertThat(view.at("/review/reasons").toString()).isEqualTo("[\"HIGH_RISK_SCORE\"]");
        assertThat(view.at("/review/ruleIds").toString()).isEqualTo("[\"R2\"]");
        assertThat(view.at("/review/output/riskScore").asInt()).isEqualTo(85);
        assertThat(view.at("/review/output/summary").stringValue()).isEqualTo("***");
        assertThat(history(large, "ASYNC_RESULT_REFUSED")).isEqualTo(1);

        // When hoa, who sent the loan, tries to review it, and then thu refers it with a reason
        HttpResponse<String> own = send("hoa", "POST", API + "/tasks/" + task + ":claim", null,
                Map.of("If-Match", "\"0\"", "Idempotency-Key", "hoa-claims-" + task));
        HttpResponse<String> claimed = send("thu", "POST", API + "/tasks/" + task + ":claim", null,
                Map.of("If-Match", "\"0\"", "Idempotency-Key", "thu-claims-" + task));
        HttpResponse<String> referred = send("thu", "POST", API + "/tasks/" + task + ":complete",
                "{\"result\":{\"riskScore\":85,\"recommendation\":\"REFER\"},"
                        + "\"reason\":\"Income for 1.5 billion VND needs a second look\"}",
                Map.of("If-Match", "\"1\"", "Idempotency-Key", "thu-refers-" + task,
                        "Content-Type", "application/json"));

        // Then four eyes hold, and the reviewer's answer completes the step: the loan goes to an underwriter
        assertThat(own.statusCode()).as(own.body()).isEqualTo(403);
        assertThat(JSON.readTree(own.body()).get("code").stringValue()).isEqualTo("SELF_APPROVAL_FORBIDDEN");
        assertThat(claimed.statusCode()).as(claimed.body()).isEqualTo(200);
        assertThat(referred.statusCode()).as(referred.body()).isEqualTo(200);
        JsonNode reviewed = instance("thu", large);
        assertThat(reviewed.at("/variables/assessment/recommendation").stringValue()).isEqualTo("REFER");
        assertThat(openTaskNodes(reviewed)).containsExactly("underwrite");
        assertThat(history(large, "HUMAN_OVERRIDE")).isEqualTo(1);

        // When kim sends LN-2026-0103 (two of three documents)
        String partial = review("kim", "LN-2026-0103");
        runAgentStep();

        // Then the assessor refers it itself, which the guardrail allows
        JsonNode referredByAgent = instance("thu", partial);
        assertThat(referredByAgent.at("/variables/assessment/recommendation").stringValue()).isEqualTo("REFER");
        assertThat(referredByAgent.at("/variables/assessment/riskScore").asInt()).isEqualTo(55);
        assertThat(openTaskNodes(referredByAgent)).containsExactly("underwrite");

        // And sending an application again returns the instance it already has
        assertThat(review("kim", "LN-2026-0101")).isEqualTo(small);
    }

    @Test
    void PRE_186_a_case_is_drawn_on_its_diagram_from_the_engine_apis_alone() throws Exception {
        // Given LN-2026-0102 after its review, waiting for an underwriter
        String loan = jdbc.sql("SELECT instance_id FROM loan_application WHERE tenant_id = 'bank-a' AND "
                + "id = 'LN-2026-0102'").query(String.class).optional().orElse(null);
        if (loan == null) {
            loan = review("hoa", "LN-2026-0102");
            runAgentStep();
        }

        // When the reviewer reads the definition as BPMN with its layout, and the instance
        HttpResponse<String> diagram = send("thu", "GET", API + "/definitions/loan-document-review/versions/1/export"
                + "?format=bpmn", null, Map.of());
        JsonNode instance = instance("thu", loan);

        // Then the document draws every step, and the instance names where the case stands
        assertThat(diagram.statusCode()).as(diagram.body()).isEqualTo(200);
        assertThat(diagram.headers().firstValue("Content-Type").orElse("")).startsWith("application/bpmn+xml");
        for (String step : List.of("start", "assess-documents", "underwrite", "end")) {
            assertThat(diagram.body()).contains("bpmnElement=\"" + step + "\"");
        }
        assertThat(instance.get("definitionKey").stringValue()).isEqualTo("loan-document-review");
        List<String> live = new ArrayList<>();
        instance.get("tokens").forEach(t -> {
            if ("FLOW".equals(t.get("kind").stringValue()) && !"COMPLETED".equals(t.get("status").stringValue())) {
                live.add(t.get("nodeId").stringValue() + ":" + t.get("status").stringValue());
            }
        });
        assertThat(live).containsAnyOf("underwrite:WAITING_USER", "assess-documents:WAITING_USER");

        // And its path is recorded step by step, in order, the refused answer and the review included
        List<String> path = jdbc.sql("SELECT event_type FROM pre_history WHERE instance_id = ? AND event_type IN "
                + "('INSTANCE_STARTED','ASYNC_STEP_SCHEDULED','ASYNC_RESULT_REFUSED','STEP_ROUTED_TO_REVIEW') "
                + "ORDER BY occurred_at, id").param(loan).query(String.class).list();
        assertThat(path).containsExactly("INSTANCE_STARTED", "ASYNC_STEP_SCHEDULED", "ASYNC_RESULT_REFUSED",
                "STEP_ROUTED_TO_REVIEW");
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private String review(String user, String application) throws Exception {
        HttpResponse<String> started = send(user, "POST", "/loan-applications/" + application + ":review", null,
                Map.of());
        assertThat(started.statusCode()).as(started.body()).isEqualTo(200);
        return JSON.readTree(started.body()).get("id").stringValue();
    }

    private void runAgentStep() throws InterruptedException {
        assertThat(executor.pollOnce()).isEqualTo(1);
        assertThat(executor.awaitIdle(Duration.ofSeconds(30))).isTrue();
    }

    private JsonNode instance(String user, String id) throws Exception {
        return json(send(user, "GET", API + "/instances/" + id, null, Map.of()));
    }

    private static JsonNode json(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static List<String> openTaskNodes(JsonNode instance) {
        List<String> nodes = new ArrayList<>();
        instance.get("tasks").forEach(t -> {
            if (!"COMPLETED".equals(t.get("status").stringValue())) {
                nodes.add(t.get("nodeId").stringValue());
            }
        });
        return nodes;
    }

    private long history(String instanceId, String type) {
        return jdbc.sql("SELECT count(*) FROM pre_history WHERE instance_id = ? AND event_type = ?")
                .params(instanceId, type).query(Long.class).single();
    }

    private HttpResponse<String> send(String user, String method, String path, String body, Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString((user + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8)))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(request::header);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
