# HORUS Agentic BPM

Embeddable Process Engine and Rule Engine for Java and Spring Boot, with a cockpit, in which humans, services and AI agents take steps in the same governed process.

**Documentation: the [project wiki](../../wiki)** (source in [`wiki/`](wiki/)).

![System context](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/system-context.svg)

> Status: design preview. The wiki describes the 0.1 target and is reviewed before the code is written. No artifact is published yet.

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
    DecisionInput.of("age", 25, "income", Money.of("VND", 8_000_000)));
r.matchedRuleIds();                   // ["R3"]
r.output("eligible", Boolean.class);  // true
```

The decision, its inputs and the matched row are written to the decision log in your transaction. A second person must approve every rule change before it takes effect. The full walkthrough is in [Getting Started](../../wiki/Getting-Started).

## Modules

| Module | What it does | Artifact |
|---|---|---|
| [`rule-engine/`](rule-engine/) | Decision tables (`first`, `unique`, `collect`), versioned rule sets with maker-checker and effective dates, a safe expression language, decision API, append-only decision log, simulation | `com.horus.bpm:rule-engine-spring-boot-starter` |
| [`process-engine/`](process-engine/) | Versioned process definitions with user, service and agent tasks, gateways and timers; human task queues with SLA and escalation; idempotent starts, saga compensation, instance migration | `com.horus.bpm:process-engine-spring-boot-starter` |
| [`cockpit/`](cockpit/) | React dashboard for both engines, served by the host or hosted standalone as static assets | `com.horus.bpm:cockpit-spring-boot-starter` |
| [`examples/`](examples/) | Minimal host services showing each engine alone and both together | |

The engines are libraries, not servers. State lives in your PostgreSQL under the `rle_` and `pre_` prefixes, in your transactions. The Rule Engine never depends on the Process Engine. Decisions behind this are in [`docs/adr/`](docs/adr/).

## Where to read next

| You want to | Read |
|---|---|
| Try it | [Getting Started](../../wiki/Getting-Started) |
| Do a task: author a table, publish a version, simulate, read the log, use the cockpit | How-to guides listed on the wiki [Home](../../wiki) |
| Look up an endpoint, a property, an operator | [Decision API](../../wiki/Rule-Engine-Decision-API), [Configuration](../../wiki/Rule-Engine-Configuration), [Expression Language](../../wiki/Rule-Engine-Expression-Language) |
| Understand the design | [Concepts](../../wiki/Concepts), [Principles](../../wiki/Principles), [Architecture](../../wiki/Architecture), [Roadmap](../../wiki/Roadmap) |

## Out of scope

Full BPMN 2.0 or DMN FEEL compliance, CMMN, a distributed job executor, a standalone server mode. See [FAQ](../../wiki/FAQ).

## Contributing

See [`CONTRIBUTING.md`](CONTRIBUTING.md). Security issues: [`SECURITY.md`](SECURITY.md).

## Licence

Licensed under the PolyForm Noncommercial License 1.0.0. For any other use, contact HORUS. See [`LICENSE`](LICENSE) and [`COMMERCIAL-LICENSE.md`](COMMERCIAL-LICENSE.md). Contributions are accepted under [`CLA.md`](CLA.md).
