# ADR-0012: Process definition lifecycle with maker-checker

- Status: accepted
- Date: 2026-11-18
- Deciders: product owner (PD-033, PD-036, PD-040), maintainers
- Module: process-engine
- Amends: ADR-0010 (permission list)

## Context
Process definitions were deployed directly with `processes:deploy`: one call made a version active. Rule sets already go through draft, request, approval by another identity, effective date and retirement, enforced in the service layer (`TR-SEC-04`). Principle 6 now covers process definitions too (PD-040), the product owner decided the same lifecycle for processes in M2 (PD-033), and definitions may arrive both from Git and from the cockpit (PD-036). The deploy contract changes shape, so it must change before adopters call it in M2 and before the 1.0 freeze.

## Decision
A process definition version moves `DRAFT -> PENDING_APPROVAL -> ACTIVE -> RETIRED`, with `:validate`, `:request-approval`, `:approve`, `:reject` and `:retire`. The requester can never approve, in the Java API or over REST (`403 SELF_APPROVAL_FORBIDDEN`). Approval takes an effective date; the effective version of a key is the highest approved version whose `effective_from <= now()`, and the previous one is retired by a scheduled job at that time. Both paths create drafts: `POST /definitions` and the start-up classpath import (permission `processes:deploy`, the Git and pipeline path) and the cockpit's draft endpoints (permission `processes:author`). The content hash (SHA-256 of the RFC 8785 form of the document without its `diagram` part) is exposed on every version. A host setting `horus.bpm.processes.authoring.cockpit-drafts` (default `true`) turns off draft creation and editing through `processes:author` per environment. Only the `dev` profile may auto-approve; outside it the starter refuses to start with `definitions-auto-approve: true` or `maker-checker.enabled: false`. The approver sees a graph diff (nodes and edges added, removed or changed).

Permissions, amending ADR-0010: `processes:author` (create and edit drafts, request approval, save layout, read the step catalogue) and `processes:approve` (approve, reject, retire) are added; `processes:deploy` now means "import a complete definition as a draft". Events: `processes.definition.publish-requested.v1`, `.approved.v1`, `.rejected.v1`, `.retired.v1`; `processes.definition.deployed.v1` is removed (no release has shipped).

## Options considered
1. Keep direct deploy; governance stays with the host's release process. No work; principle 6 stays rules-only, a business user can never change a process safely from the cockpit, and the deploy contract freezes at 1.0 without a lifecycle.
2. The rule engine's lifecycle applied to processes, in the engine API in M2, screens in M3 (chosen). One governance model across both engines; the deploy contract changes once, before adopters depend on it; M2 approvals happen through REST or host tooling until the M3 screens.
3. Lifecycle in M3 with the screens. Less M2 work; the deploy API changes shape in the milestone that freezes the API, after M2 adopters started using it.
4. Git as the only source, cockpit read-only (PD-036 option 1). One truth; the modeller has no purpose in production and the bank's change policy cannot differ per environment.

Within option 2, how to separate the Git path from cockpit authoring:
- a. A request header naming the client. Trivially spoofed; not a control.
- b. A separate permission per path (chosen). `processes:deploy` for complete imports, `processes:author` for interactive drafts; a bank grants `processes:deploy` in production only to its pipeline identity, and the host setting turns `processes:author` drafts off.

## Consequences
- `pre_definition.status` gains `DRAFT` and `PENDING_APPROVAL`, plus approval columns, `lock_version` and a `CHECK` that the approver differs from the requester (process-engine DESIGN section 7).
- One governance story for rules and processes: one approval queue for the Approver persona, the same self-approval rule and the same `dev`-only relaxation.
- A pipeline promotes a change by importing a draft and requesting approval; an approver in each environment approves the promoted draft. Drift between Git and production is visible through the hash, not prevented.
- An older draft cannot be approved after a newer version is active (`409 VERSION_SUPERSEDED`); unused drafts are withdrawn with `:retire`, so the four states suffice.
- Layout is outside the lifecycle (ADR-0013): moving a box never needs approval.
- Requirements: `TR-DEF-03`, `TR-DEF-04`, `TR-SEC-04` (extended), `TR-SEC-11`, `TR-OBS-04` (extended). Verified by `PRE-018` to `PRE-025` and `PRE-037`.
- Open: whether the approver must also differ from every editor of the draft, for both engines (process-engine DESIGN open question 11.2, decision needed).
