# Design Principles

Twelve rules that decide trade-offs. A proposal that conflicts with one either changes, or amends the principle through an ADR in `docs/adr/`.

Design preview: the principles are in force now; the behaviour they describe is the 0.1 target.

| # | Principle | Therefore |
|---|---|---|
| 1 | A library, never a server. Each engine runs inside the host JVM (ADR-0004). | No cluster, no network hop on evaluation, no separate identity. The host's deployment is the engine's deployment. |
| 2 | The host owns the data. State lives in the host's PostgreSQL under a prefixed schema (ADR-0002). | One database to operate. Engine and business data commit in one transaction. Nothing leaves the host's service. |
| 3 | Deterministic by construction. Same version, inputs and clock give the same result. The evaluator performs no I/O. | Decisions replay and explain years later. Simulations are trustworthy. Tests need no mocks. |
| 4 | No arbitrary code in definitions. Expressions are data parsed into a closed set of operations (ADR-0003). | Business users change behaviour without opening a security hole. Definitions review like documents. |
| 5 | Everything versioned, nothing rewritten. Published definitions are immutable, instances pin their version, logs are append-only. | Audits reconstruct the past exactly. A change never breaks work in flight. |
| 6 | Governance built in. Draft, simulate, request, approve by another identity, effective date, retire. The engine enforces each step. | Risk and compliance can accept business-user autonomy. |
| 7 | Agents are participants with obligations. Bounded inputs, schema-validated output, guardrail rules, timeout, audit record. The engine never embeds a model. | Autonomy inside rails that people can read, test and approve. |
| 8 | Small surface, stable contracts. Few concepts, few endpoints, semver per module, deprecations overlap one minor release. | Upgrades are routine. Documentation can be complete. |
| 9 | Standards as vocabulary, not compliance. BPMN and DMN words, a documented subset of their semantics. | The engines stay small. What is supported is exactly what is documented. |
| 10 | Operable from day one. History, decision log, metrics, traces, incidents with retry. The cockpit is part of the product (ADR-0005). | The people who run the host can run the engines. |
| 11 | Permissive dependencies, explicit licence. Apache 2.0, MIT and BSD only, SBOM per release. | Legal review of the engines is a one-time exercise. |
| 12 | Documentation precedes code. Behaviour is written in this wiki and in module specifications first, and changes with the code. | The design is reviewed before it is built. Docs never lag. |

## How the principles are used

| Situation | What happens |
|---|---|
| A feature request needs a scripting engine in expressions | Declined under principle 4; see [[Contributing]] |
| A host wants to run the engine as a shared service for several applications | Declined under principle 1; the host exposes the REST layer instead (ADR-0004) |
| A requirement cannot be verified | It is rewritten until it can; see [[Technical Requirements]] |
| Two principles conflict in a concrete case | The maintainers record the decision as an ADR, which may amend a principle |
