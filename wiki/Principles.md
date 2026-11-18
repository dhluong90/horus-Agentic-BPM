# Design Principles

Twelve rules that decide trade-offs. A proposal that conflicts with one either changes or amends the principle through an ADR.

| # | Principle | Therefore |
|---|---|---|
| 1 | **A library, never a server.** Each engine runs inside the host JVM. | No cluster, no network hop on evaluation, no separate identity; the host's deployment is the engine's deployment. |
| 2 | **The host owns the data.** State lives in the host's PostgreSQL under a prefixed schema. | One database to operate; engine and business data commit in one transaction; nothing leaves the customer's service. |
| 3 | **Deterministic by construction.** Same version, inputs and clock give the same result. No I/O in the evaluator. | Decisions replay and explain years later; simulations are trustworthy; tests are simple. |
| 4 | **No arbitrary code in definitions.** Expressions are data parsed into a closed set of operations. | Business users change behaviour without opening a security hole; definitions review like documents. |
| 5 | **Everything versioned, nothing rewritten.** Published definitions are immutable; instances pin their version; logs are append-only. | Audits reconstruct the past exactly; a change never breaks work in flight. |
| 6 | **Governance built in.** Draft, simulate, request, approve by another identity, effective date, retire, enforced in the engine. | Risk and compliance can accept business-user autonomy. |
| 7 | **Agents are participants with obligations.** Bounded inputs, schema-validated output, guardrail rules, timeout, audit record. The engine never embeds a model. | Autonomy inside rails that can be read, tested and approved. |
| 8 | **Small surface, stable contracts.** Few concepts, few endpoints, semver per module, deprecations overlap one minor release. | Upgrades are routine; documentation can be complete. |
| 9 | **Standards as vocabulary, not compliance.** BPMN and DMN words, a documented subset of their semantics. | The engines stay small; what is supported is exactly what is documented. |
| 10 | **Operable from day one.** History, decision log, metrics, traces, incidents with retry; the cockpit is part of the product. | The people who run the host can run the engines. |
| 11 | **Permissive dependencies, explicit licence.** Apache 2.0, MIT, BSD only; SBOM per release. | Legal review of the engines is a one-time exercise. |
| 12 | **Documentation precedes code.** Behaviour is written here and in module specifications first, and changes with the code. | The design is reviewed first; docs never lag. |
