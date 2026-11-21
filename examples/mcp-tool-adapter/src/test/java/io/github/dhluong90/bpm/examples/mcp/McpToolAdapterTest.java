package io.github.dhluong90.bpm.examples.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.dhluong90.bpm.process.spi.AdapterResult;
import io.github.dhluong90.bpm.process.spi.AgentInvocation;
import io.github.dhluong90.bpm.process.test.AgentInvocations;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The adapter against the demo MCP server, without the engines (#99, PRE-097): which code each failure gets, that no
 * server text is kept, and that a result is never followed as an instruction.
 */
class McpToolAdapterTest {

    private static final String TOKEN = "demo-token";

    private final DemoMcpServer server = server();
    private final HttpClient http = HttpClient.newHttpClient();

    private static DemoMcpServer server() {
        try {
            return new DemoMcpServer(0, TOKEN, "X-Tenant-Id", DemoMcpServer.demoData()).start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterEach
    void stop() {
        server.close();
    }

    private McpToolAdapter adapter(Duration requestTimeout) {
        return new McpToolAdapter(new McpToolSettings("mcp-risk", server.url(), TOKEN,
                List.of(DemoMcpServer.TOOL), "tool", "X-Tenant-Id", McpToolSettings.PROTOCOL_VERSION, requestTimeout),
                http);
    }

    private static AgentInvocation step(String tenant, String tool, String customerId) {
        return AgentInvocations.builder().tenantId(tenant).jobId("01JOBMCP000000000000000001")
                .attemptId("01ATTMCP000000000000000001").inputs(Map.of("tool", tool, "customerId", customerId))
                .build();
    }

    @Test
    void PRE_097_a_tool_outside_the_allow_list_is_refused_and_no_request_is_sent() {
        AdapterResult result = adapter(Duration.ofSeconds(5)).execute(step("bank-a", "delete_customer", "C-778"));

        assertThat(result).isEqualTo(new AdapterResult.Failed(McpToolAdapter.TOOL_NOT_ALLOWED,
                "the step names a tool outside allowed-tools", false));
        assertThat(server.calls()).isEmpty();
    }

    @Test
    void PRE_097_a_tool_error_is_not_retryable_and_its_text_is_not_kept() {
        AdapterResult result = adapter(Duration.ofSeconds(5)).execute(step("bank-a", DemoMcpServer.TOOL, "C-ERR"));

        assertThat(result).isInstanceOfSatisfying(AdapterResult.Failed.class, f -> {
            assertThat(f.errorCode()).isEqualTo(McpToolAdapter.TOOL_ERROR);
            assertThat(f.retryable()).isFalse();
            assertThat(f.detail()).doesNotContain("Customer not found");
        });
    }

    @Test
    void PRE_097_a_json_rpc_error_an_http_5xx_and_a_timeout_are_retryable() throws Exception {
        AdapterResult rpc = adapter(Duration.ofSeconds(5)).execute(step("bank-a", DemoMcpServer.TOOL, "C-RPC"));
        AdapterResult http500 = adapter(Duration.ofSeconds(5)).execute(step("bank-a", DemoMcpServer.TOOL, "C-500"));
        CountDownLatch late = new CountDownLatch(1);
        server.holdNextAnswer(late);
        AdapterResult timeout = adapter(Duration.ofMillis(300)).execute(step("bank-a", DemoMcpServer.TOOL, "C-778"));
        late.countDown();

        // The JSON-RPC error keeps its number only, never the server's message
        assertThat(rpc).isEqualTo(new AdapterResult.Failed(McpToolAdapter.PROTOCOL_ERROR, "jsonrpc=-32603", true));
        assertThat(http500).isEqualTo(new AdapterResult.Failed(McpToolAdapter.PROTOCOL_ERROR, "http=500", true));
        assertThat(timeout).isInstanceOfSatisfying(AdapterResult.Failed.class, f -> {
            assertThat(f.errorCode()).isEqualTo(McpToolAdapter.TIMEOUT);
            assertThat(f.retryable()).isTrue();
        });
    }

    @Test
    void PRE_097_a_text_only_answer_is_returned_empty_and_its_instruction_is_never_followed() {
        AdapterResult result = adapter(Duration.ofSeconds(5)).execute(step("bank-a", DemoMcpServer.TOOL, "C-TEXT"));

        // The engine's schema check refuses the empty answer (AGENT_SCHEMA_INVALID); the adapter took nothing from
        // the text, which asked for another tool, and called no second tool
        assertThat(result).isInstanceOfSatisfying(AdapterResult.Completed.class,
                c -> assertThat(c.output()).isEmpty());
        assertThat(server.calls()).singleElement().satisfies(call -> assertThat(call.tool())
                .isEqualTo(DemoMcpServer.TOOL));
    }

    @Test
    void PRE_096_the_call_carries_the_job_id_as_idempotency_key_and_the_tenant_and_returns_structured_content() {
        AdapterResult result = adapter(Duration.ofSeconds(5)).execute(step("bank-a", DemoMcpServer.TOOL, "C-778"));

        assertThat(result).isInstanceOfSatisfying(AdapterResult.Completed.class, c -> {
            assertThat(c.output()).isEqualTo(Map.of("riskBand", "B", "score", 62));
            assertThat(c.labels()).containsEntry("mcp.tool", DemoMcpServer.TOOL);
        });
        assertThat(server.calls()).singleElement().satisfies(call -> {
            assertThat(call.idempotencyKey()).isEqualTo("01JOBMCP000000000000000001");
            assertThat(call.correlationId()).isEqualTo("01ATTMCP000000000000000001");
            assertThat(call.tenant()).isEqualTo("bank-a");
            // The tool input chose the tool; it is not an argument
            assertThat(call.arguments()).isEqualTo(Map.of("customerId", "C-778"));
        });
    }

    @Test
    void another_tenants_customer_is_refused_by_the_server_and_nothing_is_stored() {
        AdapterResult result = adapter(Duration.ofSeconds(5)).execute(step("bank-b", DemoMcpServer.TOOL, "C-778"));

        assertThat(result).isInstanceOfSatisfying(AdapterResult.Failed.class,
                f -> assertThat(f.errorCode()).isEqualTo(McpToolAdapter.TOOL_ERROR));
        assertThat(server.executions()).isZero();
    }

    @Test
    void settings_mask_the_token_and_refuse_an_empty_allow_list() {
        McpToolSettings settings = new McpToolSettings("mcp-risk", URI.create("http://localhost:8931/mcp"), TOKEN,
                List.of(DemoMcpServer.TOOL), "tool", "X-Tenant-Id", McpToolSettings.PROTOCOL_VERSION,
                Duration.ofSeconds(30));

        assertThat(settings.toString()).doesNotContain(TOKEN).contains("bearerToken=***");
        assertThatThrownBy(() -> new McpToolSettings("mcp-risk", URI.create("http://localhost:8931/mcp"), TOKEN,
                List.of(), "tool", "X-Tenant-Id", McpToolSettings.PROTOCOL_VERSION, Duration.ofSeconds(30)))
                .hasMessageContaining("example.mcp.allowed-tools");
        assertThatThrownBy(() -> new McpToolSettings("mcp-risk", URI.create("http://localhost:8931/mcp"), " ",
                List.of(DemoMcpServer.TOOL), "tool", "X-Tenant-Id", McpToolSettings.PROTOCOL_VERSION,
                Duration.ofSeconds(30))).hasMessageContaining("example.mcp.bearer-token");
    }
}
