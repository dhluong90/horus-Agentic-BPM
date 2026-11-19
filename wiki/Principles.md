# Design Principles

Thirteen rules that decide trade-offs. A proposal that conflicts with one either changes, or amends the principle through an ADR in `docs/adr/`.

Design preview: the principles are in force now; the behaviour they describe is the 0.1 target.

| # | Principle | Therefore |
|---|---|---|
| 1 | A library, never a server. Each engine runs inside the host JVM (ADR-0004). | No cluster, no network hop on evaluation, no separate identity. The host's deployment is the engine's deployment. |
| 2 | The host owns the data. State lives in the host's PostgreSQL under a prefixed schema (ADR-0002). | One database to operate. Engine and business data commit in one transaction. Nothing leaves the host's service. |
| 3 | Deterministic by construction. Same version, inputs and clock give the same result. The evaluator performs no I/O. | Decisions replay and explain years later. Simulations are trustworthy. Tests need no mocks. |
| 4 | No arbitrary code in definitions. Expressions are data parsed into a closed set of operations (ADR-0003). | Business users change behaviour without opening a security hole. Definitions review like documents. |
| 5 | Everything versioned, nothing rewritten. Published definitions are immutable, instances pin their version, logs are append-only. | Audits reconstruct the past exactly. A change never breaks work in flight. |
| 6 | Governance built in, for rule sets and process definitions. Draft, validate or simulate, request, approve by another identity, effective date, retire. The engine enforces each step. | Risk and compliance can accept business-user autonomy. Diagram layout changes, such as moving a box on a process diagram, are outside this control: they never change behaviour, so they need no approval and are recorded with who made them. |
| 7 | Agents are participants with obligations. Bounded inputs, schema-validated output, guardrail rules, timeout, audit record. The engine never embeds a model. | Autonomy inside rails that people can read, test and approve. |
| 8 | Small surface, stable contracts. Few concepts, few endpoints, semver per module. A deprecated API stays at least one minor release and six months, and is removed only in a major release. | Upgrades are routine. Documentation can be complete. |
| 9 | BPMN 2.0 and DMN are the models. Our JSON is their canonical serialisation and always has a diagram. What each release executes is exactly what the [standards compliance](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/docs/architecture/standards-compliance.md) page lists. | Teams bring and take standard models; the engines stay deterministic and code-free; every deviation is published. |
| 10 | Operable from day one. History, decision log, metrics, traces, incidents with retry. The cockpit is part of the product (ADR-0005). | The people who run the host can run the engines. |
| 11 | Permissive dependencies, explicit licence. Apache 2.0, MIT and BSD only, SBOM per release. | Legal review of the engines is a one-time exercise. |
| 12 | Documentation precedes code. Behaviour is written in this wiki and in module specifications first, and changes with the code. | The design is reviewed before it is built. Docs never lag. |
| 13 | Generic by design. Every business behaviour is optional and configurable per host or tenant, with a safe default. Business logic enters through extension points (SPI interfaces, Spring beans, hooks), not as built-in behaviour. Nothing bank-specific is hard-coded. The core stays small. | The same engines serve a bank, a fintech, an insurer or a public agency by configuration. A customer need becomes a setting or an extension, never a fork. |

## Generic by design in practice

Principle 13 is checked in every design review against six rules. See [[Configuration and Extension Points]] for the settings and extension points that exist today.

| # | Rule | Example |
|---|---|---|
| 1 | A behaviour that differs between businesses is a setting or an extension point. Its default is the safe choice: controls stay on and nothing is deleted | The decision log is written in your transaction by default; asynchronous writing is a setting (M2) |
| 2 | A setting that relaxes a control is off by default, logged when used, and shown in the host's `/api/bpm/meta` settings | The emergency single-approver path exists only when you turn it on |
| 3 | Some guarantees are never settings: tenant isolation, append-only audit, determinism, no code in definitions, fail-closed guardrails, and the four-eyes rule on publication, which only the emergency path relaxes | The FEEL error mode is configurable per model, but a guardrail still fails closed in every mode |
| 4 | No business values in engine code. Currencies, tiers, fee codes, reason codes, roles, languages, calendars and SLA periods come from definitions, configuration or host extensions. Examples, templates and seed data may use banking data; the engine core may not | SLA breaches are measured against each task definition's own deadline |
| 5 | Every setting has a default, a documented range, a test of the default and a test at each boundary. A setting that no named user needs is not added | Replay limits arrive with replay, with defaults |
| 6 | New capability goes to an extension point or an optional module before it goes into the core | Sealed audit manifests are delivered through an extension point |

## How the principles are used

| Situation | What happens |
|---|---|
| A feature request needs a scripting engine in expressions | Declined under principle 4; see [[Contributing]] |
| A host wants to run the engine as a shared service for several applications | Declined under principle 1; the host exposes the REST layer instead (ADR-0004) |
| A customer asks for its own fee codes, roles or holiday calendar in the engine | Declined as engine code under principle 13; it becomes a definition, a setting or a host bean ([[Configuration and Extension Points]]) |
| A requirement cannot be verified | It is rewritten until it can; see [[Technical Requirements]] |
| Two principles conflict in a concrete case | The maintainers record the decision as an ADR, which may amend a principle |
