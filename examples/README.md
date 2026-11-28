# Examples

Minimal Spring Boot host services that embed the engines. Each one is built and tested by `mvn -B verify` at the repository root. Every example's parent is `examples/pom.xml`, which marks it as never published.

**Run the integration tests with `verify`.** The embedded Rule Engine entry point is granted only to a Process Engine class from a sealed jar of the same version (#336). Under `mvn test` in a multi-module build the engine modules are `target/classes` directories, which are never granted, so an example's `*IT` class run that way fails with `TENANT_NOT_ALLOWED`. `mvn test` runs the unit tests (`*Test`) only; `mvn verify` packages the engines first and runs the `*IT` classes. Do not name an `*IT` class with `-Dtest` in a build that has not packaged the engines.

| Example | Shows | Status |
|---|---|---|
| [`rule-engine-only/`](rule-engine-only/README.md) | A host with its own tables and Flyway history that adds the Rule Engine starter and one configuration block | Available |
| [`mcp-tool-adapter/`](mcp-tool-adapter/README.md) | An agent step that calls one tool of a Model Context Protocol server through `AgentAdapter`, keyed by the job id, with a small demo server | Available |
| [`model-adapters/`](model-adapters/README.md) | Two reference agent adapters for model APIs, OpenAI-compatible Chat Completions and Anthropic Messages, on the JDK HTTP client with no vendor SDK, tested against a local stub API | Available |
| [`guarded-agent-process/`](guarded-agent-process/README.md) | A loan document review where an agent step's answer is checked by a Rule Engine guardrail and a refused answer goes to a four-eyes reviewer, with seed data, a scripted demo and no cockpit dependency | Available |
| `process-engine-only/` | The Process Engine without the Rule Engine | Added with the first Process Engine feature |
| `both/` | Both engines in one host, sharing its database and transactions | Added with the first Process Engine feature |
