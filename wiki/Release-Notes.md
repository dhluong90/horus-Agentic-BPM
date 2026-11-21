# Release Notes

What shipped in each release. The project has one version for every module, and one tag per release, `vX.Y.Z` (PD-087); see [[Technical Stack]] under Delivery.

| Release | Tag | Date | Modules | Notes |
|---|---|---|---|---|
| 0.1.0 | `v0.1.0` | 20 Nov 2026 | The 13 artifacts pinned by `bpm-bom` 0.1.0, all at `0.1.0`: `bpm-host-metadata`, the Rule Engine (`rule-engine-core`, `-postgres`, `-rest`, `-spring-boot-starter`), the Process Engine preview (`process-engine-core`, `-bpmn`, `-test`, `-postgres`, `-rest`, `-spring-boot-starter`), `cockpit-spring-boot-starter`, and `bpm-bom` itself | First release, published as a GitHub Release with its bill of materials and checksums |
| 0.2.0 | `v0.2.0` | 21 Nov 2026 | As 0.1.0, at `0.2.0`, plus the new `bpm-expression` (14 artifacts) | **Breaking change of a 0.x release: `Money` and the expression types moved to `bpm-expression`** (see [0.2.0](#020)). **Upgrade note (PD-104): finish a rolling upgrade before relying on approval acknowledgements.** A replica still on 0.1.x approves without asking for an acknowledgement and writes no `acknowledged_warnings` or `masked_by_default_inputs`, so until every replica runs 0.2.0 an approval can reach the decision log without the acknowledgement on the record. **Upgrade note (PD-105): a pending rule set version written before V2 has no recorded author and can no longer be approved** (`409 AUTHOR_UNKNOWN`); import the change again, have it approved, and reject or withdraw the old version. Migrations: see [0.2.0](#020) |

Every release note lists the database migrations it adds (TR-OPS-01) and the "should" requirements it meets ([[Technical Requirements]]).

## 0.3.0 (not yet released)

What `main` holds for the next release. It is still a 0.x release, so it makes no compatibility promise.

- **Migrations:** Rule Engine `V18__rle_version_retire_request` (the pending four-eyes retirement on `rle_version`: who asked, when, the reason and the confirmation), `V19__rle_decision_log_drop` (the append-only `rle_decision_log_drop` table, one row per tenant and one total row for each decision log month that retention drops) and `V20__rle_decision_correlation_index` (the case-reference index on `rle_decision`, led by tenant and correlation id). Process Engine `V13__pre_incident` (the new `pre_incident` table and three partial indexes on it; additive, nothing existing is touched).
- **Upgrade note: Rule Engine V20 is a Java migration that does not block writes, and migrations need a direct database connection.** V20 runs outside a transaction: it creates the parent index `ON ONLY rle_decision`, then builds the index on each existing partition with `CREATE INDEX CONCURRENTLY` and attaches it, so evaluations keep writing decisions while it runs. Partitions created later get the index automatically. A rerun is a no-op, and a build left invalid by an interrupted run is dropped and rebuilt. The Rule Engine migrator now takes Flyway's migration lock as a session advisory lock, as the Process Engine does, so run migrations on a direct connection, not through PgBouncer or another pooler in transaction mode. The running engine still works behind a pooler in transaction mode.
- **Agent SPI additions.** `AdapterResult.Completed.labels()` carries string labels about how an answer was produced, with the reserved keys `model`, `promptVersion`, `usage.inputTokens` and `usage.outputTokens` (constants on `Completed`) plus the host's own keys; `AdapterResult.completed(output, labels)` builds one. Labels are kept unchanged in the agent history, bounded by `agents.labels.max-count` (16), and the engine takes no decision from them; an answer over the bounds fails the attempt with `AGENT_LABELS_INVALID`. `AgentInvocation.outputSchema()` gives the adapter the step's output schema without the engine's `mask` annotations. The new `InputMasker` SPI turns a masked step input into its stored fingerprint (`hmac:<keyId>:<hex>` under the tenant's masking key, `***` without one). The test-support module adds the `AgentInvocations` and `AdapterCalls` builders, which keep compiling when a component is added to those records. See [[Guarded Agent Steps|Guarded-Agent-Steps]].
- **Behaviour change: a job that fails for good opens an incident.** When an asynchronous job fails for good (retries used up, a business error, an agent answer refused with `onViolation: fail`, or a job claimed too often), the engine opens one `OPEN` row in `pre_incident` in the same transaction, records `INCIDENT_RAISED` in history and emits `processes.incident.raised.v1`. The token stays on the step in `WAITING_ASYNC`. The incident carries its own code (`AGENT_TIMEOUT`, `AGENT_SCHEMA_INVALID`, `UNHANDLED_BUSINESS_ERROR`, `MAX_ATTEMPTS_REACHED`), the number of retries and a cause built from codes only. `IncidentService.openIncidents(instanceId)` reads them in the caller's tenant. Retry and resolve by API are not in this release; resolving will need a second person (PD-126).
- **Upgrade note (PD-130): decision log retention is off unless set.** With `horus.bpm.rules.decision-log.retention` unset, every decision is kept, nothing is dropped, start-up is not stopped, and a WARN is logged at every start-up. Set it to `keep-forever` to keep everything without the warning, or to whole months of at least 12, such as `P12M`; a shorter period, a period in days or an unknown value stops start-up naming the property. A later release will require the setting.
- **Breaking change: the host security handlers moved to a public package.** `BpmProblemAuthenticationEntryPoint` and `BpmProblemAccessDeniedHandler` are now in `io.github.dhluong90.bpm.host.security` (were `io.github.dhluong90.bpm.host.autoconfigure`). Change the imports. The module also registers one bean of each, built from `horus.bpm.security.challenge`; inject them into the host's filter chain instead of calling the constructors, so the chain and the engine endpoints send the same `WWW-Authenticate` challenge.

## 0.2.0

Released 21 Nov 2026. Still a 0.x release, so it makes no compatibility promise; the changes below need a code change in a host that uses the moved types.

- **Tag:** `v0.2.0`. Every module is at `0.2.0`.
- **Runs on:** Java 21 or 25, Spring Boot 4.x or 3.5.x, PostgreSQL 14 or later. CI tests these combinations on PostgreSQL 14, 16 and 18.
- **Migrations:** Rule Engine `V6__rle_version_history_draft_updated`, `V10__rle_decision_pinned`, `V11__rle_version_retired_at`, `V12__rle_version_four_eyes_no_exemption`, `V14__rle_setting_seen`, `V15__rle_version_history_acknowledged_warnings`, `V16__rle_masking_key_check`, `V17__rle_decision_on_behalf_of`. Process Engine `V3__pre_engine_key`, `V4__pre_task_claim`, `V6__pre_timer`, `V7__pre_definition_lifecycle`, `V8__pre_async_completion_token`, `V9__pre_timer_kind_index`, `V10__pre_task_review`. Versions V7, V8, V9 and V13 of the Rule Engine and V5 of the Process Engine are unused.

### Breaking changes

**Expressions and value types moved to `bpm-expression` (ADR-0027).** The new artifact `io.github.dhluong90.bpm:bpm-expression` holds money, the value mapping and the expression language, and depends on the JDK only. Both engines depend on it, and `process-engine-core` no longer depends on `rule-engine-core`. `bpm-bom` pins it, so a host that imports the BOM needs no new version.

| Moved from (0.1.0) | To (0.2.0) |
|---|---|
| `io.github.dhluong90.bpm.rules.api.Money` | `io.github.dhluong90.bpm.expr.api.Money` |
| `io.github.dhluong90.bpm.rules.internal.expr.*` (parser, tree, evaluator, bounded regular expressions) | `io.github.dhluong90.bpm.expr.internal.*` |

New in the same artifact: `expr.spi.ExpressionLanguage`, `CompiledExpression`, `TypeEnvironment`, `EvaluationContext`, `ValueType` and `EvaluationProfile`, the extension point both engines compile expressions through; `expr.api.ExpressionValues`, `ExpressionInvalidException` and `ExpressionEvaluationException`. To upgrade, replace the import of `Money`; nothing else in the Rule Engine API changes.

**A host with the Process Engine alone.** The process starter declares `rule-engine-core` as optional. Without it, gateway conditions still run, and activating a definition with a business rule task is refused with `422 VALIDATION_FAILED` and the problem `BPMN_NOT_EXECUTABLE`, naming the step (was `RULE_ENGINE_MISSING`).

### Decision steps of a process

A business rule task is evaluated as the engine identity `system:process-engine`, on behalf of the user who triggered the step (none after a timer), in the instance's tenant, at the engine clock of the step, with an idempotency key derived from the instance, the step and the attempt. The task worker needs no `rules:evaluate`. A retried step replays the logged decision and writes no second row. The decision log shows the user as `onBehalfOf` (Java `DecisionLogEntry` and `LoggedDecision`, and the REST decision log). Migration `V17__rle_decision_on_behalf_of` adds the nullable column `rle_decision.on_behalf_of`.

### Business errors, late results and agent memory

**Behaviour change:** a local handler's business error no longer fails the call with `STEP_FAILED`. The call succeeds, the step waits, and an incident `UNHANDLED_BUSINESS_ERROR` opens with the business code (#55). An agent or remote step's business error fails its job on the first attempt.

A result that arrives after its attempt timed out, after another attempt took over, after the step completed or after the job failed is discarded, writes nothing and never reopens the step.

An agent step can declare `<horus:memory>` to receive declared fields of earlier agent steps' results in the same instance (#101). Process Engine migration `V14__pre_step_result` adds one table. New settings: `agents.memory.max-entries` (16) and `agents.memory.max-bytes` (16384). `AgentInvocation` gains the `memory()` component.

## 0.1.0

The first release: the Rule Engine with four-eyes publication, the embedded cockpit to edit and approve rules, and an example host that starts with one command. It is a 0.x release, so it makes no compatibility promise; 1.0 is the release with a frozen API.

- **Tag:** `v0.1.0`, one tag for the whole project (PD-087). Every module is at `0.1.0`.
- **Coordinates:** group id `io.github.dhluong90.bpm`. Import `bpm-bom` and add the starters without versions.
- **Distribution:** the GitHub Release of the tag. Maven Central follows once the namespace is registered.
- **Runs on:** Java 21 or 25, Spring Boot 4.x or 3.5.x, PostgreSQL 14 or later. CI tests these combinations on PostgreSQL 14 and 18.

### Shipped

**Rule Engine**

| Area | What you get |
|---|---|
| Lifecycle | Import a rule set as a draft, request publication, then approve, or reject with a reason. Evaluation uses the published version. Every transition writes an append-only history row (`GET .../versions/{v}/history`) and an outbox event |
| Four eyes | The approver is neither the requester nor the author of the version. Self-approval and self-reject answer `403 SELF_APPROVAL_FORBIDDEN`, through the Java API and through REST |
| Hit policies | `first`, `unique` and `collect`, as DMN 1.5 defines them. `unique` with several matches answers `422 UNIQUE_HIT_VIOLATION` with the matched rule ids and no outputs. `collect` returns lists aligned with the matched rules, or `sum`, `min`, `max` and `count`; money sums are exact and in one currency |
| Evaluation | Runs in the caller's transaction and writes one `rle_decision` row per decision. The tenant comes from the authenticated caller, never from a header or a body. Idempotency keys on import and evaluate; a reused key with another body answers `422 IDEMPOTENCY_KEY_REUSE_MISMATCH` |
| DMN 1.5 | Import a DMN 1.2 to 1.5 file with one decision table (`Content-Type: application/xml`, `text/xml` or `application/dmn+xml`); export any version with `?format=dmn`. A FEEL subset of unary tests and literals is supported; anything outside it is refused with its line. The same behaviour gives the same canonical hash from YAML and from DMN. Exports use the extension namespace `urn:horus-bpm:dmn:extensions:v1` |
| Editor API | Lists of rule sets and versions with cursors, version history, `allowedActions` per caller, and validation errors placed on the rule id and column. A `money` output without a currency is refused at import |

Reference: [[Rule Engine Decision API|Rule-Engine-Decision-API]] and [[Rule Engine Configuration|Rule-Engine-Configuration]].

**Cockpit**

| Area | What you get |
|---|---|
| Shell | Served by your host at `/bpm/cockpit` behind your own sign-in. The header shows host, environment, tenant and user. CSRF protection on every change, security headers, and errors that show a code and a `correlationId` |
| Home | The landing page for every role: pending approvals and recent rule sets |
| Approvals | Versions waiting for a second person, with Review offered only where the engine allows the caller to act |
| Rules | Rule set list, the decision table, a draft editor for cells and rows, four-eyes approval, and Try it to evaluate sample inputs. A saved draft still needs a second person to approve it. The draft editor is off by default; see Known limits |

The menu also shows Decisions and Processes, labelled Preview, each with an empty state.

**Platform**

| Area | What you get |
|---|---|
| Permission seam | One access check, `EngineAccess`, decides caller, tenant and permissions for `/api/bpm/me` and every engine endpoint, so the cockpit shows only what the engines allow. `Cache-Control: no-store` and the tenant check stay on when the metadata endpoints are switched off |
| Docker Compose | `docker compose up --build --wait` builds the example host and starts it beside PostgreSQL 16. The container runs as a non-root user on a read-only file system with all capabilities dropped, bound to 127.0.0.1, with secrets only from `.env`. For local evaluation; see [Deploying with Docker Compose](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/docs/DEPLOYING.md) |
| Release packaging | Built from a clean checkout of the tagged commit, with the cockpit bundle built from source. The GitHub Release carries the jars and POMs, a merged CycloneDX 1.6 bill of materials (Java and npm packages), `BUILD-INFO.json` and `SHA256SUMS`. See [Releasing](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/docs/RELEASING.md) |

Check your downloads in the folder you saved them to:

```bash
while read -r hash path; do name="${path##*/}"; [ -f "$name" ] && printf '%s  %s\n' "$hash" "$name"; done < SHA256SUMS | shasum -a 256 -c -
```

Each file prints `<name>: OK`.

### Preview: the Process Engine

The Process Engine is a preview in 0.1.0, for evaluation on the `dev` profile (PD-082, process engine DESIGN section 5.8).

- It imports a first BPMN 2.0 subset: none start and end events, `serviceTask` (a local handler), `businessRuleTask`, `userTask` and `exclusiveGateway`. Every other element is refused by name. See [[Process Engine: import a BPMN file|Process-Engine-Importing-BPMN]].
- Outside `dev`, an imported process stays a `DRAFT`. The approval flow comes later, so nothing in 0.1.0 can activate it there.
- On `dev` only, `horus.bpm.processes.definitions.auto-approve: true` activates imports. Start-up refuses this setting under any other profile.
- Events are handed to your `ProcessEventPublisher` inside the transaction of the state change. They are durable only if your listener writes an outbox row in that transaction.

This is a first subset, not a conformance claim.

### Known limits

- **Cockpit draft editing is off by default.** Turn it on with `authoring.cockpit-drafts: true`. In 0.1.0 each save creates a new draft version; saving a draft in place comes in 0.2.0 (PD-094).
- **No claim step on process tasks.** A candidate completes a task directly with `POST /tasks/{id}:complete`. `If-Match` and `Idempotency-Key` are required, so two candidates cannot overwrite each other, and four eyes is enforced on tasks marked `fourEyes`.
- **Decision history comes in 0.2.0.** Every decision is logged in `rle_decision`, but 0.1.0 has no API or cockpit screen to read the log. The Decisions area shows an empty state.
- **Display names show logins.** Versions and approvals show the identity recorded in the audit trail, for example `lan`, not a full name. Display names come in 0.2.0 from a host extension point.
- **No withdraw.** A requester cannot withdraw their own pending request in 0.1.0; an approver rejects it instead. Withdraw comes in 0.2.0.

### Breaking change coming in 0.2.0

The first change of 0.2.0 moves `Money`, the value types and the expression language into a new library, `bpm-expression` (ADR-0027). In 0.1.0 these types live under `io.github.dhluong90.bpm.rules`; in 0.2.0 they move to `io.github.dhluong90.bpm.expr`. Java code that imports them changes its imports. Rule set documents, REST payloads and stored data do not change.

### Database migrations

All migrations are forward-only Flyway scripts in each engine's own history table. None is destructive.

| Engine | History table | Scripts |
|---|---|---|
| Rule Engine | `rle_schema_history` | `V1__rle_baseline.sql`, `V2__rle_version_created_by.sql`, `V3__rle_version_history.sql`, `V4__rle_version_history_append_only.sql`, `V5__rle_engine_key.sql` |
| Process Engine | `pre_schema_history` | `V1__pre_baseline.sql`, `V2__pre_idempotency_claim.sql` |

`V4` adds triggers that refuse `UPDATE`, `DELETE` and `TRUNCATE` on `rle_version_history`. If your host separates the migration role from the runtime role, also revoke those rights from the runtime role. `V5` creates the list-cursor key table; the runtime role needs `SELECT` and `INSERT` on `rle_engine_key`.

### Upgrade notes

0.1.0 is the first release. These notes apply if you ran a build of `main` before it.

- **Rolling upgrade and lifecycle history (#152).** `V3` creates the history table and gives every existing version one `CREATED` row. While old replicas still run, they keep serving request-publish and approve but write no history rows, so those transitions are missing from `GET .../history` for good. If that matters, hold imports, requests and approvals until every replica runs 0.1.0. Evaluation is unaffected.
- **DMN extension namespace (#186).** Exports write `urn:horus-bpm:dmn:extensions:v1`. A file with the earlier spelling `urn:horus-bpm:dmn:extensions:1` is refused at import with its line and the replacement. Change the namespace in the file and import again.
- **Coordinates and packages.** The group id and Java packages are `io.github.dhluong90.bpm`. Builds of `main` before the rename used `com.horus.bpm`; change your dependencies and imports.
