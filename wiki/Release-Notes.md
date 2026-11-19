# Release Notes

What shipped in each release. The project has one version for every module, and one tag per release, `vX.Y.Z` (PD-087); see [[Technical Stack]] under Delivery.

| Release | Tag | Date | Modules | Notes |
|---|---|---|---|---|
| 0.1.0 | `v0.1.0` | 22 Nov 2026 (planned) | The modules pinned by `bpm-bom` on the tagged commit, all at `0.1.0`: today `bpm-host-metadata`, `rule-engine-core`, `rule-engine-postgres`, `rule-engine-rest`, `rule-engine-spring-boot-starter`, `cockpit-spring-boot-starter`, and `bpm-bom` itself | First release, published as a GitHub Release with its bill of materials and checksums |

Every release note lists the database migrations it adds (TR-OPS-01) and the "should" requirements it meets ([[Technical Requirements]]).

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

`V4` adds triggers that refuse `UPDATE`, `DELETE` and `TRUNCATE` on `rle_version_history`. If your host separates the migration role from the runtime role, also revoke those rights from the runtime role. `V5` creates the list-cursor key table; the runtime role needs `SELECT` and `INSERT` on `rle_engine_key`.

### Upgrade notes

0.1.0 is the first release. These notes apply if you ran a build of `main` before it.

- **Rolling upgrade and lifecycle history (#152).** `V3` creates the history table and gives every existing version one `CREATED` row. While old replicas still run, they keep serving request-publish and approve but write no history rows, so those transitions are missing from `GET .../history` for good. If that matters, hold imports, requests and approvals until every replica runs 0.1.0. Evaluation is unaffected.
- **DMN extension namespace (#186).** Exports write `urn:horus-bpm:dmn:extensions:v1`. A file with the earlier spelling `urn:horus-bpm:dmn:extensions:1` is refused at import with its line and the replacement. Change the namespace in the file and import again.
- **Coordinates and packages.** The group id and Java packages are `io.github.dhluong90.bpm`. Builds of `main` before the rename used `com.horus.bpm`; change your dependencies and imports.
