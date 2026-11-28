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

## Async steps: span links

A remote service or agent step crosses transactions: the step that schedules the call ends, and the answer is applied later by the job runner or through `POST .../steps/{id}:complete`, in a trace of its own. The `horus.process.step` span that applies the answer (kind `ASYNC_RESULT`, or the review routing of a refused answer) carries one span link to the span that scheduled the call, so the tracing back end shows both halves of the step together. The engine stores the reference in the job's payload as `traceLink`. It is a trace id and a span id and nothing else. A job written before this release has no link and its answer is applied as before. A tracer that cannot link, or a damaged reference, gives an ordinary span.

## What tracing costs

Measured with `TracingOverheadBenchmark` (JMH, `examples/rule-engine-benchmarks`) on an OpenTelemetry SDK tracer: the spans of the shipped bindings, one thread, no network. A rule evaluation is the 200-row table of `TR-PERF-01`; a step is the span a Process Engine step adds around its own work. Apple M5, JDK 25.0.4, 3 warm-up and 5 measured iterations of 2 s; one run, so read the differences as an order of magnitude, not as a promise. CI repeats the run every night and keeps the report with the other benchmarks.

| Case | Tracing off | Sampler off (spans for parenting only) | Every span recorded and queued |
|---|---|---|---|
| Rule evaluation, mean | 5.02 us | 5.18 us (+0.16 us) | 5.64 us (+0.62 us) |
| Rule evaluation, p99 | 26.2 us | 26.8 us | 31.1 us |
| Process step span, mean | 0.03 us | 0.56 us | 0.72 us |
| Process step span, p99 | 0.12 us | 1.33 us | 1.75 us |

"Every span recorded" is the cost in the request thread with an exporter that discards its batch. Sending the batch over the network happens on the exporter's own thread. At the default sampling of 10 percent most spans cost the middle column. The example hosts keep export off.

Spring Boot's OpenTelemetry starter also switches on an OTLP metrics registry and an OTLP log exporter, and each sends to `http://localhost:4318` once a minute until a collector exists. The example host turns both off (`HORUS_METRICS_OTLP_EXPORT_ENABLED` and `HORUS_LOGGING_OTLP_EXPORT_ENABLED`, both `false`); a host of your own that uses the starter should do the same, or name its collector. Metrics reach Prometheus through `/actuator/prometheus`.
