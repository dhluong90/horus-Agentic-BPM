package io.github.dhluong90.bpm.examples.ruleandprocess;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.host.spi.TenantResolver;
import io.github.dhluong90.bpm.process.internal.service.AsyncStepExecutor;
import io.github.dhluong90.bpm.process.spi.AdapterCall;
import io.github.dhluong90.bpm.process.spi.AdapterResult;
import io.github.dhluong90.bpm.process.spi.ServiceAdapter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
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
 * The completion API of a remote step over HTTP on PostgreSQL (ADR-0008): a loan application asks the credit bureau
 * for a score, the bureau adapter hands the call off, and the bureau's callback client reports the score through
 * {@code POST /api/bpm/processes/v1/async-steps/{jobId}:complete} with the attempt id and the completion token.
 *
 * <p>Users: {@code tuan} (teller) applies; {@code bureau} holds {@code processes:step:complete} and the bureau's
 * completer role; {@code sanctions} holds the permission with the sanctions provider's completer role only;
 * {@code bureau-b} is the bureau client of {@code bank-b}; {@code an} (card officer) has no completion permission.
 * The job poller is off; each test claims the step once through the executor, so nothing waits on a timer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "HORUS_DEMO_PASSWORD=it-pass",
    "horus.bpm.processes.jobs.enabled=false",
    "horus.bpm.processes.definitions.locations=classpath:processes/card-fee-review.bpmn,"
            + "classpath:processes/loan-bureau-check.bpmn",
    "horus.bpm.processes.security.role-permissions.ROLE_BUREAU_CALLBACK=processes:step:complete",
    "horus.bpm.processes.security.role-permissions.ROLE_SANCTIONS_CALLBACK=processes:step:complete",
    "horus.bpm.processes.adapters.completer-roles[creditBureau.score]=ROLE_BUREAU_CALLBACK",
    "horus.bpm.processes.adapters.completer-roles[sanctions.screen]=ROLE_SANCTIONS_CALLBACK"
})
@ActiveProfiles("dev")
@Testcontainers
class AsyncStepCompletionIT {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String PASSWORD = "it-pass";
    static final String STEPS = "/api/bpm/processes/v1/async-steps/";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** A {@code TENANT_<id>} authority names the tenant, otherwise {@code bank-a}. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Adapters {
        static final List<AdapterCall> CALLS = new CopyOnWriteArrayList<>();

        @Bean
        TenantResolver tenantFromAuthority() {
            return caller -> caller.authorities().stream().filter(a -> a.startsWith("TENANT_"))
                    .map(a -> a.substring(7)).findFirst().or(() -> Optional.of("bank-a"));
        }

        /** The bureau takes the request and calls back later. */
        @Bean
        ServiceAdapter creditBureau() {
            return adapter("creditBureau.score", call -> {
                CALLS.add(call);
                return new AdapterResult.Accepted();
            });
        }

        /** Another provider on the same host, never called by the loan process. */
        @Bean
        ServiceAdapter sanctionsScreening() {
            return adapter("sanctions.screen", call -> AdapterResult.completed(Map.of("cleared", true)));
        }

        private static ServiceAdapter adapter(String name, java.util.function.Function<AdapterCall, AdapterResult> f) {
            return new ServiceAdapter() {
                @Override
                public String name() {
                    return name;
                }

                @Override
                public AdapterResult execute(AdapterCall call) {
                    return f.apply(call);
                }
            };
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

    @BeforeEach
    void callbackClients() {
        user("bureau", "ROLE_BUREAU_CALLBACK");
        user("sanctions", "ROLE_SANCTIONS_CALLBACK");
        user("bureau-b", "ROLE_BUREAU_CALLBACK", "TENANT_bank-b");
    }

    private void user(String name, String... authorities) {
        if (!users.userExists(name)) {
            users.createUser(User.withUsername(name).password("{noop}" + PASSWORD).authorities(authorities).build());
        }
    }

    @Test
    void PRE_009_the_bureau_callback_completes_the_step_once_and_the_loan_moves_on() throws Exception {
        // Given a loan whose bureau call was handed off
        AdapterCall call = handedOff("LOAN-IT-0001");
        long historyBefore = history(call.instanceId());

        // When the bureau's client reports the score, then repeats the callback
        HttpResponse<String> first = complete("bureau", call, call.completionToken(), completed(712));
        long historyAfterFirst = history(call.instanceId());
        HttpResponse<String> repeat = complete("bureau", call, call.completionToken(), completed(712));

        // Then the first is accepted and the loan waits for underwriting; the repeat is a duplicate and writes nothing
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        JsonNode accepted = JSON.readTree(first.body());
        assertThat(accepted.get("outcome").stringValue()).isEqualTo("ACCEPTED");
        assertThat(accepted.get("jobId").stringValue()).isEqualTo(call.jobId());
        assertThat(accepted.get("attemptId").stringValue()).isEqualTo(call.attemptId());
        assertThat(accepted.has("completionToken")).isFalse();
        assertThat(historyAfterFirst).isGreaterThan(historyBefore);
        JsonNode instance = instance(call.instanceId());
        assertThat(instance.at("/variables/bureau/score").asInt()).isEqualTo(712);
        assertThat(instance.at("/tasks/0/nodeId").stringValue()).isEqualTo("underwrite");
        assertThat(repeat.statusCode()).as(repeat.body()).isEqualTo(200);
        assertThat(JSON.readTree(repeat.body()).get("outcome").stringValue()).isEqualTo("DUPLICATE");
        assertThat(history(call.instanceId())).isEqualTo(historyAfterFirst);
    }

    @Test
    void TR_TEN_01_another_tenants_bureau_client_gets_not_found_and_nothing_is_written() throws Exception {
        AdapterCall call = handedOff("LOAN-IT-0002");
        long historyBefore = history(call.instanceId());

        HttpResponse<String> response = complete("bureau-b", call, call.completionToken(), completed(800));

        assertThat(problem(response)).isEqualTo("404 ASYNC_STEP_NOT_FOUND");
        assertThat(history(call.instanceId())).isEqualTo(historyBefore);
        assertThat(jobStatus(call)).isEqualTo("CLAIMED");
    }

    @Test
    void TR_SEC_04_a_caller_without_the_permission_is_forbidden() throws Exception {
        AdapterCall call = handedOff("LOAN-IT-0003");
        long historyBefore = history(call.instanceId());

        HttpResponse<String> response = complete("an", call, call.completionToken(), completed(800));

        assertThat(problem(response)).isEqualTo("403 PERMISSION_DENIED");
        assertThat(history(call.instanceId())).isEqualTo(historyBefore);
        assertThat(jobStatus(call)).isEqualTo("CLAIMED");
    }

    @Test
    void TR_SEC_04_a_client_scoped_to_another_adapter_is_forbidden() throws Exception {
        AdapterCall call = handedOff("LOAN-IT-0004");
        long historyBefore = history(call.instanceId());

        HttpResponse<String> response = complete("sanctions", call, call.completionToken(), completed(800));

        assertThat(problem(response)).isEqualTo("403 PERMISSION_DENIED");
        assertThat(history(call.instanceId())).isEqualTo(historyBefore);
        assertThat(jobStatus(call)).isEqualTo("CLAIMED");
    }

    @Test
    void TR_ASY_02_a_wrong_or_absent_completion_token_is_refused_and_writes_nothing() throws Exception {
        AdapterCall call = handedOff("LOAN-IT-0005");
        long historyBefore = history(call.instanceId());

        HttpResponse<String> wrong = complete("bureau", call, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                completed(850));
        HttpResponse<String> absent = complete("bureau", call, null, completed(850));

        assertThat(problem(wrong)).isEqualTo("403 PERMISSION_DENIED");
        assertThat(problem(absent)).isEqualTo("400 INPUT_INVALID");
        assertThat(history(call.instanceId())).isEqualTo(historyBefore);
        assertThat(jobStatus(call)).isEqualTo("CLAIMED");
    }

    @Test
    void a_failure_without_an_error_code_is_a_bad_request() throws Exception {
        AdapterCall call = handedOff("LOAN-IT-0006");
        long historyBefore = history(call.instanceId());

        HttpResponse<String> response = complete("bureau", call, call.completionToken(),
                "\"status\":\"FAILED\",\"retryable\":false");

        assertThat(problem(response)).isEqualTo("400 INPUT_INVALID");
        assertThat(history(call.instanceId())).isEqualTo(historyBefore);
        assertThat(jobStatus(call)).isEqualTo("CLAIMED");
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    /** Applies for a loan as {@code tuan} and runs the bureau step once; the adapter hands it off. */
    private AdapterCall handedOff(String applicationId) throws Exception {
        HttpResponse<String> started = send("tuan", "POST", "/api/bpm/processes/v1/instances",
                "{\"definitionKey\":\"loan-bureau-check\",\"businessKey\":\"" + applicationId + "\",\"variables\":"
                        + "{\"nationalId\":\"079088001234\",\"amount\":250000000}}",
                Map.of("Content-Type", "application/json", "Idempotency-Key", "start-" + applicationId));
        assertThat(started.statusCode()).as(started.body()).isEqualTo(201);
        String instanceId = JSON.readTree(started.body()).get("id").stringValue();
        assertThat(executor.pollOnce()).isEqualTo(1);
        assertThat(executor.awaitIdle(Duration.ofSeconds(30))).isTrue();
        return Adapters.CALLS.stream().filter(c -> c.instanceId().equals(instanceId)).findFirst().orElseThrow();
    }

    private static String completed(int score) {
        return "\"status\":\"COMPLETED\",\"output\":{\"score\":" + score + ",\"band\":\"A\"}";
    }

    private HttpResponse<String> complete(String user, AdapterCall call, String token, String result) throws Exception {
        String body = "{\"attemptId\":\"" + call.attemptId() + "\","
                + (token == null ? "" : "\"completionToken\":\"" + token + "\",") + result + "}";
        return send(user, "POST", STEPS + call.jobId() + ":complete", body, Map.of("Content-Type", "application/json"));
    }

    private JsonNode instance(String id) throws Exception {
        HttpResponse<String> read = send("tuan", "GET", "/api/bpm/processes/v1/instances/" + id, null, Map.of());
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        return JSON.readTree(read.body());
    }

    private long history(String instanceId) {
        return jdbc.sql("SELECT count(*) FROM pre_history WHERE instance_id = ?").param(instanceId).query(Long.class)
                .single();
    }

    private String jobStatus(AdapterCall call) {
        return jdbc.sql("SELECT status FROM pre_timer WHERE id = ?").param(call.jobId()).query(String.class).single();
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
