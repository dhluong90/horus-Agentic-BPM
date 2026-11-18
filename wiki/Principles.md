# Design Principles

These principles decide trade-offs. When a proposal conflicts with one, the proposal changes or the principle is amended through an ADR.

## 1. A library, never a server
Each engine is a dependency of the host service and runs in its JVM. There is no engine process to deploy, scale, patch or secure separately. **Therefore:** no network hop on the evaluation path, no cluster coordination, no separate identity system; the host's deployment model is the engine's deployment model.

## 2. The host owns the data
State lives in the host's PostgreSQL under a prefixed schema, migrated by the engine, backed up and governed by the host. **Therefore:** one database to operate, transactions can span business data and engine data, and nothing about a customer's data leaves their service.

## 3. Deterministic by construction
Same definition version, same inputs, same clock give the same result, always. The evaluator performs no I/O and the clock is an explicit input. **Therefore:** decisions can be replayed and explained years later, simulations are trustworthy, and tests are simple.

## 4. No arbitrary code in definitions
Rule expressions and process definitions are data, parsed into a closed set of operations. No scripting engine, no reflection, no class loading, no network. **Therefore:** a business user can change behaviour without creating a security hole, and a definition can be reviewed like a document.

## 5. Everything versioned, nothing rewritten
Definitions are immutable once published; running instances pin the version they started on; logs and history are append-only. **Therefore:** audits reconstruct the past exactly, and a change never breaks work in flight.

## 6. Governance built in, not bolted on
Draft, simulate, request, approve by a different identity, effective date, retire. Maker-checker is enforced in the engine, so it holds for every client, UI or API. **Therefore:** risk and compliance teams can accept business-user autonomy.

## 7. Agents are participants, with the same obligations as services
An AI agent performing a step receives bounded inputs, returns output that is validated against a schema and a rule set, runs under a timeout, and leaves an audit record. The engine never embeds a model; adapters live in the host. **Therefore:** autonomy exists inside rails that can be read, tested and approved.

## 8. Small surface, stable contracts
Few concepts, few endpoints, semantic versioning per module, deprecation with at least one minor release of overlap. **Therefore:** upgrades are routine, and the documentation can be complete.

## 9. Standards as vocabulary, not as a compliance target
BPMN and DMN words (user task, gateway, hit policy, decision table) are used so that practitioners understand immediately. Full BPMN 2.0 or DMN FEEL compliance is not a goal; a documented subset is. **Therefore:** the engines stay small, and what is supported is exactly what is documented.

## 10. Operable from day one
Every state change is observable: history, decision log, metrics, traces, incidents with retry. The cockpit is part of the product, not an add-on. **Therefore:** the people who run the host service can run the engines without new skills.

## 11. Permissive dependencies, explicit licence
Only Apache 2.0, MIT or BSD third-party code; an SBOM with every release; the project's own licence stated in every artifact. **Therefore:** legal review of the engines is a one-time exercise for an adopter.

## 12. Documentation precedes code
Public behaviour is written in this wiki and in module specifications before it is implemented, and changes in the same pull request as the code. **Therefore:** the design is reviewed first and the docs never lag.
