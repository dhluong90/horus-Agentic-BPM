# PRD: Cockpit

| Field | Value |
|---|---|
| Type | Product requirements |
| Owner area | Product |
| Last reviewed | 2026-11-18 |

Design preview. Behaviour is specified in [`cockpit/docs/SPEC.md`](../../cockpit/docs/SPEC.md) (`CKP-*`); release requirements in [requirements.md](requirements.md) (`TR-*`); milestone dates in the [product roadmap](roadmap.md); feature scope and status in the [feature map](feature-map.md); decisions in the [decision log](decisions.md). Cockpit 0.1 ships in M1, Cockpit 0.2 in M2.

## 1. Summary

A React application built to static assets that lets people act on both engines: review and approve rule changes, simulate, investigate decisions, and from 0.2 edit tables and operate processes and agent steps. It has no backend, database or login of its own; it calls the REST layers the host exposes, under the host's authentication ([ADR-0005](../adr/0005-cockpit-as-static-assets.md)). It is part of the product because principle 10 requires the engines to be operable from day one.

## 2. Maintainers

The maintainers own this PRD and decide its scope. Proposals arrive as issues labelled `type:proposal`.

## 3. Background

Governance that only exists in an API is used by developers, not by the people it is meant for. An approver must see what changed before approving; an auditor must find a decision without SQL; an operator must retry a failed step without a script. Building these screens per host repeats work and lets each host enforce controls differently. A shipped cockpit that only reflects engine-enforced rules (maker-checker, masking, tenancy) gives every host the same screens without moving any control into the browser.

## 4. Objective

Let non-developers perform the governance and operations tasks of both engines without leaving the cockpit or bypassing an engine control.

| # | Key result | Target | Source |
|---|---|---|---|
| KR1 | Approval without developer help | An approver who did not author a change reviews the diff and a sample simulation and approves or rejects it in Cockpit 0.1, with no REST call by hand | `TR-UI-02` (review and approve part), CKP-002 to CKP-004 |
| KR2 | Operations from the cockpit | An operator inspects an escalated `agentTask` and retries an incident in Cockpit 0.2 | CKP-007, CKP-008, `TR-OPS-03` |
| KR3 | Usable by every target user | Zero serious or critical WCAG 2.1 AA violations; English and Vietnamese with no missing keys | CKP-009, CKP-010 (both "should" in TR) |

## 5. Segments by job (personas)

| Persona | Job | Permissions |
|---|---|---|
| Author | Prepare a rule change and show its effect | `rules:read`, `rules:author`, `rules:simulate` |
| Approver | Decide whether someone else's change goes live | `rules:read`, `rules:approve`, `rules:simulate` to run a simulation; from 0.2 `processes:task:work` on approval tasks |
| Operator | Keep instances moving: queues, SLAs, incidents, calendars | `processes:read`, `processes:task:manage`, `processes:incident:manage`, `processes:calendar:manage` (0.2); permission names per [PD-003](decisions.md#pd-003-permission-names-are-moduleaction) |
| Auditor | Reconstruct a past decision or step and who acted | `rules:log:read`, `processes:read` |

## 6. Value propositions

| Part | Author | Approver | Operator | Auditor |
|---|---|---|---|---|
| Why | Show a change is correct before it goes live | Approve only what they understand | Stuck work is invisible until a customer calls | Evidence must come from the system, not from memory |
| What before | Email a spreadsheet; ask a developer to run tests | Approve a ticket without seeing the rows | SQL against engine tables; log searches | Screenshots and change tickets |
| How | 0.1: view the imported draft, simulate sample inputs, request publication. 0.2: grid editor with inline validation, replay of logged decisions | Side-by-side diff; Approve disabled for the requester; reject needs a comment | 0.2: task queues, incident queue with retry and resolve, agent task inspector, calendar editor | Decision explorer with matched row highlighted, explanation, masked inputs |
| What after | Changes arrive with evidence of their effect | Self-approval impossible in UI and API | A failure is an incident with cause, retry count and an action | A decision is explained in one screen |
| Alternatives | Host-built admin pages; YAML in a pull request | Approval in a ticket tool, disconnected from the engine | BPM vendor operations tools, which need that vendor's runtime | Database exports |

Statement: for the people who govern and run the engines, the cockpit puts approvals, simulation, decision investigation and incident handling in one static UI that cannot weaken an engine control.

## 7. Solution

### 7.1 User journeys

| Persona | Journey (0.1 unless marked) | Hand-off |
|---|---|---|
| Author | Open rule set → view imported draft v2 → paste five sample rows → see two changed outcomes → request publication | Unblocks the approver |
| Approver | Open Approvals → read diff (R2 changed, R6 added) → approve, or reject with a comment | Approve publishes; reject returns the draft to the author |
| Operator (0.2) | Open Incidents → read `AGENT_TIMEOUT`, retry count 2 → Retry; on `409` reload and retry | Unblocks the instance |
| Approver (0.2) | Open escalated agent task → read masked inputs, schema verdict, output, fired rule R2 → approve or override with a reason | Override recorded for evaluation |
| Auditor | Filter decisions by rule set and period → open one → see version, matched row, explanation; masked fields show as hash | None |

### 7.2 Must-have capabilities by platform layer

The cockpit lives in the application layer. Data, logic and observability belong to the engines; the cockpit only renders them.

| Layer | Capability | Version | SPEC | TR |
|---|---|---|---|---|
| Application | Boot embedded or standalone from one base URL | 0.1 | CKP-001 | TR-UI-01, TR-SEC-09 |
| Application | Version diff | 0.1 | CKP-002 | TR-UI-02 |
| Application | Maker-checker reflected; API `403` surfaced | 0.1 | CKP-003 | TR-SEC-04 |
| Application | Simulation over sample inputs | 0.1 | CKP-004 | TR-UI-02 |
| Application | Decision explorer with masking and explanation | 0.1 | CKP-005 | TR-SEC-05, TR-OBS-05 |
| Application | Grid editor with inline validation | 0.2 | CKP-006 | TR-DET-02, TR-UI-02 |
| Application | Agent task inspector | 0.2 | CKP-007 | TR-AGT-01, TR-AGT-02 |
| Application | Incident queue with retry and resolve | 0.2 | CKP-008 | TR-OPS-03 |
| Application | Replay simulation | 0.2 | CKP-011 | TR-UI-02 |
| Application | Accessibility; English and Vietnamese | 0.1, re-run 0.2 | CKP-009, CKP-010 | TR-UI-03, TR-UI-04 |
| API (consumed) | Problem Details errors shown with `correlationId`; idempotency key and `If-Match` on every mutation | 0.1 | CKP-008 (0.2 only) | TR-SEC-08, TR-IDEM-01 |

### 7.3 Specification gaps

| Capability | Version | Source |
|---|---|---|
| Rule sets list, version view, import and export | 0.1 | Roadmap M1 |
| Process definitions, instance view with history timeline | 0.2 | Roadmap M2 |
| Human task queues: claim, complete, reassign | 0.2 | Roadmap M2 |
| Compensation runs view and SLA breach list | 0.2 | wiki Cockpit page; scope pending as PD-024 |
| Business calendar editor | 0.2 | Roadmap M2, `TR-CAL-01` |
| Instance migration screen | M3 | Roadmap M3, `TR-MIG-03`; wiki says 0.2, pending as PD-023 |
| Simulation report attached to a publication request | M3 | Roadmap M3 |
| Tier-1 pulse strip of KPIs on landing views | not scheduled | `cockpit/docs/DESIGN.md` section 3 only; pending as PD-016 |

### 7.4 Open questions

- `TR-UI-02` requires authoring "without leaving the cockpit" and has no milestone. Is 0.1 authoring by YAML import in the cockpit enough, or does `TR-UI-02` gate only from M2? Pending as [PD-013](decisions.md#pd-013-from-which-milestone-tr-ui-02-gates).
- Standalone mode: one host per URL, or an environment switcher? (DESIGN open question, gate M1 start.)
- Proposal: add the pulse strip to a milestone, or remove it from DESIGN. Pending as [PD-016](decisions.md#pd-016-cockpit-pulse-strip).

## 8. Release and scope

| Cockpit 0.1 (M1): viewer and approver | Cockpit 0.2 (M2): editor and operations |
|---|---|
| Rule sets, version view, import and export | Grid editor with type checks and expression validation |
| Diff, sample simulation, approvals, decision explorer with explanation | Replay simulation |
| Embedded and standalone hosting | Process definitions, instances, task queues, incidents, compensation runs, SLA breaches, calendar editor |
| | Agent task inspector with approve or override |

M3: migration screen; simulation reports on publication requests. After 1.0 (candidate): hosted cockpit.

Non-goals: a backend-for-frontend, database or user store ([ADR-0005](../adr/0005-cockpit-as-static-assets.md)); any authorisation decided in the browser; a BPMN diagram modeller; dashboards that replace the host's monitoring (metrics stay in Micrometer, `TR-OBS-02`); editing in 0.1.

## Red-team

| # | Assumption | Fails if | Evidence | Kill criterion | Cheapest test |
|---|---|---|---|---|---|
| 1 | Approvers will approve in the cockpit | Compliance teams require approvals in their existing ticket or workflow tool | Ask three compliance contacts where they approve changes today and whether a second tool is acceptable | Two of three refuse a second approval tool | Three conversations in M1. If killed, document approval through the REST API and the `rules.version.publish-requested.v1` event as the primary path; keep the cockpit screen |
| 2 | Diff plus sample simulation is enough to approve in 0.1 | Approvers refuse to approve without the effect on real traffic, which arrives only with replay in 0.2 | Moderated session: an approver reviews a 20-row change with diff and five samples | The approver cannot state who is affected, or defers the decision | One session in week 3 of M1. If killed, move replay simulation (RLE-015, CKP-011) into M1 and cut REST filters (PD-018) |
| 3 | A stateless cockpit is enough for operators | Operators need notifications of breaches and new incidents, which a static UI cannot push | Ask two operations leads how they learn of a stuck case today | Both need push alerts from the BPM tool itself | Two conversations before M2. If killed, document alerting on engine metrics and events in the host's monitoring; keep the cockpit stateless |
