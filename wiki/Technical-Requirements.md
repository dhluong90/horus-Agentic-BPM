# Technical Requirements

The engines are held to numbered, testable requirements with ids such as `TR-EMB-02` or `TR-SEC-04`. Each requirement states how it is verified.

**Full list: [docs/product/requirements.md](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/docs/product/requirements.md).** Other wiki pages quote requirement ids; look them up there.

## What gates a release

- Every "Must" requirement that applies to a module gates every release of that module. A Must with a milestone, for example "Must (M2)", gates releases from that milestone on.
- "Should" requirements are targets. [[Release Notes]] list the ones each release meets.

## Requirement groups

| Group | Ids | Covers |
|---|---|---|
| Embedding and compatibility | `TR-EMB-*` | One starter, your `DataSource` and transactions, no network I/O; supported Java, Spring Boot and PostgreSQL versions (`TR-EMB-02`) |
| Determinism and correctness | `TR-DET-*` | Same version, inputs and clock give the same result; expressions checked at publish time |
| Security | `TR-SEC-*` | No code execution, bounded regex, tenant from the resolver only, maker-checker, masking, error bodies, cockpit headers |
| Reliability under retries | `TR-IDEM-*` | Idempotency keys, business keys, optimistic locking |
| Concurrency and asynchronous steps | `TR-ASY-*` | No external call inside a database transaction, job leases, gateway joins, cache refresh across replicas |
| Compensation | `TR-COMP-*` | Compensate handlers run in reverse order, with retries and incidents |
| Business calendars | `TR-CAL-*` | SLA deadlines through a business calendar per tenant |
| Instance migration | `TR-MIG-*` | Dry run and batched migration of running instances between versions |
| Agentic steps | `TR-AGT-*` | Schema and guardrail checks on agent output, history, adapter timeouts |
| Multi-tenancy | `TR-TEN-*` | `tenant_id` on every table and query |
| Observability and audit | `TR-OBS-*` | Append-only logs, metrics, traces, events |
| Performance and scalability | `TR-PERF-*` | Evaluation latency, compile time, replicas sharing jobs, bounded cache |
| Operability | `TR-OPS-*` | Forward-only migrations, upgrades, incidents, connection poolers |
| Cockpit | `TR-UI-*` | Authoring and approval in the cockpit, accessibility, languages |
| Quality and documentation | `TR-QA-*` | Tests named by specification id, documentation in the same pull request, coverage |
| Licensing | `TR-LIC-*` | Permissive dependencies only, licence notice on every artifact |
