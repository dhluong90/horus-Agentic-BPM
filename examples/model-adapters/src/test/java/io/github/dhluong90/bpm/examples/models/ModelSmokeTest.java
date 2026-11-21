package io.github.dhluong90.bpm.examples.models;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.dhluong90.bpm.process.spi.AdapterResult;
import io.github.dhluong90.bpm.process.test.AgentInvocations;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The manual smoke test behind {@code smoke.sh}: one call of one reference adapter against the real API, with the
 * endpoint, model and key the user supplies in the environment. It is skipped unless {@code MODEL_SMOKE_API} is set,
 * which CI never does. It prints the outcome's code and labels only, never the prompt, the answer or the key.
 */
@EnabledIfEnvironmentVariable(named = "MODEL_SMOKE_API", matches = "OPENAI_COMPATIBLE|ANTHROPIC_MESSAGES")
class ModelSmokeTest {

    @Test
    void one_real_call_returns_a_labelled_answer() {
        ModelApi api = ModelApi.valueOf(System.getenv("MODEL_SMOKE_API"));
        ModelAdapterSettings.Adapter settings = new ModelAdapterSettings.Adapter(api,
                URI.create(System.getenv("MODEL_SMOKE_BASE_URL")), System.getenv("MODEL_SMOKE_MODEL"),
                "prompts/kyc-review@p7", System.getenv("MODEL_SMOKE_API_KEY"), 512, Duration.ofSeconds(60), true,
                "2023-06-01");
        ModelApiAdapter adapter = ModelAdaptersApplication.create("smoke", settings,
                PromptTemplate.load(settings.prompt(), getClass().getClassLoader()), HttpClient.newHttpClient(),
                Clock.systemUTC());
        Map<String, Object> schema = Map.of("type", "object", "additionalProperties", false,
                "required", List.of("documentType", "nameMatches", "expiryDate", "tamperingSuspected", "confidence"),
                "properties", Map.of("documentType", Map.of("type", "string", "enum", List.of("PASSPORT", "ID_CARD",
                        "UNKNOWN")), "nameMatches", Map.of("type", "boolean"), "expiryDate", Map.of("type", "string"),
                        "tamperingSuspected", Map.of("type", "boolean"), "confidence", Map.of("type", "number")));

        AdapterResult result = adapter.execute(AgentInvocations.builder().tenantId("smoke")
                .jobId(UUID.randomUUID().toString()).attemptId(UUID.randomUUID().toString())
                .input("applicantName", "Nguyen Thi Lan")
                .input("documentText", "PASSPORT C1234567, NGUYEN THI LAN, born 12 MAR 1990, expires 01 MAY 2031")
                .outputSchema(schema).build());

        switch (result) {
            case AdapterResult.Completed c -> System.out.println("smoke: completed, fields " + c.output().keySet()
                    + ", labels " + c.labels());
            case AdapterResult.Failed f -> System.out.println("smoke: failed " + f.errorCode() + " (" + f.detail()
                    + "), retryable " + f.retryable());
            default -> System.out.println("smoke: " + result.getClass().getSimpleName());
        }
        assertThat(result).isInstanceOf(AdapterResult.Completed.class);
    }
}
