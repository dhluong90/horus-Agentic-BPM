package io.github.dhluong90.bpm.examples.mcp;

import io.github.dhluong90.bpm.process.spi.AdapterResult;
import io.github.dhluong90.bpm.process.spi.AgentAdapter;
import io.github.dhluong90.bpm.process.spi.AgentInvocation;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * An {@link AgentAdapter} that calls one tool of a Model Context Protocol server with {@code tools/call} over the
 * Streamable HTTP transport (MCP revision {@value McpToolSettings#PROTOCOL_VERSION}), on the JDK HTTP client.
 *
 * <p>The process chooses the tool, not the model: the step names it in the input {@code tool} (configurable), and a
 * tool outside {@code allowed-tools} is refused before any request is sent. The step's other inputs are the tool's
 * arguments. The tool's {@code structuredContent} is returned as the answer as it is; the engine checks it against the
 * step's output schema and guardrail in the result transaction. A text-only answer is returned as an empty answer, so
 * that check refuses it, and its text is never read: a tool result is data, never an instruction, and this adapter
 * calls no second tool.
 *
 * <p>A tool call may change data on the server, so it is keyed by the job id, which every retry keeps
 * ({@link AgentInvocation#idempotencyKey()}), sent as {@code _meta.idempotencyKey}. The attempt id travels only as
 * {@code _meta.correlationId}. The instance's tenant goes to the server in {@code tenant-header}.
 *
 * <p>Codes of a failed attempt, and nothing of the server's message text: {@code MCP_TOOL_NOT_ALLOWED} (not
 * retryable, no request sent), {@code MCP_TOOL_ERROR} (the tool answered {@code isError}, not retryable),
 * {@code MCP_PROTOCOL_ERROR} (a JSON-RPC error, retryable, with its number; an HTTP 5xx, retryable; another HTTP status,
 * not retryable) and {@code ADAPTER_TIMEOUT} (retryable). One session per attempt: {@code initialize}, then
 * {@code notifications/initialized}, then the call. Thread-safe.
 */
public final class McpToolAdapter implements AgentAdapter {

    /** A step named a tool outside the allow-list. */
    public static final String TOOL_NOT_ALLOWED = "MCP_TOOL_NOT_ALLOWED";
    /** The tool answered with {@code isError: true}. */
    public static final String TOOL_ERROR = "MCP_TOOL_ERROR";
    /** A JSON-RPC error, an unexpected HTTP status or an unreadable answer. */
    public static final String PROTOCOL_ERROR = "MCP_PROTOCOL_ERROR";
    /** The request did not complete in time, or the engine abandoned the attempt. */
    public static final String TIMEOUT = "ADAPTER_TIMEOUT";

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();
    private static final String SESSION_HEADER = "Mcp-Session-Id";

    private final McpToolSettings settings;
    private final HttpClient http;
    private final AtomicLong ids = new AtomicLong();

    /**
     * @param settings host configuration
     * @param http the HTTP client
     */
    public McpToolAdapter(McpToolSettings settings, HttpClient http) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.http = Objects.requireNonNull(http, "http");
    }

    @Override
    public String name() {
        return settings.name();
    }

    @Override
    public AdapterResult execute(AgentInvocation invocation) {
        Object named = invocation.inputs().get(settings.toolInput());
        if (!(named instanceof String tool) || !settings.allowedTools().contains(tool)) {
            return new AdapterResult.Failed(TOOL_NOT_ALLOWED, "the step names a tool outside allowed-tools", false);
        }
        Map<String, Object> arguments = new LinkedHashMap<>(invocation.inputs());
        arguments.remove(settings.toolInput());
        try {
            Exchange init = post(null, invocation.tenantId(), request("initialize", Map.of(
                    "protocolVersion", settings.protocolVersion(),
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "horus-bpm-mcp-example", "version", "1.0.0"))));
            if (init.failure() != null) {
                return init.failure();
            }
            String session = init.session();
            Exchange initialized = post(session, invocation.tenantId(), notification("notifications/initialized"));
            if (initialized.failure() != null) {
                return initialized.failure();
            }
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", tool);
            params.put("arguments", arguments);
            params.put("_meta", Map.of("idempotencyKey", invocation.idempotencyKey(),
                    "correlationId", invocation.attemptId()));
            Exchange call = post(session, invocation.tenantId(), request("tools/call", params));
            if (call.failure() != null) {
                return call.failure();
            }
            return answer(tool, call.body());
        } catch (HttpTimeoutException e) {
            return new AdapterResult.Failed(TIMEOUT, "the MCP request timed out", true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new AdapterResult.Failed(TIMEOUT, "the attempt was abandoned", true);
        } catch (IOException | JacksonException e) {
            return new AdapterResult.Failed(PROTOCOL_ERROR, "transport " + e.getClass().getSimpleName(), true);
        }
    }

    private static AdapterResult answer(String tool, JsonNode response) {
        if (response == null || !response.isObject()) {
            return new AdapterResult.Failed(PROTOCOL_ERROR, "no JSON-RPC response", true);
        }
        JsonNode error = response.get("error");
        if (error != null && !error.isNull()) {
            // The number only: a server message may echo data or carry text meant for a model.
            return new AdapterResult.Failed(PROTOCOL_ERROR, "jsonrpc=" + error.path("code").asLong(0), true);
        }
        JsonNode result = response.get("result");
        if (result == null || !result.isObject()) {
            return new AdapterResult.Failed(PROTOCOL_ERROR, "no result", true);
        }
        if (result.path("isError").asBoolean(false)) {
            return new AdapterResult.Failed(TOOL_ERROR, "the tool reported an error", false);
        }
        Map<String, String> labels = Map.of("mcp.tool", tool);
        JsonNode structured = result.get("structuredContent");
        if (structured == null || !structured.isObject()) {
            // Text only: nothing is taken from it; the engine's schema check refuses the empty answer.
            return AdapterResult.completed(Map.of(), labels);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> output = JSON.convertValue(structured, Map.class);
        return AdapterResult.completed(output, labels);
    }

    private ObjectNode request(String method, Map<String, Object> params) {
        ObjectNode node = JSON.createObjectNode();
        node.put("jsonrpc", "2.0");
        node.put("id", ids.incrementAndGet());
        node.put("method", method);
        node.set("params", JSON.valueToTree(params));
        return node;
    }

    private static ObjectNode notification(String method) {
        ObjectNode node = JSON.createObjectNode();
        node.put("jsonrpc", "2.0");
        node.put("method", method);
        return node;
    }

    /** One HTTP exchange: the session id the server gave, the JSON-RPC body, or the failure it maps to. */
    private record Exchange(String session, JsonNode body, AdapterResult failure) {
    }

    private Exchange post(String session, String tenant, ObjectNode message) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(settings.url())
                .timeout(settings.requestTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + settings.bearerToken())
                .header(settings.tenantHeader(), tenant)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(message), StandardCharsets.UTF_8));
        if (session != null) {
            builder.header(SESSION_HEADER, session);
        }
        if (!"initialize".equals(message.path("method").asString(""))) {
            builder.header("MCP-Protocol-Version", settings.protocolVersion());
        }
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(
                StandardCharsets.UTF_8));
        int status = response.statusCode();
        if (status >= 500) {
            return new Exchange(null, null, new AdapterResult.Failed(PROTOCOL_ERROR, "http=" + status, true));
        }
        if (status >= 300) {
            return new Exchange(null, null, new AdapterResult.Failed(PROTOCOL_ERROR, "http=" + status, false));
        }
        String next = response.headers().firstValue(SESSION_HEADER).orElse(session);
        if (!message.has("id")) {
            return new Exchange(next, null, null);
        }
        Optional<String> type = response.headers().firstValue("Content-Type");
        JsonNode body = type.isPresent() && type.get().startsWith("text/event-stream")
                ? fromEventStream(response.body(), message.get("id").asLong())
                : JSON.readTree(response.body());
        JsonNode error = body == null ? null : body.get("error");
        if (error != null && !error.isNull() && "initialize".equals(message.path("method").asString(""))) {
            return new Exchange(null, null, new AdapterResult.Failed(PROTOCOL_ERROR,
                    "jsonrpc=" + error.path("code").asLong(0), true));
        }
        return new Exchange(next, body, null);
    }

    /** The JSON-RPC response with this id among the {@code data:} events of a server-sent event stream. */
    private static JsonNode fromEventStream(String stream, long id) {
        StringBuilder data = new StringBuilder();
        for (String line : (stream + "\n\n").split("\n", -1)) {
            if (line.startsWith("data:")) {
                data.append(line.substring(5).strip());
            } else if (line.isBlank() && !data.isEmpty()) {
                JsonNode event = JSON.readTree(data.toString());
                data.setLength(0);
                if (event.path("id").asLong(-1) == id) {
                    return event;
                }
            }
        }
        return null;
    }
}
