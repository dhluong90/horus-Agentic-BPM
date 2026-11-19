# HORUS Agentic BPM

Embeddable Process Engine and Rule Engine for Java and Spring Boot, with a cockpit, in which humans, services and AI agents take steps in the same governed process.

**Documentation: the [project wiki](../../wiki)** (source in [`wiki/`](wiki/)).

![System context](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/system-context.svg)

> Status: pre-release. No artifact is published yet. The first release, 0.1.0, is planned for Sun 22 Nov 2026 as a GitHub Release with jars, checksums and a bill of materials; Maven Central follows once the namespace is registered. The wiki describes the 0.1 target, which is wider than the first release.

**What the first release contains** ([roadmap](docs/product/roadmap.md#first-release-010-reviewed-20-nov-shipped-by-22-nov-2026)):

- Rule Engine: import a rule set from YAML, approval by a second person (self-approval refused), evaluation in your transaction with the `first` hit policy, one decision log row per evaluation, tenant isolation and idempotency keys.
- The host metadata and permissions API (`/api/bpm/meta`, `/api/bpm/me`), the embedded cockpit shell and the Spring Boot starter.
- An example host that runs with Docker Compose.
- A first BPMN and DMN subset, if it passes the same release checks: BPMN 2.0 XML import of start, service, business rule and user tasks, exclusive gateways and end events, and DMN 1.5 decision table import and export with a FEEL subset. Anything outside the subset is refused by name. This is not a conformance claim; conformance test pass rates are published as they are measured.

Not in the first release: `unique` and `collect` hit policies, simulation, masking, effective dates and replay, cache refresh across replicas, the full Cockpit 0.1 screens and standalone sign-in. They follow in 0.2.0, planned for 15 Jan 2027. 1.0 (May 2027) is the release with a frozen API.

Requirements: Java 21 or 25, PostgreSQL 14 or later (15 or later from 1.0), and Spring Boot 4.x or 3.5.x ([ADR-0011](docs/adr/0011-spring-boot-4-baseline.md)).

## What it looks like

A decision table in `src/main/resources/rules/loan-eligibility.yaml`:

```yaml
ruleSet: loan-eligibility
hitPolicy: first
inputs:
  - { name: age,    type: integer }
  - { name: income, type: money }
outputs:
  - { name: eligible, type: boolean }
  - { name: reason,   type: string }
rules:
  - id: R1
    when: { age: "< 18" }
    then: { eligible: false, reason: "UNDER_AGE" }
  - id: R2
    when: { income: "< 5000000 VND" }
    then: { eligible: false, reason: "LOW_INCOME" }
  - id: R3
    when: {}
    then: { eligible: true, reason: "OK" }
```

Evaluated from your service:

```java
DecisionResult r = rules.evaluate("loan-eligibility",
    DecisionInput.of("age", 25, "income", Money.ofMinor("VND", 8_000_000)));
r.matchedRuleIds();                   // ["R3"]
r.output("eligible", Boolean.class);  // true
```

The decision, its inputs and the matched row are written to the decision log in your transaction. A second person must approve every rule change before it takes effect. The full walkthrough is in [Getting Started](../../wiki/Getting-Started).

## Modules

| Module | What it does | Artifact |
|---|---|---|
| [`rule-engine/`](rule-engine/) | Decision tables (`first` in the first release; `unique` and `collect` next), versioned rule sets with maker-checker and effective dates, a safe expression language, decision API, append-only decision log, simulation | `io.github.dhluong90.bpm:rule-engine-spring-boot-starter` |
| [`process-engine/`](process-engine/) | Versioned process definitions with user, service and agent tasks, gateways and timers; human task queues with SLA and escalation; idempotent starts, saga compensation, instance migration | `io.github.dhluong90.bpm:process-engine-spring-boot-starter` |
| [`cockpit/`](cockpit/) | React dashboard for both engines, served by the host or hosted standalone as static assets | `io.github.dhluong90.bpm:cockpit-spring-boot-starter` |
| [`examples/`](examples/) | Minimal host services showing each engine alone and both together | |

The engines are libraries, not servers. State lives in your PostgreSQL under the `rle_` and `pre_` prefixes, in your transactions. The Rule Engine never depends on the Process Engine. Decisions behind this are in [`docs/adr/`](docs/adr/).

Generic by design: every business behaviour is optional and configurable per host or tenant, with a safe default, and business logic enters through extension points, never as bank-specific code in the engines ([Principles](../../wiki/Principles), [Configuration and Extension Points](../../wiki/Configuration-and-Extension-Points)).

## Where to read next

| You want to | Read |
|---|---|
| Try it | [Getting Started](../../wiki/Getting-Started) |
| Do a task: author a table, publish a version, simulate, read the log, use the cockpit | How-to guides listed on the wiki [Home](../../wiki) |
| Look up an endpoint, a property, an operator | [Decision API](../../wiki/Rule-Engine-Decision-API), [Configuration](../../wiki/Rule-Engine-Configuration), [Expression Language](../../wiki/Rule-Engine-Expression-Language) |
| Understand the design | [Concepts](../../wiki/Concepts), [Principles](../../wiki/Principles), [Architecture](../../wiki/Architecture), [Roadmap](../../wiki/Roadmap) |

## Out of scope

CMMN, a distributed job executor, a standalone server mode. See [FAQ](../../wiki/FAQ).

Full BPMN 2.0 and DMN are on the roadmap: 1.0 executes a published subset, and the releases after 1.0 extend it in stages to BPMN 2.0.2 execution conformance and DMN 1.5 Conformance Level 3, each with a published list of deviations ([ADR-0017](docs/adr/0017-full-bpmn-and-dmn-support-no-cmmn.md), [standards compliance](docs/architecture/standards-compliance.md)).

## Build from source

```bash
mvn -B verify
```

Needs JDK 21 or later and a running Docker for the Testcontainers tests. To run the example host, see [`examples/rule-engine-only/`](examples/rule-engine-only/README.md).

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md). Security issues: [`SECURITY.md`](SECURITY.md).

## Licence

Licensed under the PolyForm Noncommercial License 1.0.0. For any other use, contact HORUS. See [`LICENSE`](LICENSE) and [`COMMERCIAL-LICENSE.md`](COMMERCIAL-LICENSE.md). Contributions are accepted under [`CLA.md`](CLA.md).
