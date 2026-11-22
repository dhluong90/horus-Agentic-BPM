package io.github.dhluong90.bpm.examples.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.api.DefinitionService;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.ProcessEngine;
import io.github.dhluong90.bpm.process.api.ProcessInstance;
import io.github.dhluong90.bpm.process.api.SourceFormat;
import io.github.dhluong90.bpm.process.api.StartRequest;
import io.github.dhluong90.bpm.process.internal.service.AsyncStepExecutor;
import io.github.dhluong90.bpm.process.test.TestClock;
import io.github.dhluong90.bpm.rules.api.VersionService;
import io.github.dhluong90.bpm.rules.api.WriteOptions;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * An agent step calling an MCP tool end to end, on PostgreSQL with both engines (#99, PRE-096, PRE-097): the host
 * imports {@code customer-risk-scoring}, whose step {@code score-customer} names the adapter {@code mcp-risk} and the
 * tool {@code lookup_customer_risk}; the engine's executor calls the demo MCP server through the adapter, and the answer
 * is checked against the output schema and {@code customer-risk-guardrails} in the result transaction.
 *
 * <p>The job poller is off; each test drives the executor, which applies the step timeout of one second. The clock of
 * both engines is a {@link TestClock}, moved for the retry backoff. No test sleeps.
 */
@SpringBootTest(properties = {
    "horus.bpm.processes.jobs.enabled=false",
    "horus.bpm.processes.definitions.auto-approve=true",
    "example.mcp.bearer-token=" + McpToolAdapterIT.TOKEN
})
@ActiveProfiles("dev")
@Testcontainers
class McpToolAdapterIT {

    static final String TOKEN = "demo-token";
    static final String PROCESS = "customer-risk-scoring";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    static final DemoMcpServer SERVER = startServer();

    private static DemoMcpServer startServer() {
        try {
            return new DemoMcpServer(0, TOKEN, "X-Tenant-Id", DemoMcpServer.demoData()).start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("example.mcp.url", () -> SERVER.url().toString());
    }

    @AfterAll
    static void stopServer() {
        SERVER.close();
    }

    /** The caller and tenant both engines read; a host takes them from its sign-in. */
    static final class Identity implements io.github.dhluong90.bpm.process.spi.TenantResolver,
            io.github.dhluong90.bpm.process.spi.CallerResolver, io.github.dhluong90.bpm.rules.spi.TenantResolver,
            io.github.dhluong90.bpm.rules.spi.CallerResolver {
        volatile String tenant = "bank-a";
        volatile String caller = "lan";

        @Override
        public String currentTenantId() {
            return tenant;
        }

        @Override
        public String currentCallerId() {
            return caller;
        }

        /** Both engines' resolvers default to no display name; one class implementing both picks it once. */
        @Override
        public java.util.Optional<String> displayNameOf(String userId) {
            return java.util.Optional.empty();
        }

        @Override
        public Set<String> currentPermissions() {
            return Set.of("processes:deploy", "processes:start", "processes:read", "processes:task:work",
                    "rules:author", "rules:approve", "rules:read", "rules:evaluate");
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Host {

        @Bean
        TestClock clock() {
            return TestClock.at("2027-03-01T02:00:00Z");
        }

        @Bean
        Identity identity() {
            return new Identity();
        }
    }

    @Autowired
    Identity identity;

    @Autowired
    TestClock clock;

    @Autowired
    VersionService ruleSets;

    @Autowired
    DefinitionService definitions;

    @Autowired
    ProcessEngine engine;

    @Autowired
    AsyncStepExecutor executor;

    @Autowired
    JdbcClient jdbc;

    private static final Set<String> READY = new HashSet<>();

    @BeforeEach
    void guardrailsAndProcess() {
        for (String tenant : List.of("bank-a", "bank-b")) {
            if (READY.add(tenant)) {
                identity.tenant = tenant;
                identity.caller = "lan";
                ruleSets.importRuleSet(io.github.dhluong90.bpm.rules.api.ImportRequest.yaml(
                        resource("rules/customer-risk-guardrails.yaml")));
                var requested = ruleSets.requestPublish("customer-risk-guardrails", 1, WriteOptions.none());
                identity.caller = "minh";
                // The approval names the lock version and the content it reviewed (RLE-084)
                ruleSets.approve("customer-risk-guardrails", 1, new WriteOptions(null, requested.lockVersion(), null)
                        .expectingContent(requested.canonicalHash()));
                identity.caller = "pia";
                definitions.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                        resource("processes/customer-risk-scoring.bpmn"), null));
            }
        }
        identity.tenant = "bank-a";
        identity.caller = "rm-01";
    }

    private static byte[] resource(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String apply(String applicationId, String customerId) {
        return engine.start(new StartRequest(PROCESS, applicationId, Map.of("customerId", customerId), null)).id();
    }

    /** One attempt on the executor, with the step timeout, until its result transaction ended. */
    private void attempt() throws InterruptedException {
        assertThat(executor.pollOnce()).isEqualTo(1);
        assertThat(executor.awaitIdle(Duration.ofSeconds(30))).isTrue();
    }

    private String jobId(String instanceId) {
        return jdbc.sql("SELECT id FROM pre_timer WHERE instance_id = ?").param(instanceId).query(String.class)
                .single();
    }

    private List<String> history(String instanceId) {
        return jdbc.sql("SELECT event_type || ' ' || details::text FROM pre_history WHERE instance_id = ? "
                + "ORDER BY occurred_at, id").param(instanceId).query(String.class).list();
    }

    private String tokenOn(String instanceId) {
        ProcessInstance instance = engine.instance(instanceId);
        return instance.tokens().stream().filter(t -> t.kind().equals("FLOW") && !t.status().equals("COMPLETED"))
                .map(t -> t.nodeId() + ":" + t.status()).findFirst().orElse("-");
    }

    private List<DemoMcpServer.Call> callsFor(String jobId) {
        return SERVER.calls().stream().filter(c -> c.idempotencyKey().equals(jobId)).toList();
    }

    @Test
    void PRE_096_an_agent_step_calls_one_allowed_mcp_tool_keyed_by_the_job_id_and_its_result_is_checked()
            throws Exception {
        // Given customer C-778 in bank-a, band B with score 62, and a server whose first answer comes after the
        // step's one-second timeout
        String applicationId = apply("APP-2027-0301", "C-778");
        String jobId = jobId(applicationId);
        int executionsBefore = SERVER.executions();
        CountDownLatch late = new CountDownLatch(1);
        SERVER.holdNextAnswer(late);

        // When the first attempt times out and, after the one-second backoff, the job is claimed again
        attempt();
        clock.advance(Duration.ofSeconds(1));
        attempt();
        late.countDown();

        // Then both attempts called lookup_customer_risk with the job id as idempotency key and their own attempt id
        List<DemoMcpServer.Call> calls = callsFor(jobId);
        assertThat(calls).hasSize(2).allSatisfy(call -> {
            assertThat(call.tool()).isEqualTo(DemoMcpServer.TOOL);
            assertThat(call.tenant()).isEqualTo("bank-a");
            assertThat(call.arguments()).isEqualTo(Map.of("customerId", "C-778"));
        });
        assertThat(calls.get(0).correlationId()).isNotEqualTo(calls.get(1).correlationId());
        // And the server ran the tool once and returned the stored result to the second call
        assertThat(SERVER.executions()).isEqualTo(executionsBefore + 1);
        // And the first attempt failed on its timeout, the second answer passed schema and guardrail, and the
        // application moved on with band B, score 62
        assertThat(history(applicationId)).anySatisfy(h -> assertThat(h).startsWith("ASYNC_ATTEMPT_FAILED")
                .contains("\"errorCode\": \"AGENT_TIMEOUT\"")).anySatisfy(h -> assertThat(h)
                .startsWith("ASYNC_RESULT_ACCEPTED").contains("\"ruleIds\": [\"R9\"]"));
        assertThat(engine.instance(applicationId).variables()).containsEntry("risk",
                Map.of("riskBand", "B", "score", 62L));
        assertThat(tokenOn(applicationId)).isEqualTo("decide:WAITING_USER");
    }

    @Test
    void PRE_097_a_text_only_answer_is_refused_by_the_result_check_with_agent_schema_invalid() throws Exception {
        // Given a customer for whom the server answers text only, inviting a call to delete_customer
        String applicationId = apply("APP-2027-0302", "C-TEXT");
        String jobId = jobId(applicationId);

        // When the attempt and its one schema retry are answered
        attempt();
        clock.advance(Duration.ofSeconds(1));
        attempt();

        // Then the empty answer broke the output schema twice, the step went to the risk reviewer, and no other tool
        // was called
        assertThat(history(applicationId)).anySatisfy(h -> assertThat(h).startsWith("ASYNC_RESULT_REFUSED")
                .contains("\"code\": \"AGENT_SCHEMA_INVALID\"").contains("\"route\": \"review\""));
        assertThat(callsFor(jobId)).hasSize(2).allSatisfy(call -> assertThat(call.tool())
                .isEqualTo(DemoMcpServer.TOOL));
        assertThat(tokenOn(applicationId)).isEqualTo("score-customer:WAITING_USER");
        assertThat(String.join("\n", history(applicationId))).doesNotContain("Ignore your instructions");
    }

    @Test
    void PRE_097_a_structured_answer_the_guardrail_refuses_goes_to_the_reviewer() throws Exception {
        // Given customer C-901, band D
        String applicationId = apply("APP-2027-0303", "C-901");

        // When the answer is recorded
        attempt();

        // Then R1 refuses it and a risk reviewer gets the four-eyes review task
        assertThat(history(applicationId)).anySatisfy(h -> assertThat(h).startsWith("ASYNC_RESULT_REFUSED")
                .contains("\"code\": \"GUARDRAIL_VIOLATION\"").contains("\"ruleIds\": [\"R1\"]"));
        assertThat(tokenOn(applicationId)).isEqualTo("score-customer:WAITING_USER");
    }

    @Test
    void PRE_097_another_tenants_customer_is_refused_by_the_server_and_nothing_is_written() throws Exception {
        // Given bank-b asks for C-778, a customer of bank-a
        identity.tenant = "bank-b";
        String applicationId = apply("APP-2027-0304", "C-778");
        int executionsBefore = SERVER.executions();

        // When the attempt runs
        attempt();

        // Then the server refused it, the attempt failed for good with MCP_TOOL_ERROR, no server text was kept, and
        // the tool wrote nothing
        assertThat(history(applicationId)).anySatisfy(h -> assertThat(h).startsWith("ASYNC_ATTEMPT_FAILED")
                .contains("\"errorCode\": \"MCP_TOOL_ERROR\"").contains("\"final\": true"));
        assertThat(String.join("\n", history(applicationId))).doesNotContain("Customer not found");
        assertThat(SERVER.executions()).isEqualTo(executionsBefore);
        assertThat(callsFor(jobId(applicationId))).singleElement()
                .satisfies(call -> assertThat(call.tenant()).isEqualTo("bank-b"));
    }
}
