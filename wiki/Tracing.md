# Traces

When the host has a Micrometer `Tracer` (Spring Boot with the OpenTelemetry starter), each step the Process Engine applies and each evaluation the Rule Engine runs is a span in the host's trace, under the request or job that caused it. A decision evaluated by a process step is a child of that step's span.

| Span | Attributes |
|---|---|
| `horus.process.step` | `horus.definition_key`, `horus.definition_version`, `horus.step_kind` (`START`, `COMPLETE_TASK`, `JOB_DUE`, `ASYNC_RESULT`, `CANCEL`, `LOCAL_CALL`), `horus.outcome` (`OK`, `FAILED`, `SKIPPED`), on failure `horus.error_code` and `horus.error_type` |
| `horus.rule.evaluate` | `horus.rule_set`, `horus.rule_set.version`, `horus.decision_id`, `horus.outcome` (`MATCHED`, `NO_MATCH`, `FAILED`, `REFUSED`), `horus.matched_count`, on failure `horus.error_code` |

Spans never carry a business key, an instance id, a variable, an input, an output or an exception message. A failed span records the error code and the exception class only. `horus.tenant_id` is added to both spans unless you set `horus.bpm.tracing.tenant-attribute: false`, for a tracing back end that several tenants share.

A host without a tracer runs unchanged and needs no tracing dependency. A tracer that throws never fails or rolls back engine work.

## Sending traces to a collector

Export is off by default in the example hosts. To send traces over OTLP:

```bash
export HORUS_TRACING_EXPORT_ENABLED=true
export HORUS_TRACING_SAMPLING=0.1
export MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT=http://collector:4318/v1/traces
```

On Spring Boot 3.5 the endpoint property is `MANAGEMENT_OTLP_TRACING_ENDPOINT`. Sampling is a fraction between 0 and 1; the spans exist in the host's tracer whether or not they are sampled.

Not in this release: spans for the adapter call and result of an async step linked across their transactions, and a per-evaluation overhead figure in the benchmark. Both are tracked on #114.
