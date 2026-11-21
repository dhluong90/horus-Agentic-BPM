package io.github.dhluong90.bpm.examples.models;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.spi.AdapterResult;
import io.github.dhluong90.bpm.process.spi.AgentInvocation;
import io.github.dhluong90.bpm.process.test.AgentInvocations;
import io.github.dhluong90.bpm.process.test.TestClock;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.Test;

/**
 * Both example adapters against the local stub API, without the engines (#100, PRE-098, PRE-099): the request each
 * sends, the labels of a completed answer, and the code of every failure. The key is a random value made for the
 * test run; nothing reaches a real provider.
 */
class ModelAdapterTest {

    static final String MODEL = "kyc-model-2026-11";
    static final StubModelServer STUB = new StubModelServer();
    /** A random value the stub receives as the key; not a credential of any provider. */
    static final String KEY = "stub-" + UUID.randomUUID();
    static final Map<String, Object> SCHEMA = Map.of("type", "object", "required", List.of("documentType",
            "confidence"), "properties", Map.of("documentType", Map.of("type", "string"), "confidence",
            Map.of("type", "number")));

    private final TestClock clock = TestClock.at("2026-11-20T04:00:00Z");
    private final List<LogRecord> records = new CopyOnWriteArrayList<>();
    private final Logger logger = Logger.getLogger(ModelApiAdapter.class.getName());
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    @BeforeEach
    void reset() {
        STUB.reset();
        logger.addHandler(capture);
        logger.setLevel(Level.ALL);
    }

    @AfterEach
    void release() {
        logger.removeHandler(capture);
    }

    @AfterAll
    static void stop() {
        STUB.close();
    }

    static ModelAdapterSettings.Adapter settings(ModelApi api, URI base, Duration timeout) {
        return new ModelAdapterSettings.Adapter(api, base, MODEL, "prompts/kyc-review@p7", KEY, 512, timeout, true,
                "2023-06-01");
    }

    private ModelApiAdapter adapter(ModelApi api) {
        return adapter(api, Duration.ofSeconds(10));
    }

    private ModelApiAdapter adapter(ModelApi api, Duration timeout) {
        URI base = api == ModelApi.OPENAI_COMPATIBLE ? STUB.openAiBase() : STUB.anthropicBase();
        return ModelAdaptersApplication.create("doc-reviewer", settings(api, base, timeout),
                PromptTemplate.load("prompts/kyc-review@p7", getClass().getClassLoader()), HttpClient.newHttpClient(),
                clock);
    }

    private static String path(ModelApi api) {
        return api == ModelApi.OPENAI_COMPATIBLE ? StubModelServer.OPENAI : StubModelServer.ANTHROPIC;
    }

    private static String dir(ModelApi api) {
        return api == ModelApi.OPENAI_COMPATIBLE ? "openai/" : "anthropic/";
    }

    private static AgentInvocation attempt(int attempt) {
        return AgentInvocations.builder().tenantId("bank-a").instanceId("01JQ0INSTANCE00000000000042")
                .nodeId("assess-documents").businessKey("KYC-2026-0042").jobId("01JQ0JOB000000000000000042")
                .attemptId("01JQ0ATTEMPT00000000000004" + attempt).attempt(attempt)
                .input("applicantName", "Nguyen Thi Lan").input("documentText", "PASSPORT C1234567 NGUYEN THI LAN")
                .outputSchema(SCHEMA).build();
    }

    private List<String> logLines() {
        return records.stream().map(r -> java.text.MessageFormat.format(r.getMessage(), r.getParameters())).toList();
    }

    // ---- PRE-098 ----------------------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(ModelApi.class)
    void PRE_098_a_reference_adapter_sends_the_rendered_prompt_keyed_by_the_attempt_id_and_returns_a_labelled_answer(
            ModelApi api) {
        AdapterResult result = adapter(api).execute(attempt(1));

        // The answer is the model's JSON, labelled with the model, the prompt version and the token usage
        assertThat(result).isInstanceOfSatisfying(AdapterResult.Completed.class, c -> {
            assertThat(c.output()).containsEntry("documentType", "PASSPORT").containsEntry("nameMatches", true)
                    .containsEntry("expiryDate", "2031-05-01").containsEntry("tamperingSuspected", false)
                    .containsEntry("confidence", new BigDecimal("0.97"));
            assertThat(c.labels()).containsEntry("promptVersion", "p7").containsKeys("usage.inputTokens",
                    "usage.outputTokens");
            assertThat(c.labels().get("model")).isIn(MODEL, "claude-opus-5-5");
        });
        // The request carries the rendered prompt with only the step's inputs, the schema, and the attempt id
        StubModelServer.Request sent = STUB.requests().getFirst();
        assertThat(sent.path()).isEqualTo(path(api));
        String prompt = sent.body().path("messages").path(0).path("content").asString();
        assertThat(prompt).contains("You review identity documents").contains("\"applicantName\":\"Nguyen Thi Lan\"")
                .contains("PASSPORT C1234567").doesNotContain("{{");
        assertThat(sent.body().path("model").asString()).isEqualTo(MODEL);
        if (api == ModelApi.OPENAI_COMPATIBLE) {
            assertThat(sent.body().path("user").asString()).isEqualTo("01JQ0ATTEMPT000000000000041");
            assertThat(sent.body().path("response_format").path("type").asString()).isEqualTo("json_schema");
            assertThat(sent.body().path("response_format").path("json_schema").path("schema").path("required")
                    .toString()).contains("documentType");
            assertThat(sent.headers()).containsEntry("authorization", "Bearer " + KEY);
        } else {
            assertThat(sent.body().path("metadata").path("user_id").asString()).isEqualTo("01JQ0ATTEMPT000000000000041");
            assertThat(sent.body().path("output_config").path("format").path("type").asString()).isEqualTo("json_schema");
            assertThat(sent.headers()).containsEntry("x-api-key", KEY).containsEntry("anthropic-version", "2023-06-01");
        }
        // No tenant id, instance id or job id goes to the vendor
        assertThat(sent.raw()).doesNotContain("bank-a").doesNotContain("01JQ0INSTANCE").doesNotContain("01JQ0JOB");
        assertThat(sent.headers().values()).noneMatch(v -> v.contains("bank-a"));
    }

    @ParameterizedTest
    @EnumSource(ModelApi.class)
    void PRE_098_a_429_is_model_rate_limited_and_retryable_and_the_next_attempt_sends_a_new_attempt_id(ModelApi api) {
        STUB.then(path(api), StubModelServer.recorded(dir(api) + "rate-limited.json", 429, Map.of("Retry-After", "2")));
        ModelApiAdapter adapter = adapter(api);

        AdapterResult first = adapter.execute(attempt(1));
        AdapterResult second = adapter.execute(attempt(2));

        assertThat(first).isEqualTo(new AdapterResult.Failed("MODEL_RATE_LIMITED", "http=429 retryAfter=2", true));
        assertThat(second).isInstanceOf(AdapterResult.Completed.class);
        List<String> keys = STUB.requests().stream().map(r -> api == ModelApi.OPENAI_COMPATIBLE
                ? r.body().path("user").asString() : r.body().path("metadata").path("user_id").asString()).toList();
        assertThat(keys).containsExactly("01JQ0ATTEMPT000000000000041", "01JQ0ATTEMPT000000000000042");
    }

    @ParameterizedTest
    @EnumSource(ModelApi.class)
    void PRE_098_logs_show_the_adapter_attempt_status_and_latency_and_never_the_prompt_inputs_or_key(ModelApi api) {
        ModelApiAdapter adapter = adapter(api);
        STUB.then(path(api), StubModelServer.recorded(dir(api) + "rate-limited.json", 429));

        adapter.execute(attempt(1));
        adapter.execute(attempt(2));

        assertThat(logLines()).hasSize(2).allSatisfy(line -> {
            assertThat(line).startsWith("model.call adapter=doc-reviewer api=" + api).contains("latencyMs=");
            assertThat(line).doesNotContain(KEY).doesNotContain("Nguyen Thi Lan").doesNotContain("C1234567")
                    .doesNotContain("You review").doesNotContain("PASSPORT");
        });
        assertThat(logLines().get(0)).contains("attempt=01JQ0ATTEMPT000000000000041").contains("status=429")
                .contains("result=MODEL_RATE_LIMITED");
        assertThat(logLines().get(1)).contains("status=200").contains("result=OK");
    }

    @Test
    void PRE_098_the_key_is_masked_in_the_settings_and_required_for_anthropic_messages() {
        ModelAdapterSettings.Adapter settings = settings(ModelApi.ANTHROPIC_MESSAGES, STUB.anthropicBase(),
                Duration.ofSeconds(5));
        assertThat(settings.toString()).doesNotContain(KEY).contains("apiKey=***");
        assertThatThrownBy(() -> new ModelAdapterSettings.Adapter(ModelApi.ANTHROPIC_MESSAGES, STUB.anthropicBase(),
                MODEL, "prompts/kyc-review@p7", "", 512, Duration.ofSeconds(5), true, "2023-06-01"))
                .hasMessageContaining("api-key is required");
        // A self-hosted OpenAI-compatible server may need no key: then no Authorization header is sent
        ModelApiAdapter keyless = ModelAdaptersApplication.create("doc-reviewer", new ModelAdapterSettings.Adapter(
                ModelApi.OPENAI_COMPATIBLE, STUB.openAiBase(), MODEL, "prompts/kyc-review@p7", null, 512,
                Duration.ofSeconds(5), false, "2023-06-01"), PromptTemplate.load("prompts/kyc-review@p7",
                getClass().getClassLoader()), HttpClient.newHttpClient(), clock);
        keyless.execute(attempt(1));
        assertThat(STUB.requests().getFirst().headers()).doesNotContainKey("authorization");
        assertThat(STUB.requests().getFirst().body().path("response_format").path("type").asString())
                .isEqualTo("json_object");
    }

    @Test
    void PRE_098_a_prompt_template_names_step_inputs_only() {
        assertThatThrownBy(() -> PromptTemplate.load("prompts/kyc-review@p9", getClass().getClassLoader()))
                .hasMessageContaining("not found");
        assertThatThrownBy(() -> PromptTemplate.load("../secrets@p1", getClass().getClassLoader()))
                .hasMessageContaining("<path>@<version>");
        PromptTemplate template = PromptTemplate.load("prompts/kyc-review@p7", getClass().getClassLoader());
        assertThat(template.version()).isEqualTo("p7");
        assertThat(template.render(Map.of("applicantName", "Lan"), Map.of())).contains("{\"applicantName\":\"Lan\"}");
    }

    // ---- PRE-099 ----------------------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(ModelApi.class)
    void PRE_099_model_errors_map_to_named_codes(ModelApi api) {
        ModelApiAdapter adapter = adapter(api);
        String serverError = api == ModelApi.OPENAI_COMPATIBLE ? "server-error.json" : "overloaded.json";
        int serverStatus = api == ModelApi.OPENAI_COMPATIBLE ? 503 : 529;
        STUB.then(path(api),
                StubModelServer.recorded(dir(api) + "bad-request.json", 400),
                StubModelServer.recorded(dir(api) + "unauthorized.json", 401),
                StubModelServer.recorded(dir(api) + serverError, serverStatus),
                StubModelServer.recorded(dir(api) + "refusal.json", 200));

        // (a) 400, (b) 401, (c) 5xx, (d) a refusal
        assertThat(adapter.execute(attempt(1))).isEqualTo(new AdapterResult.Failed("MODEL_REQUEST_INVALID", "http=400",
                false));
        assertThat(adapter.execute(attempt(2))).isEqualTo(new AdapterResult.Failed("MODEL_AUTH_FAILED", "http=401",
                false));
        assertThat(adapter.execute(attempt(3))).isEqualTo(new AdapterResult.Failed("MODEL_UNAVAILABLE",
                "http=" + serverStatus, true));
        assertThat(adapter.execute(attempt(4))).isInstanceOfSatisfying(AdapterResult.Failed.class, f -> {
            assertThat(f.errorCode()).isEqualTo("MODEL_REFUSED");
            assertThat(f.retryable()).isFalse();
        });
    }

    @Test
    void PRE_099_a_content_filter_stop_is_model_refused_and_not_retryable() {
        STUB.then(StubModelServer.OPENAI, StubModelServer.recorded("openai/content-filter.json", 200));

        assertThat(adapter(ModelApi.OPENAI_COMPATIBLE).execute(attempt(1))).isEqualTo(new AdapterResult.Failed(
                "MODEL_REFUSED", "stop=content_filter", false));
    }

    @ParameterizedTest
    @EnumSource(ModelApi.class)
    void PRE_099_no_answer_within_the_request_timeout_is_adapter_timeout_and_retryable(ModelApi api) {
        CountDownLatch never = new CountDownLatch(1);
        STUB.hold(never);
        try {
            assertThat(adapter(api, Duration.ofMillis(300)).execute(attempt(1))).isEqualTo(new AdapterResult.Failed(
                    "ADAPTER_TIMEOUT", "the model request timed out", true));
        } finally {
            never.countDown();
        }
    }

    @ParameterizedTest
    @EnumSource(ModelApi.class)
    void PRE_099_prose_instead_of_json_is_returned_empty_so_the_result_check_refuses_it(ModelApi api) {
        STUB.then(path(api), StubModelServer.recorded(dir(api) + "prose.json", 200));

        assertThat(adapter(api).execute(attempt(1))).isInstanceOfSatisfying(AdapterResult.Completed.class, c -> {
            assertThat(c.output()).isEmpty();
            assertThat(c.labels()).containsEntry("promptVersion", "p7");
        });
    }

    @Test
    void PRE_099_a_fenced_json_answer_is_read_and_anything_but_an_object_is_empty() {
        assertThat(ModelApiAdapter.jsonObject("```json\n{\"confidence\": 0.9}\n```"))
                .isEqualTo(Map.of("confidence", new BigDecimal("0.9")));
        assertThat(ModelApiAdapter.jsonObject("[1, 2]")).isEmpty();
        assertThat(ModelApiAdapter.jsonObject("")).isEmpty();
        assertThat(ModelApiAdapter.jsonObject(null)).isEmpty();
    }
}
