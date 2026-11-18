# Concepts

This page defines the terms used everywhere else in the wiki. Other pages link here instead of redefining them.

Design preview: describes the 0.1 target (Rule Engine) and the Process Engine 0.1 target (Process Engine terms).

## Rule Engine terms

| Term | Meaning |
|---|---|
| Rule set | A named decision, for example `loan-eligibility`, with typed inputs and outputs. The unit that callers evaluate. |
| Version | An immutable snapshot of a rule set's rules. A version is in one of four states: draft, pending approval, published, retired (see below). At most one version of a rule set is effective per tenant at any instant. |
| Rule | One row of a decision table: a `when` (one condition per input) and a `then` (one value per output). Each row has a stable id such as `R2`. |
| Hit policy | How the engine combines several matching rows. `first`: the first match in row order wins. `unique`: exactly one row must match, otherwise the decision fails. `collect`: every matching row contributes, and outputs become lists or aggregates. |
| Expression | A condition or output value written in the expression language. It is data, parsed at publish time into a closed set of operations; it cannot call code. Reference: [[Rule Engine Expression Language]]. |
| Clock | The instant a decision reasons about. It selects the effective version and feeds `today()` and `now()`. It defaults to the system time and can be set explicitly for simulation and replay. |
| Decision | One evaluation of a rule set version against one set of inputs. It produces outputs, the matched rule ids and one record in the decision log. |
| Decision log | The append-only record of every decision: tenant, rule set, version, inputs, matched rules, outputs, caller, clock. It is the audit trail. How-to: [[Rule Engine Decision Log and Audit]]. |
| Effective date | The `effectiveFrom` instant from which a published version decides. A version with a future date is scheduled and becomes effective without any further action. |
| Maker-checker | Publishing a version needs two identities: one requests, a different one approves. The engine enforces it; the cockpit only reflects it. |
| Simulation | Evaluating a draft version against sample inputs, or against past decisions of the effective version, to see what would change. A simulation never writes to the decision log. |
| Masking | Marking an input `mask: true` so the decision log stores a keyed hash instead of the value. The hash still allows exact-match search. |
| Tenant | The isolation boundary. Rule sets, versions and decisions always belong to exactly one tenant; there is no cross-tenant read. |
| Idempotency key | A caller-chosen key on a mutating call. A replay with the same key inside the retention window returns the original result and has no second effect (ADR-0006). |

## Lifecycle of a version

![Version lifecycle](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/version-lifecycle.svg)

| State | Editable | Evaluable | Leaves the state when |
|---|---|---|---|
| Draft | Yes | Only by simulation | An author requests publication |
| Pending approval | No | Only by simulation | A second identity approves (to published) or rejects (back to draft) |
| Published | No | Yes, from its effective date | A later version becomes effective, or an approver retires it |
| Retired | No | No, but it still explains the decisions it made | Never; history is not rewritten |

The decision log records the version each decision used, so a retired version can still explain a past decision.

## Process Engine terms

| Term | Meaning |
|---|---|
| Process definition | A versioned description of steps (tasks, gateways, timers) that an instance moves through. Running instances pin the version they started with. Every definition has a diagram view (see Diagram). |
| Diagram | The graphical view of a process definition or instance. Every definition has one; when no layout is stored, the cockpit lays it out automatically. The layout is stored apart from the definition, so moving a box changes no behaviour and needs no approval. An instance's diagram shows its current tokens. |
| Token | A marker for where an instance is in its definition. An instance has one token per active path, so parallel branches hold several tokens at once. |
| Process instance | One execution of a definition, for example one loan application. |
| Task | A step that waits for a participant. A user task waits for a person, a service task for host code, an agent task for an AI agent. |
| Agent task (`agentTask`) | A step performed by an AI agent through an adapter the host registers. The agent receives bounded inputs and must return output that validates against a JSON Schema and passes a guardrail rule set; otherwise the engine routes the step to a human or fails it. The engine never embeds a model. |
| Guardrail | A rule set that the engine evaluates on an agent's output before the process continues. |
| Business key | A caller-chosen identifier for an instance, unique per tenant and definition, for example a loan application number. Starting again with the same key returns the existing instance. |
| Incident | A failed step with its cause, retry count and the actions an operator can take (retry, resolve). |
| Compensation | Declared undo handlers that run in reverse order when a later step fails after earlier steps committed effects in other systems (ADR-0007). |
| Saga | A block of steps that compensate together. |
| Business calendar | Working hours, holidays and time zone per tenant, used to compute SLA deadlines such as 4 business hours. Supplied through an SPI. |
| Instance migration | Moving running instances from one definition version to another with a validated step mapping. |

Behaviour of these terms is described in [[Process Engine Overview]].

## What the engines are not

Not a BPMN 2.0 or DMN FEEL implementation: processes use the engine's own notation, with a BPMN subset for import and export. Not a server you deploy separately, not a workflow tool for infrastructure. The reasons are in [[Principles]] and [[FAQ]].
