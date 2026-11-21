package io.github.dhluong90.bpm.examples.models;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.api.DefinitionService;
import io.github.dhluong90.bpm.process.api.ImportRequest;
import io.github.dhluong90.bpm.process.api.InstanceNotFoundException;
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
import java.util.UUID;
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
 * Both reference adapters running in the example host, end to end on PostgreSQL with both engines (#100, PRE-098,
 * PRE-099): step {@code assess-documents} of {@code kyc-document-review} names {@code doc-reviewer} (OpenAI-compatible)
 * and of {@code kyc-document-review-anthropic} names {@code doc-reviewer-anthropic} (Anthropic Messages). The engine's
 * executor calls the local stub API through the adapter, and checks the answer against the output schema and
 * {@code kyc-document-guardrails} in the result transaction.
 *
 * <p>The job poller is off; each test drives the executor. The clock of both engines is a {@link TestClock}, moved for
 * the retry backoff. No test sleeps, reaches a real provider or uses a real key.
 */
@SpringBootTest(properties = {
    "horus.bpm.processes.jobs.enabled=false",
    "horus.bpm.processes.definitions.auto-approve=true",
    "example.model-adapters.adapters.doc-reviewer.model=" + ModelAdapterTest.MODEL,
    "example.model-adapters.adapters.doc-reviewer-anthropic.model=claude-opus-5-5"
})
@ActiveProfiles("dev")
@Testcontainers
class ModelAdaptersIT {

    static final String OPENAI_PROCESS = "kyc-document-review";
    static final String ANTHROPIC_PROCESS = "kyc-document-review-anthropic";
    /** A random value the stub receives as the key; not a credential of any provider. */
    static final String KEY = "stub-" + UUID.randomUUID();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(TestImages.postgres());

    static final StubModelServer STUB = new StubModelServer();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("example.model-adapters.adapters.doc-reviewer.base-url", () -> STUB.openAiBase().toString());
        registry.add("example.model-adapters.adapters.doc-reviewer.api-key", () -> KEY);
        registry.add("example.model-adapters.adapters.doc-reviewer-anthropic.base-url",
                () -> STUB.anthropicBase().toString());
        registry.add("example.model-adapters.adapters.doc-reviewer-anthropic.api-key", () -> KEY);
    }

    @AfterAll
    static void stopStub() {
        STUB.close();
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
            return TestClock.at("2026-11-20T04:00:00Z");
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
    void guardrailsAndProcesses() {
        STUB.reset();
        for (String tenant : List.of("bank-a", "bank-b")) {
            if (READY.add(tenant)) {
                identity.tenant = tenant;
                identity.caller = "lan";
                ruleSets.importRuleSet(io.github.dhluong90.bpm.rules.api.ImportRequest.yaml(
                        resource("rules/kyc-document-guardrails.yaml")));
                long requested = ruleSets.requestPublish("kyc-document-guardrails", 1, WriteOptions.none())
                        .lockVersion();
                identity.caller = "minh";
                ruleSets.approve("kyc-document-guardrails", 1, new WriteOptions(null, requested, null));
                identity.caller = "pia";
                for (String process : List.of(OPENAI_PROCESS, ANTHROPIC_PROCESS)) {
                    definitions.importDefinition(new ImportRequest(SourceFormat.BPMN_XML,
                            resource("processes/" + process + ".bpmn"), null));
                }
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

    private String apply(String process, String applicationId) {
        return engine.start(new StartRequest(process, applicationId, Map.of("applicantName", "Nguyen Thi Lan",
                "documentText", "PASSPORT C1234567 NGUYEN THI LAN 01 MAY 2031"), null)).id();
    }

    /** One attempt on the executor, until its result transaction ended. */
    private void attempt() throws InterruptedException {
        assertThat(executor.pollOnce()).isEqualTo(1);
        assertThat(executor.awaitIdle(Duration.ofSeconds(30))).isTrue();
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

    @Test
    void PRE_098_the_openai_compatible_adapter_answers_after_a_429_with_a_new_attempt_id_and_a_labelled_answer()
            throws Exception {
        // Given the API fails the first call with 429 and Retry-After 2, then answers PASSPORT with confidence 0.97
        STUB.then(StubModelServer.OPENAI, StubModelServer.recorded("openai/rate-limited.json", 429,
                Map.of("Retry-After", "2")));
        String application = apply(OPENAI_PROCESS, "KYC-2026-0101");

        // When the first attempt runs and, after the two-second backoff, the second
        attempt();
        clock.advance(Duration.ofSeconds(2));
        attempt();

        // Then attempt 1 failed with MODEL_RATE_LIMITED, retryable, and attempt 2 was accepted with model and p7
        List<String> history = history(application);
        assertThat(history).anySatisfy(h -> assertThat(h).startsWith("ASYNC_ATTEMPT_FAILED")
                .contains("\"errorCode\": \"MODEL_RATE_LIMITED\"").contains("\"final\": false"));
        assertThat(history).anySatisfy(h -> assertThat(h).startsWith("ASYNC_RESULT_ACCEPTED")
                .contains("\"model\": \"" + ModelAdapterTest.MODEL + "\"").contains("\"promptVersion\": \"p7\"")
                .contains("\"ruleIds\": [\"R9\"]"));
        // And each attempt sent its own attempt id, and the two calls carried the same rendered prompt
        List<StubModelServer.Request> calls = STUB.requests();
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).body().path("user").asString()).isNotEqualTo(calls.get(1).body().path("user")
                .asString());
        assertThat(calls.get(0).body().path("messages")).isEqualTo(calls.get(1).body().path("messages"));
        assertThat(calls).allSatisfy(c -> assertThat(c.raw()).doesNotContain("bank-a").doesNotContain(application));
        // And the application moved on with the assessment
        assertThat(engine.instance(application).variables().get("assessment")).isInstanceOfSatisfying(Map.class,
                a -> assertThat(a).containsEntry("documentType", "PASSPORT").containsEntry("nameMatches", true));
        assertThat(tokenOn(application)).isEqualTo("open-account:WAITING_USER");
        assertThat(String.join("\n", history)).doesNotContain(KEY).doesNotContain("You review identity documents");
    }

    @Test
    void PRE_098_the_anthropic_messages_adapter_runs_the_same_step_in_the_same_host() throws Exception {
        String application = apply(ANTHROPIC_PROCESS, "KYC-2026-0102");

        attempt();

        assertThat(history(application)).anySatisfy(h -> assertThat(h).startsWith("ASYNC_RESULT_ACCEPTED")
                .contains("\"model\": \"claude-opus-5-5\"").contains("\"promptVersion\": \"p7\"")
                .contains("\"usage.inputTokens\": \"431\""));
        assertThat(STUB.requests()).singleElement().satisfies(c -> {
            assertThat(c.path()).isEqualTo(StubModelServer.ANTHROPIC);
            assertThat(c.body().path("metadata").path("user_id").asString()).hasSize(26);
            assertThat(c.body().path("output_config").path("format").path("schema").path("required").toString())
                    .contains("tamperingSuspected");
        });
        assertThat(tokenOn(application)).isEqualTo("open-account:WAITING_USER");
    }

    @Test
    void PRE_099_prose_instead_of_json_is_refused_by_the_result_check_and_writes_no_result() throws Exception {
        STUB.then(StubModelServer.OPENAI, StubModelServer.recorded("openai/prose.json", 200),
                StubModelServer.recorded("openai/prose.json", 200));
        String application = apply(OPENAI_PROCESS, "KYC-2026-0103");

        // When the attempt and its one schema retry are answered with prose
        attempt();
        clock.advance(Duration.ofSeconds(2));
        attempt();

        assertThat(history(application)).anySatisfy(h -> assertThat(h).startsWith("ASYNC_RESULT_REFUSED")
                .contains("\"code\": \"AGENT_SCHEMA_INVALID\""));
        assertThat(engine.instance(application).variables()).doesNotContainKey("assessment");
        assertThat(tokenOn(application)).isEqualTo("assess-documents:WAITING_USER");
        assertThat(String.join("\n", history(application))).doesNotContain("I would approve");
    }

    @Test
    void PRE_099_a_low_confidence_answer_goes_to_the_kyc_reviewer_and_writes_no_result() throws Exception {
        STUB.then(StubModelServer.ANTHROPIC, StubModelServer.recorded("anthropic/low-confidence.json", 200));
        String application = apply(ANTHROPIC_PROCESS, "KYC-2026-0104");

        attempt();

        assertThat(history(application)).anySatisfy(h -> assertThat(h).startsWith("ASYNC_RESULT_REFUSED")
                .contains("\"code\": \"GUARDRAIL_VIOLATION\"").contains("LOW_CONFIDENCE")
                .contains("\"ruleIds\": [\"R5\"]"));
        assertThat(engine.instance(application).variables()).doesNotContainKey("assessment");
        assertThat(tokenOn(application)).isEqualTo("assess-documents:WAITING_USER");
    }

    @Test
    void PRE_099_a_refusal_fails_the_attempt_for_good_with_model_refused_and_the_step_does_not_advance()
            throws Exception {
        STUB.then(StubModelServer.ANTHROPIC, StubModelServer.recorded("anthropic/refusal.json", 200));
        String application = apply(ANTHROPIC_PROCESS, "KYC-2026-0105");

        attempt();

        assertThat(history(application)).anySatisfy(h -> assertThat(h).startsWith("ASYNC_ATTEMPT_FAILED")
                .contains("\"errorCode\": \"MODEL_REFUSED\"").contains("\"final\": true"));
        assertThat(engine.instance(application).variables()).doesNotContainKey("assessment");
        assertThat(tokenOn(application)).isEqualTo("assess-documents:WAITING_ASYNC");
        clock.advance(Duration.ofMinutes(5));
        assertThat(executor.pollOnce()).isZero();
        assertThat(STUB.requests()).hasSize(1);
    }

    @Test
    void PRE_099_another_tenant_cannot_read_an_application_or_its_assessment() throws Exception {
        String application = apply(OPENAI_PROCESS, "KYC-2026-0106");
        attempt();
        int before = history(application).size();

        identity.tenant = "bank-b";
        assertThatThrownBy(() -> engine.instance(application)).isInstanceOf(InstanceNotFoundException.class);

        identity.tenant = "bank-a";
        assertThat(history(application)).hasSize(before);
    }
}
