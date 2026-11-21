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
 * An agent adapter over an OpenAI-compatible Chat Completions endpoint, {@code POST <base-url>/chat/completions}: the
 * shape many hosted vendors and the self-hosted servers banks run on premises (vLLM, Ollama and similar) accept.
 *
 * <p>The request has one user message with the rendered prompt, {@code max_tokens}, and the attempt id in
 * {@code user}. It asks for structured output with {@code response_format}: a {@code json_schema} with the step's
 * output schema, or a {@code json_object} when the step has none or {@code structured-output} is off. The key, when
 * set, goes in {@code Authorization: Bearer}.
 *
 * <p>The answer is {@code choices[0].message.content}. A {@code refusal}, or {@code finish_reason: content_filter},
 * is {@code MODEL_REFUSED}. Labels: {@code model} from the response, {@code promptVersion}, and
 * {@code usage.prompt_tokens} and {@code usage.completion_tokens} as {@code usage.inputTokens} and
 * {@code usage.outputTokens}.
 */
public final class OpenAiCompatibleAdapter extends ModelApiAdapter {

    /**
     * @param name the adapter name
     * @param settings its configuration
     * @param prompt its prompt template
     * @param http the HTTP client
     * @param clock the clock latencies are measured with
     */
    public OpenAiCompatibleAdapter(String name, ModelAdapterSettings.Adapter settings, PromptTemplate prompt,
            HttpClient http, Clock clock) {
        super(name, settings, prompt, http, clock);
    }

    @Override
    protected HttpRequest request(String renderedPrompt, AgentInvocation invocation) {
        ModelAdapterSettings.Adapter s = settings();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", s.model());
        body.put("messages", List.of(Map.of("role", "user", "content", renderedPrompt)));
        body.put("max_tokens", s.maxTokens());
        body.put("user", invocation.modelCallKey());
        body.put("response_format", s.structuredOutput() && !invocation.outputSchema().isEmpty()
                ? Map.of("type", "json_schema", "json_schema", Map.of("name", "step_answer", "schema",
                        invocation.outputSchema()))
                : Map.of("type", "json_object"));
        String base = s.baseUrl().toString().replaceAll("/+$", "");
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                .timeout(s.requestTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(body(body));
        if (!s.apiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + s.apiKey());
        }
        return builder.build();
    }

    @Override
    protected Answer answer(JsonNode body) {
        JsonNode choice = body.path("choices").path(0);
        JsonNode message = choice.path("message");
        String finish = choice.path("finish_reason").asString("");
        JsonNode refusal = message.get("refusal");
        if ("content_filter".equals(finish) || (refusal != null && !refusal.isNull() && !refusal.asString("").isBlank())) {
            return new Answer.Refused("content_filter".equals(finish) ? finish : "refusal");
        }
        JsonNode usage = body.path("usage");
        return new Answer.Text(message.path("content").asString(null), body.path("model").asString(null),
                usage.has("prompt_tokens") ? usage.get("prompt_tokens").asLong() : null,
                usage.has("completion_tokens") ? usage.get("completion_tokens").asLong() : null);
    }
}
