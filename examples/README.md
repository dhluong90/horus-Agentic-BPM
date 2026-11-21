# Examples

Minimal Spring Boot host services that embed the engines. Each one is built and tested by `mvn -B verify` at the repository root. Every example's parent is `examples/pom.xml`, which marks it as never published.

| Example | Shows | Status |
|---|---|---|
| [`rule-engine-only/`](rule-engine-only/README.md) | A host with its own tables and Flyway history that adds the Rule Engine starter and one configuration block | Available |
| [`mcp-tool-adapter/`](mcp-tool-adapter/README.md) | An agent step that calls one tool of a Model Context Protocol server through `AgentAdapter`, keyed by the job id, with a small demo server | Available |
| [`model-adapters/`](model-adapters/README.md) | Two reference agent adapters for model APIs, OpenAI-compatible Chat Completions and Anthropic Messages, on the JDK HTTP client with no vendor SDK, tested against a local stub API | Available |
| `process-engine-only/` | The Process Engine without the Rule Engine | Added with the first Process Engine feature |
| `both/` | Both engines in one host, sharing its database and transactions | Added with the first Process Engine feature |
