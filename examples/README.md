# Examples

Minimal Spring Boot host services that embed the engines. Each one is built and tested by `mvn -B verify` at the repository root.

| Example | Shows | Status |
|---|---|---|
| [`rule-engine-only/`](rule-engine-only/README.md) | A host with its own tables and Flyway history that adds the Rule Engine starter and one configuration block | Available |
| `process-engine-only/` | The Process Engine without the Rule Engine | Added with the first Process Engine feature |
| `both/` | Both engines in one host, sharing its database and transactions | Added with the first Process Engine feature |
