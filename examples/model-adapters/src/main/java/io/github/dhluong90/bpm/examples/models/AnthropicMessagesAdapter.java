package io.github.dhluong90.bpm.examples.models;

import io.github.dhluong90.bpm.process.spi.AgentInvocation;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * An agent adapter over the Anthropic Messages API, {@code POST <base-url>/v1/messages}: a second request shape next
 * to the OpenAI-compatible one.
 *
 * <p>The request has one user message with the rendered prompt, {@code max_tokens}, and the attempt id in
 * {@code metadata.user_id}. With {@code structured-output} on and a step output schema it asks for structured output
 * with {@code output_config.format} of type {@code json_schema}. The key goes in {@code x-api-key}, with the
 * {@code anthropic-version} header.
 *
 * <p>The answer is the text of the response's {@code text} content blocks. {@code stop_reason: refusal} is
 * {@code MODEL_REFUSED}. Labels: {@code model} from the response, {@code promptVersion}, and
 * {@code usage.input_tokens} and {@code usage.output_tokens}.
 */
public final class AnthropicMessagesAdapter extends ModelApiAdapter {

    /**
     * @param name the adapter name
     * @param settings its configuration
     * @param prompt its prompt template
     * @param http the HTTP client
     * @param clock the clock latencies are measured with
     */
    public AnthropicMessagesAdapter(String name, ModelAdapterSettings.Adapter settings, PromptTemplate prompt,
            HttpClient http, Clock clock) {
        super(name, settings, prompt, http, clock);
    }

    @Override
    protected HttpRequest request(String renderedPrompt, AgentInvocation invocation) {
        ModelAdapterSettings.Adapter s = settings();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", s.model());
        body.put("max_tokens", s.maxTokens());
        body.put("messages", List.of(Map.of("role", "user", "content", renderedPrompt)));
        body.put("metadata", Map.of("user_id", invocation.modelCallKey()));
        if (s.structuredOutput() && !invocation.outputSchema().isEmpty()) {
            body.put("output_config", Map.of("format", Map.of("type", "json_schema", "schema",
                    invocation.outputSchema())));
        }
        String base = s.baseUrl().toString().replaceAll("/+$", "");
        return HttpRequest.newBuilder(URI.create(base + "/v1/messages"))
                .timeout(s.requestTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("x-api-key", s.apiKey())
                .header("anthropic-version", s.anthropicVersion())
                .POST(body(body))
                .build();
    }

    @Override
    protected Answer answer(JsonNode body) {
        String stop = body.path("stop_reason").asString("");
        if ("refusal".equals(stop)) {
            return new Answer.Refused(stop);
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode block : body.path("content")) {
            if ("text".equals(block.path("type").asString(""))) {
                text.append(block.path("text").asString(""));
            }
        }
        JsonNode usage = body.path("usage");
        return new Answer.Text(text.toString(), body.path("model").asString(null),
                usage.has("input_tokens") ? usage.get("input_tokens").asLong() : null,
                usage.has("output_tokens") ? usage.get("output_tokens").asLong() : null);
    }
}
