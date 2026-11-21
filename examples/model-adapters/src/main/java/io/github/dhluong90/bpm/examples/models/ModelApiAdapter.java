package io.github.dhluong90.bpm.examples.models;

import io.github.dhluong90.bpm.process.spi.AdapterResult;
import io.github.dhluong90.bpm.process.spi.AgentAdapter;
import io.github.dhluong90.bpm.process.spi.AgentInvocation;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What both example model adapters share: render the step's versioned prompt from its bounded inputs, call the API
 * once per attempt on the JDK HTTP client, map the API's errors to attempt codes, and return the model's JSON answer
 * with its labels. The subclasses build the request and read the answer of their API.
 *
 * <p>Keys (see {@link AgentInvocation}): a model call changes nothing on the far side, so it is keyed by the attempt
 * id ({@link AgentInvocation#modelCallKey()}), which each subclass sends in its API's user or metadata field. Every
 * retry is a new call and a fresh generation. Nothing here has a side effect that the job id would need to key.
 *
 * <p>Checks: the answer is returned as the model gave it. Prose, or JSON that is not an object, is returned as an
 * empty answer, so the engine's result check refuses it with {@code AGENT_SCHEMA_INVALID}; its text is never kept.
 * The engine checks every answer against the output schema and the guardrail in the result transaction; nothing
 * here replaces that check.
 *
 * <p>Logs: the adapter name, the attempt id, the HTTP status and the latency. Never the prompt, an input, the answer
 * or the key. No tenant id is sent to the vendor. Called by the engine with no transaction open (ADR-0008).
 * Thread-safe.
 */
public abstract class ModelApiAdapter implements AgentAdapter {

    /** HTTP 400, 404, 413 or 422: the request is wrong and repeating it cannot help. */
    public static final String REQUEST_INVALID = "MODEL_REQUEST_INVALID";
    /** HTTP 401 or 403: the key is missing, wrong or not allowed. */
    public static final String AUTH_FAILED = "MODEL_AUTH_FAILED";
    /** HTTP 429: retried after the step's backoff. */
    public static final String RATE_LIMITED = "MODEL_RATE_LIMITED";
    /** HTTP 408, 5xx, an overloaded API or a broken connection: retried. */
    public static final String UNAVAILABLE = "MODEL_UNAVAILABLE";
    /** The model refused, or a content filter stopped the answer: not retried. */
    public static final String REFUSED = "MODEL_REFUSED";
    /** The request did not complete within {@code request-timeout}, or the engine abandoned the attempt. */
    public static final String TIMEOUT = "ADAPTER_TIMEOUT";

    /** The labels of a completed answer, as the engine reserves them. */
    static final String MODEL = AdapterResult.Completed.MODEL;
    static final String PROMPT_VERSION = AdapterResult.Completed.PROMPT_VERSION;
    static final String INPUT_TOKENS = AdapterResult.Completed.INPUT_TOKENS;
    static final String OUTPUT_TOKENS = AdapterResult.Completed.OUTPUT_TOKENS;

    static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    private static final System.Logger LOG = System.getLogger(ModelApiAdapter.class.getName());

    private final String name;
    private final ModelAdapterSettings.Adapter settings;
    private final PromptTemplate prompt;
    private final HttpClient http;
    private final Clock clock;

    /**
     * @param name the adapter name steps use in {@code horus:agent}
     * @param settings its configuration
     * @param prompt its prompt template, loaded once
     * @param http the HTTP client
     * @param clock the clock latencies are measured with
     */
    protected ModelApiAdapter(String name, ModelAdapterSettings.Adapter settings, PromptTemplate prompt,
            HttpClient http, Clock clock) {
        this.name = Objects.requireNonNull(name, "name");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.prompt = Objects.requireNonNull(prompt, "prompt");
        this.http = Objects.requireNonNull(http, "http");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public final String name() {
        return name;
    }

    /** @return the configuration */
    protected final ModelAdapterSettings.Adapter settings() {
        return settings;
    }

    /**
     * The API's request for one attempt.
     *
     * @param renderedPrompt the prompt
     * @param invocation the attempt
     * @return the request, with its key header and timeout
     */
    protected abstract HttpRequest request(String renderedPrompt, AgentInvocation invocation);

    /**
     * Reads a 2xx answer of the API.
     *
     * @param body the response body
     * @return the answer
     */
    protected abstract Answer answer(JsonNode body);

    /** A 2xx answer: the model's text and how it was produced, or a refusal. */
    protected sealed interface Answer permits Answer.Text, Answer.Refused {

        /**
         * @param content the model's text, expected to be one JSON object
         * @param model the model the API says answered, or null
         * @param inputTokens input tokens, or null
         * @param outputTokens output tokens, or null
         */
        record Text(String content, String model, Long inputTokens, Long outputTokens) implements Answer {
        }

        /** @param reason the API's stop reason, for the log only */
        record Refused(String reason) implements Answer {
        }
    }

    /** The request body as JSON. */
    protected static HttpRequest.BodyPublisher body(Map<String, Object> body) {
        return HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body), StandardCharsets.UTF_8);
    }

    @Override
    public final AdapterResult execute(AgentInvocation invocation) {
        String rendered;
        try {
            rendered = prompt.render(invocation.inputs(), invocation.memory());
        } catch (IllegalArgumentException e) {
            return log(invocation, "-", new AdapterResult.Failed(REQUEST_INVALID, "the prompt template names an input "
                    + "the step does not give", false), clock.instant());
        }
        Instant started = clock.instant();
        HttpResponse<String> response;
        try {
            response = http.send(request(rendered, invocation), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            return log(invocation, "timeout", new AdapterResult.Failed(TIMEOUT, "the model request timed out", true),
                    started);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return log(invocation, "abandoned", new AdapterResult.Failed(TIMEOUT, "the attempt was abandoned", true),
                    started);
        } catch (IOException e) {
            return log(invocation, "io", new AdapterResult.Failed(UNAVAILABLE, "transport "
                    + e.getClass().getSimpleName(), true), started);
        }
        int status = response.statusCode();
        return log(invocation, Integer.toString(status), result(status, response), started);
    }

    private AdapterResult result(int status, HttpResponse<String> response) {
        if (status >= 200 && status < 300) {
            JsonNode body;
            try {
                body = JSON.readTree(response.body());
            } catch (JacksonException e) {
                return new AdapterResult.Failed(UNAVAILABLE, "http=" + status + " unreadable body", true);
            }
            return switch (answer(body)) {
                case Answer.Refused r -> new AdapterResult.Failed(REFUSED, "stop=" + r.reason(), false);
                case Answer.Text t -> completed(t);
            };
        }
        // The status only: an error body may echo the prompt.
        String detail = "http=" + status;
        return switch (status) {
            case 400, 404, 413, 422 -> new AdapterResult.Failed(REQUEST_INVALID, detail, false);
            case 401, 403 -> new AdapterResult.Failed(AUTH_FAILED, detail, false);
            case 429 -> new AdapterResult.Failed(RATE_LIMITED, detail + response.headers().firstValue("Retry-After")
                    .map(s -> " retryAfter=" + s.replaceAll("[^0-9]", "")).orElse(""), true);
            default -> status == 408 || status >= 500 ? new AdapterResult.Failed(UNAVAILABLE, detail, true)
                    : new AdapterResult.Failed(REQUEST_INVALID, detail, false);
        };
    }

    private AdapterResult completed(Answer.Text text) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(MODEL, text.model() == null || text.model().isBlank() ? settings.model() : text.model());
        labels.put(PROMPT_VERSION, prompt.version());
        if (text.inputTokens() != null) {
            labels.put(INPUT_TOKENS, Long.toString(text.inputTokens()));
        }
        if (text.outputTokens() != null) {
            labels.put(OUTPUT_TOKENS, Long.toString(text.outputTokens()));
        }
        return AdapterResult.completed(jsonObject(text.content()), labels);
    }

    /** The answer as a JSON object, a fenced one included; anything else is empty, so the result check refuses it. */
    static Map<String, Object> jsonObject(String content) {
        if (content == null) {
            return Map.of();
        }
        String text = content.strip();
        if (text.startsWith("```")) {
            int open = text.indexOf('\n');
            int close = text.lastIndexOf("```");
            text = open < 0 || close <= open ? "" : text.substring(open + 1, close).strip();
        }
        try {
            JsonNode node = JSON.readTree(text);
            if (node == null || !node.isObject()) {
                return Map.of();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> output = JSON.convertValue(node, Map.class);
            return output;
        } catch (JacksonException e) {
            return Map.of();
        }
    }

    private AdapterResult log(AgentInvocation invocation, String status, AdapterResult result, Instant started) {
        String code = result instanceof AdapterResult.Failed f ? f.errorCode() : "OK";
        LOG.log(System.Logger.Level.INFO, "model.call adapter={0} api={1} attempt={2} status={3} result={4} latencyMs={5}",
                name, settings.api(), invocation.modelCallKey(), status, code,
                Duration.between(started, clock.instant()).toMillis());
        return result;
    }
}
