# Example: MCP tool adapter

An agent step that calls one tool of a [Model Context Protocol](https://modelcontextprotocol.io) server. The example implements the engine's `AgentAdapter` interface with the JDK HTTP client and calls `tools/call` over the Streamable HTTP transport, MCP revision `2025-06-18`, which is the revision it was written and tested against. Nothing here is part of an engine artifact.

```mermaid
sequenceDiagram
    autonumber
    participant E as Process Engine executor
    participant A as McpToolAdapter (mcp-risk)
    participant S as MCP server
    participant G as Result check
    E->>A: execute(invocation): inputs tool, customerId; job id; attempt id
    A->>A: tool in allowed-tools? else MCP_TOOL_NOT_ALLOWED, no request
    A->>S: initialize, notifications/initialized
    A->>S: tools/call lookup_customer_risk, _meta.idempotencyKey = job id, correlationId = attempt id
    S->>S: run once per idempotency key, else return the stored result
    S-->>A: structuredContent { riskBand, score }
    A-->>E: Completed(structuredContent)
    E->>G: output schema, then guardrail customer-risk-guardrails
    G-->>E: allow, or review task for role:RISK_REVIEWER
```

## How it works

- **The process chooses the tool.** Step `score-customer` in [`customer-risk-scoring.bpmn`](src/main/resources/processes/customer-risk-scoring.bpmn) names the adapter (`horus:agent="mcp-risk"`) and the tool, as the literal input `tool`. The adapter refuses any tool that is not in `example.mcp.allowed-tools` before it sends anything. The tools a server advertises are never used to pick one.
- **Side effects are keyed by the job id.** A tool call may change data on the server, so the job id, which every retry keeps, goes in `_meta.idempotencyKey`, and the demo server deduplicates by it. The attempt id travels only as `_meta.correlationId`.
- **The result is data.** The tool's `structuredContent` is the step's answer, checked by the engine in the result transaction against the output schema and the guardrail rule set [`customer-risk-guardrails`](src/main/resources/rules/customer-risk-guardrails.yaml), which sends band D to a reviewer. A text-only answer is returned empty, so the schema check refuses it; its text is never read, and the adapter calls no second tool.
- **Tenancy.** The instance's tenant goes to the server in `X-Tenant-Id`; the server's own authorisation decides what that tenant may see. The demo server answers another tenant's customer as not found and stores nothing.
- **No call inside a transaction.** The engine calls the adapter on its executor with no connection held (ADR-0008).

| Failure | Code | Retried |
|---|---|---|
| The step names a tool outside the allow-list | `MCP_TOOL_NOT_ALLOWED` | no; no request is sent |
| The tool answers `isError: true` | `MCP_TOOL_ERROR` | no |
| A JSON-RPC error, an HTTP 5xx, or a timeout | `MCP_PROTOCOL_ERROR` or `ADAPTER_TIMEOUT` | yes |
| Text only, no `structuredContent` | `AGENT_SCHEMA_INVALID`, from the engine's result check | yes, once (schema retry) |
| A structured answer the guardrail refuses | `GUARDRAIL_VIOLATION`; the step goes to its reviewer | n/a |

No server message text is kept: history records the code, and the adapter's detail holds at most the JSON-RPC error number or the HTTP status.

## Configuration

| Property | Environment variable | Default | Meaning |
|---|---|---|---|
| `example.mcp.name` | | `mcp-risk` | The adapter name steps use in `horus:agent` |
| `example.mcp.url` | `MCP_SERVER_URL` | `http://localhost:8931/mcp` | The server's Streamable HTTP endpoint |
| `example.mcp.bearer-token` | `MCP_BEARER_TOKEN` | none, required | Sent as `Authorization: Bearer`; masked in logs and `toString()` |
| `example.mcp.allowed-tools` | | `[lookup_customer_risk]` | The tools a step may name |
| `example.mcp.tool-input` | | `tool` | The step input that names the tool; not passed on as an argument |
| `example.mcp.tenant-header` | | `X-Tenant-Id` | The header that carries the tenant |
| `example.mcp.request-timeout` | | `PT30S` | Longest single HTTP request; the engine abandons an attempt at the step timeout |
| `spring.datasource.*` | `HORUS_DB_URL`, `HORUS_DB_USERNAME`, `HORUS_DB_PASSWORD` | local PostgreSQL | The host database the engines keep their state in |

The host's sign-in, which gives the engines their caller and tenant, is left out of this example; the [`rule-and-process`](../rule-and-process/README.md) example shows it.

## Run the demo

The tests run the whole scenario on PostgreSQL in a container (Docker needed), with both engines and the demo MCP server:

```bash
mvn -B -pl examples/mcp-tool-adapter -am verify -Dcockpit.bundle.skip=true
```

`McpToolAdapterIT` starts an application for customer C-778, holds the server's first answer past the step's one-second timeout, and shows the retry reusing the job id: the server runs the tool once, returns the stored `riskBand B, score 62` to the second call, and the application moves on to `decide`. The other tests cover a text-only answer, a band D customer routed to a reviewer, and another tenant's request refused by the server. `McpToolAdapterTest` covers each failure code without the engines, and `EngineIndependenceTest` checks that no engine module depends on the example.

## Files

- [`McpToolAdapter`](src/main/java/io/github/dhluong90/bpm/examples/mcp/McpToolAdapter.java): the adapter
- [`McpToolSettings`](src/main/java/io/github/dhluong90/bpm/examples/mcp/McpToolSettings.java): `example.mcp.*`
- [`DemoMcpServer`](src/main/java/io/github/dhluong90/bpm/examples/mcp/DemoMcpServer.java): the demo server, for the example and its tests only; an MCP server is never packaged with the engines
