package io.github.dhluong90.bpm.examples.models;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Host configuration of the example model adapters, under {@code example.model-adapters}: one entry per adapter name
 * that steps use in {@code horus:agent}. The endpoint, the model, the prompt template and the API key come from here,
 * never from a process definition. An invalid value stops start-up naming the property.
 *
 * @param adapters the adapters by name, for example {@code doc-reviewer}
 */
@ConfigurationProperties("example.model-adapters")
public record ModelAdapterSettings(@DefaultValue Map<String, Adapter> adapters) {

    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,64}$");

    public ModelAdapterSettings {
        Map<String, Adapter> copy = new LinkedHashMap<>();
        (adapters == null ? Map.<String, Adapter>of() : adapters).forEach((name, adapter) -> {
            if (!NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("example.model-adapters.adapters." + name + ": an adapter name is "
                        + "1 to 64 letters, digits, '.', '_' or '-'");
            }
            copy.put(name, Objects.requireNonNull(adapter, "example.model-adapters.adapters." + name));
        });
        adapters = Map.copyOf(copy);
    }

    /**
     * One adapter.
     *
     * @param api the request shape
     * @param baseUrl the API's base address: for {@code OPENAI_COMPATIBLE} the part before {@code /chat/completions},
     *     for example {@code https://api.openai.com/v1} or {@code http://localhost:11434/v1}; for
     *     {@code ANTHROPIC_MESSAGES} the part before {@code /v1/messages}, for example {@code https://api.anthropic.com}
     * @param model the model id sent with every request, and the {@code model} label when the answer names none
     * @param prompt the versioned prompt template, {@code <path>@<version>}, read from the host's classpath as
     *     {@code <path>/<version>.txt}; for example {@code prompts/kyc-review@p7}
     * @param apiKey the API key, from the environment; masked in {@link #toString()} and never logged. Required for
     *     {@code ANTHROPIC_MESSAGES}; may be empty for a self-hosted OpenAI-compatible server that needs none
     * @param maxTokens the longest answer in tokens
     * @param requestTimeout the longest one HTTP request may take; the engine abandons the attempt at the step timeout
     * @param structuredOutput whether to ask the API for output in the step's output schema; turn it off for a server
     *     that refuses the schema, and the adapter asks for a JSON object instead
     * @param anthropicVersion the {@code anthropic-version} header of {@code ANTHROPIC_MESSAGES}
     */
    public record Adapter(
            ModelApi api,
            URI baseUrl,
            String model,
            String prompt,
            String apiKey,
            @DefaultValue("1024") int maxTokens,
            @DefaultValue("PT30S") Duration requestTimeout,
            @DefaultValue("true") boolean structuredOutput,
            @DefaultValue("2023-06-01") String anthropicVersion) {

        public Adapter {
            Objects.requireNonNull(api, "api is required: OPENAI_COMPATIBLE or ANTHROPIC_MESSAGES");
            Objects.requireNonNull(baseUrl, "base-url is required");
            if (!"http".equals(baseUrl.getScheme()) && !"https".equals(baseUrl.getScheme())) {
                throw new IllegalArgumentException("base-url must be an http or https address");
            }
            if (model == null || model.isBlank()) {
                throw new IllegalArgumentException("model is required");
            }
            if (prompt == null || !PromptTemplate.REF.matcher(prompt).matches()) {
                throw new IllegalArgumentException("prompt must be <path>@<version>, for example prompts/kyc-review@p7");
            }
            apiKey = apiKey == null ? "" : apiKey;
            if (api == ModelApi.ANTHROPIC_MESSAGES && apiKey.isBlank()) {
                throw new IllegalArgumentException("api-key is required for ANTHROPIC_MESSAGES; set it from the "
                        + "environment");
            }
            if (maxTokens < 1) {
                throw new IllegalArgumentException("max-tokens must be positive");
            }
            if (requestTimeout.isNegative() || requestTimeout.isZero()) {
                throw new IllegalArgumentException("request-timeout must be positive");
            }
        }

        /** Masks the key. */
        @Override
        public String toString() {
            return "Adapter[api=" + api + ", baseUrl=" + baseUrl + ", model=" + model + ", prompt=" + prompt
                    + ", apiKey=" + (apiKey.isEmpty() ? "" : "***") + ", maxTokens=" + maxTokens + ", requestTimeout="
                    + requestTimeout + ", structuredOutput=" + structuredOutput + ", anthropicVersion="
                    + anthropicVersion + "]";
        }
    }
}
