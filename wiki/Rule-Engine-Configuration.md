# Configuration

Reference for every `horus.bpm.rules.*` property of the Rule Engine starter and the permission strings its REST layer checks. _Design preview: the properties marked Built are read today; the rest are planned for the milestone named in their row._

## Status of each property

| Status | Meaning |
|---|---|
| Built | Read by the starter on `main`. Not yet released |
| 0.1 (M1) | Planned for Rule Engine 0.1, milestone M1, 15 Jan 2027. Setting it today has no effect |
| 0.1 (M1), may move | Planned for 0.1; the [[Roadmap]] names the milestone it moves to if M1 runs late |
| M2, M3 | Planned for that milestone (M2 26 Mar 2027, M3 21 May 2027). Setting it today has no effect |

## Properties

All names are under `horus.bpm.rules.`. Table names are fixed (`rle_*`, Flyway history `rle_schema_history`) and have no property.

| Property | Default | Status | Meaning |
|---|---|---|---|
| `enabled` | `true` | Built | Register the engine beans. The engine needs exactly one `DataSource` in the host (or one marked `@Primary`); without it the starter stays inactive and logs a warning |
| `migrate-on-start` | `true` | Built | Run the engine's Flyway migrations at start-up with the host `DataSource`; `false` for hosts that run migrations in a separate job |
| `definitions` | unset | 0.1 (M1) | Resource pattern of YAML rule sets to load as drafts at start-up |
| `definitions-tenant` | `default` | 0.1 (M1) | Tenant that owns rule sets loaded from `definitions`. A document may override it with a top-level `tenant:` field; multi-tenant hosts load per-tenant files or use the import endpoint |
| `definitions-auto-publish` | `false` | 0.1 (M1) | Publish loaded drafts without maker-checker. Refused unless the `dev` profile is active |
| `rest.enabled` | `true` | 0.1 (M1) | Expose the REST API |
| `rest.base-path` | `/api/bpm/rules/v1` | 0.1 (M1) | Base path of the REST API. Keep the version segment when you change it; the cockpit reads the same value |
| `rest.cors.allowed-origins` | empty | 0.1 (M1) | Origins allowed to call the REST API, needed only for a standalone cockpit. Empty disables CORS |
| `decision-log.enabled` | `true` | 0.1 (M1) | Write `rle_decision` rows |
| `decision-log.mode` | `transactional` | `transactional` 0.1 (M1); `async` M2 (March 2027), may move to M3 | `transactional`: one `INSERT` in the caller's transaction. `async`: insert into `rle_decision_outbox`, relayed by the scheduler thread, for high-volume hosts. Until `async` ships, setting it stops start-up with a message naming the milestone |
| `decision-log.async.batch-size` | `100` | M2, may move to M3 | Outbox rows relayed per poll in `async` mode |
| `decision-log.partitions-ahead` | `P7D` | 0.1 (M1) | How far ahead the scheduler creates monthly `rle_decision` partitions. See [Decision log partitions](#decision-log-partitions) |
| `decision-log.retention` | unset; required outside `dev` from M3 | M3 | ISO 8601 period in whole months, at least `decision-log.retention-floor`, for example `P12M` or `P120M`. From M3, outside the `dev` profile, the engine refuses to start until it is set, and refuses a value below the floor or not in whole months, naming the property. The scheduler drops whole monthly partitions that end before `now() - retention`. Nothing is deleted in the `dev` profile or before M3; before M3 the property is ignored with a start-up note. No decision row is ever deleted by date |
| `decision-log.mask-default` | `false` | 0.1 (M1) | Mask every input unless the rule set says `mask: false` |
| `masking.hmac-key` | unset | 0.1 (M1) | Base64 key, 32 bytes or more, for HMAC-SHA256 of masked inputs. Supply from an environment variable or secret store. Start-up fails if any loaded rule set masks an input and no key is set |
| `evaluation.max-rows` | `5000` | Built | Most rules a version may contain, checked at import (`422 VALIDATION_FAILED`). `1` to `5000`; may be lowered, never raised |
| `evaluation.pin-retired` | `false` | 0.2 | Whether an evaluation pinned with `rules:pin` may name a `RETIRED` version. `false` answers `409 INVALID_STATE`, so a rule taken out of service never makes a new logged decision. Replay of a logged decision is not affected |
| `evaluation.max-output-bytes` | `1048576` | Built | Estimated JSON size of one decision's outputs, checked while `collect` values accumulate. A breach fails the decision with a logged `EVALUATION_ERROR` and no outputs. `1` to `1048576`; may be lowered, never raised |
| `evaluation.max-string-length` | `65536` | Built | Longest string input (`400 INPUT_INVALID`) and longest string that `+` may build (`EVALUATION_ERROR`, checked before the string is built), in UTF-16 code units. `1` to `65536`; may be lowered, never raised |
| `evaluation.max-batch` | `1000` | M2, may move to M3 | Largest `:evaluate-batch` request |
| `simulation.max-inputs` | `1000` | Built | Most sample input sets per `:simulate` (`400 INPUT_INVALID` above it). `1` to `1000`; may be lowered, never raised |
| `evaluation.regex-timeout-ms` | `50` | 0.1 (M1) | Bounded matcher timeout; a hit fails the decision with `EVALUATION_ERROR` |
| `evaluation.regex-max-input-length` | `4096` | 0.1 (M1) | Longer inputs fail `matches()` deterministically |
| `evaluation.max-input-scale` | `34` | Built | Largest absolute scale of a number input (`1e-35` and `1e35` are refused with `400 INPUT_INVALID`; at most 34 significant digits always). `0` to `34`; may be lowered, never raised. An invalid value stops start-up |
| `evaluation.reject-undeclared-inputs` | `true` | Built | Refuse an input name the rule set does not declare with `400 INPUT_INVALID`, so a misspelt name such as `txcount` never falls to a default row. With `false`, undeclared inputs are ignored; they are never written to the decision log either way |
| `cache.max-entries` | `1000` | Built | Compiled versions kept per replica, `1` to `100000`; the cache is cleared when full (LRU eviction from 0.1). An invalid value stops start-up |
| `cache.listen-notify` | `true` | 0.1 (M1) | Open one session-mode connection for `LISTEN horus_rle_cache`; set `false` behind a transaction-mode pooler |
| `cache.epoch-check-interval` | `PT5S` | 0.1 (M1) | Interval of the `cache_epoch` sweep; this is the staleness bound when NOTIFY is unavailable |
| `idempotency.retention` | `PT24H` | Built | Lifetime of `rle_idempotency` rows, `PT1M` to `P30D`. An invalid value stops start-up |
| `query.max-range`, `query.max-page-size`, `query.statement-timeout` | `P31D`, `200`, `PT2S` | 0.1 (M1) | Bounds of `GET /decisions` and list reads; range and page size may be lowered, never raised |
| `lifecycle.sweep-interval` | `PT1M` | 0.1 (M1) | How often superseded versions are marked retired; evaluation already switches at `effectiveFrom` |
| `authoring.cockpit-drafts` | `false` | Built (published in `/meta`); refusal 0.1 (M1) | Whether the cockpit may create and edit drafts on this host; off by default in 0.1.0 (PD-094). Always published in `/api/bpm/meta` as the rules setting `authoring.cockpit-drafts`. Turn it on with `authoring.cockpit-drafts: true`, as the example host does on its `dev` profile. Import of a document, approvals and simulation are not affected. Planned for 0.1: with `false`, authors (`rules:author`) get `403 DRAFT_AUTHORING_DISABLED` when they create or edit a draft from the cockpit |
| `readings.style` | `sentence` | 0.1 (M1) | Style of the plain-language readings of rules and of explanation `text`: `sentence` (patterns from the message catalogue) or `shorthand` (for example `tier: GOLD, PLATINUM`). If sentences miss the 8 Jan 2027 checkpoint, 0.1 accepts only `shorthand` until sentences ship. Shown in `/api/bpm/meta` as `rules.readings.style` |
| `lifecycle.reason-required`, `lifecycle.reason-max-length` | `true`, `2000` | 0.1 (M1) | Reason on reject and retire; may be made optional for reject only |
| `lifecycle.system-actor` | `system` | 0.1 (M1) | Actor recorded when the engine retires a superseded version |
| `lifecycle.retire-approval` (host property `horus.bpm.lifecycle.retire-approval`, shared with the Process Engine) | `single` | 0.1 (M1); `four-eyes` M3 (May 2027), may move to 1.1 | Approval mode for retiring a published version by hand. `single`: one `rules:approve` holder retires it with a mandatory reason. `four-eyes`: `:retire` only records a request and the version keeps serving; a second `rules:approve` holder confirms with `:confirm-retire`, and the requester's own confirmation is refused with `403 SELF_APPROVAL_FORBIDDEN`; any holder can withdraw the request with `:cancel-retire` and a reason. The mode in force is shown in `/api/bpm/meta` and recorded on every retirement event and history row. Independent of the emergency single-approver path, which governs publication only. See [[Rule Engine Versioning and Publishing]] |
| `lifecycle.retire-last-version` (host property `horus.bpm.lifecycle.retire-last-version`, shared with the Process Engine) | `confirm` | 0.2 | Retiring the last version in effect of a rule set. `confirm`: the retire needs `"confirmNoVersionInEffect": true`, else `409 LAST_VERSION_IN_EFFECT`. `four-eyes`: a second approver confirms (with four-eyes retirement, M3). `refuse`: never; approve a replacement first. Shown in `/api/bpm/meta` |
| `lifecycle.approver-must-differ-from` (host property `horus.bpm.lifecycle.approver-must-differ-from`, shared with the Process Engine) | `makers` | 0.2 for the setting; `makers` behaviour built | Who may not approve a version. `makers`: nobody who imported, created, edited or requested it. `requester`: only the requester is excluded; this relaxes a control, so it is never the default, it appears in `/api/bpm/meta`, is logged at WARN at start-up and is recorded on every approval. The requester can never approve (PD-096). The Rule Engine always applies `makers`, and reports `makers` in `/meta`, because its database constraint refuses an approval by the author; `requester` affects the Process Engine only |
| `explain.default-language` | `en` | 0.1 (M1) | Language of the explanation `text` when the request asks for none, or for a language without a catalogue. English (`en`) and Vietnamese (`vi`) catalogues ship; a host can override or extend them |
| `publication.require-simulation-report` | `false` | M3 (May 2027) | `true`: `:request-publish` is refused with `422 VALIDATION_FAILED` unless a stored simulation report of the draft is attached. Applies to the whole host, or only to the rule sets listed in `publication.require-simulation-report.rule-sets`. Until M3 the approver runs the simulation herself |
| `decision-log.retention-floor` | `P12M` | M3 | Shortest `decision-log.retention` accepted. You may raise it to your own policy, never set it below `P12M` |
| `maker-checker.enabled` | `true` | 0.1 (M1) | `false` is refused unless the `dev` profile is active |
| `tenancy.claim` | `tenant_id` | Built as the host-wide `horus.bpm.tenancy.claim` | Claim or principal attribute read by the default `TenantResolver`, for a token or a server-side session login alike |
| `tenancy.dev-tenant` | unset | Built as the host-wide `horus.bpm.tenancy.dev-tenant` | Fixed tenant for local work without an identity provider. `dev` profile only: start-up is refused when it is set outside `dev` |
| `events.decision-evaluated` | `false` | 0.1 (M1) | Publish `rules.decision.evaluated.v1` for every decision |
| `scheduler.enabled` | `true` | 0.1 (M1) | Run the engine's scheduler thread (outbox relay, partition creation, partition drop, idempotency cleanup) on this replica |
| `security.role-permissions` | empty | Built | Map of host role authorities to engine permissions; see [Mapping permissions](#mapping-permissions-to-the-hosts-authorities). Read by `/api/bpm/me` today; the engine endpoints use it from 0.1. A permission outside the six below stops the start-up |

### Host-wide properties

These are under `horus.bpm.` and apply to every engine in the host. They are read by the host metadata endpoints, `/api/bpm/meta` and `/api/bpm/me` ([[Host Metadata and Permissions]]).

| Property | Default | Status | Meaning |
|---|---|---|---|
| `host-id` | unset | Built | Id clients show for this host, 1 to 40 characters; returned as `hostId` by `/api/bpm/meta` |
| `host-metadata.enabled` | `true` | Built | `false` turns off `/api/bpm/meta` and `/api/bpm/me` only. The `no-store` header on engine responses, the tenant resolver and `EngineAccess` stay on: controls are not options (ADR-0024) |
| `tenancy.claim` | `tenant_id` | Built | Attribute of the authenticated principal that the default `TenantResolver` reads: the claim of a validated token, or the attribute of a session principal from an OIDC or OAuth2 login (PD-079). Whatever the resolver, a principal with this attribute must match the resolved tenant |
| `tenancy.dev-tenant` | unset | Built | Fixed tenant for principals that carry none, for local work without an identity provider. Accepted on the `dev` profile only; start-up is refused when it is set elsewhere |

There is no header-based tenant mode: the engines never take the tenant from what the browser sends, and `tenancy.resolver` and `tenancy.trusted-gateway` stop the start-up with a message (PD-079). A host whose principal has no tenant attribute declares its own `TenantResolver` bean.
| `security.challenge` | `Bearer` | Built | `WWW-Authenticate` value of a `401` the engine endpoints raise themselves |

### Decided settings without a property name yet

These settings are decided. Their property names and ranges arrive with the specification of their milestone, and this table then moves into the one above.

| Setting | Default | Milestone | What it does |
|---|---|---|---|
| Emergency single-approver path | Off | Set by its ADR | On: a logged path lets one person publish in an emergency; every use is reviewed afterwards. Never applies to retirement |

The cockpit's landing area is the host property `horus.bpm.cockpit.landing` (default `home`; also `rules` or `approvals`), published as `cockpit.landing` in `/api/bpm/meta`; see [[Cockpit]].

### Process Engine properties decided so far

The full Process Engine list arrives with its 0.2 configuration page. These are already decided, under `horus.bpm.processes.`:

| Property | Default | Status | Meaning |
|---|---|---|---|
| `agents.labels.max-count`, `agents.labels.max-key-length`, `agents.labels.max-value-length` | `16`, `64`, `256` | M2 | Bounds of the optional labels an agent adapter reports with a result, recorded in history and never acted on. `model` and `promptVersion` are reserved keys. May be lowered, never raised |
| `authoring.cockpit-drafts` | `true` | M2 | `false` refuses creating and editing process drafts from the cockpit with `403 DRAFT_AUTHORING_DISABLED`. Imports, approvals and layout saves still work |

Properties with a prefix of `tenancy`, `masking` and `rest.cors` are shared with the Process Engine starter under `horus.bpm.processes.*`; when both engines run in one host, set them once under `horus.bpm.*`.

There is no property to turn decision log partitioning on or off. `rle_decision` is partitioned by month in every install (ADR-0010).

There is no `clock` property either. The engine's time comes from the host's `Clock` bean (see [Host beans](#host-beans-the-starter-picks-up)), and a single decision can reason about another instant through the request's `clock` field, which needs `rules:simulate` over REST. To fix time in tests, declare a fixed `Clock` bean, for example `Clock.fixed(Instant.parse("2026-12-01T00:00:00Z"), ZoneOffset.UTC)`.

### What the first build reads

```yaml
horus:
  bpm:
    host-id: loans                 # optional
    rules:
      enabled: true                # default
      migrate-on-start: true       # default
      security:
        role-permissions:
          ROLE_PRICING_AUTHOR:   [rules:read, rules:author]
          ROLE_PRICING_APPROVER: [rules:read, rules:approve]
```

On a clean database on 19 Nov 2026, start-up logs two lines from the engine:

```text
Rule Engine schema at version 1 (1 migration(s) applied)
Rule Engine created decision log partitions [rle_decision_2026_11, rle_decision_2026_12]
```

On the next start both partitions exist, so only the first line appears, with `0 migration(s) applied`.

## Decision log partitions

`rle_decision` is range-partitioned by calendar month in UTC from the engine's first migration, with a default partition `rle_decision_default` for rows outside any monthly partition. Partitions are named `rle_decision_YYYY_MM`. This is fixed, not configured (ADR-0010).

| Step | When | Status |
|---|---|---|
| Create the partitions of the current and the next month | Every start-up, after the engine's migrations. The month comes from the host `Clock` bean, or system UTC when the host has none, never from the database clock | Built |
| Move rows of a month out of `rle_decision_default` | When that month's partition is created, in the same transaction, before it is attached | Built |
| Create partitions up to `now() + decision-log.partitions-ahead` | Daily, from the scheduler thread | 0.1 (M1) |
| Drop partitions older than `decision-log.retention` | From the scheduler thread, outside the `dev` profile | M3 |

Several replicas can start at once: a transaction-scoped advisory lock lets one replica create the partitions at a time, and the others find them present. Each creation waits at most 5 s for locks (`lock_timeout`).

With `migrate-on-start: false`, the engine creates partitions only if its schema is current. If migrations are pending, it skips the partitions, logs a warning, and the `ruleEngine` health contributor reports `DOWN`. Run the migrations, then restart.

The `ruleEngine` health contributor also reports whether next month's partition exists. A missing partition is a detail, not a failure: decisions of a month without a partition go to `rle_decision_default` until that partition is created.

## Host beans the starter picks up

| Bean | Default | Override when |
|---|---|---|
| `TenantResolver` | Reads the `tenancy.claim` attribute of the authenticated principal in the security context: a validated token or a server-side session login. A call with no resolvable tenant gets `401 UNAUTHENTICATED` | Tenant comes from somewhere else, such as your user directory, realm, path or subdomain. No header-based resolver ships; a resolver you write that trusts a request header is your risk Built in `io.github.dhluong90.bpm.host.spi`; see [[Host Metadata and Permissions]] |
| `CallerResolver` | Principal name | Audit needs a different identity |
| `RuleFunctionRegistry` | Built-in pure functions | Host registers extra pure functions, listed in the cockpit |
| `RuleEventPublisher` | Spring `ApplicationEventPublisher` | Events go to an outbox or a bus |
| `CurrencyRegistry` | ISO 4217 scales from the JDK | A currency has no ISO scale (for example `XAU`) or needs another scale |
| `DecisionExplainer` | Explanation text from a message catalogue per language (`en`, `vi`), default language `explain.default-language` | House wording beyond what catalogue overrides allow; the structured reasons stay the same |
| `LifecycleGuard` | Allows every transition | An extra veto, such as a change ticket on retire; it cannot let through what the engine refuses |
| `Clock` | System UTC | Tests and replays. The engine's only source of time: the default for `DecisionRequest.clock`, partition creation and the health check. The engine uses the host's bean when there is exactly one and never registers a `Clock` bean of its own |

The engine's default beans are `@ConditionalOnMissingBean`, so a host bean replaces them (see [[Configuration and Extension Points]]). `Clock` is read from the host and has no engine bean. The starter never component-scans the host. Today `Clock` and `TenantResolver` are read; the others arrive with 0.1.

## Permissions

| Permission | Grants |
|---|---|
| `rules:evaluate` | `:evaluate`, `:evaluate-batch` on published versions |
| `rules:read` | Read rule sets, versions, diffs, exports |
| `rules:author` | Create and edit drafts, validate, import, `:request-publish` |
| `rules:approve` | `:approve`, `:reject`, `:retire`. Approval by the requester is refused regardless |
| `rules:simulate` | `:simulate`, set `clock` on `:evaluate` |
| `rules:pin` | Evaluate one named approved version (`/versions/{v}:evaluate`). No role holds it by default; grant it only to a service identity such as the Process Engine's. A retired version is refused unless `evaluation.pin-retired` is `true` |
| `rules:log:read` | Read the decision log and explanations |

### Mapping permissions to the host's authorities

The REST layer checks `hasAuthority('<permission>')` on the authenticated principal. Give the principal the permission strings as `GrantedAuthority` values; nothing else is needed. Two ways to do that:

| Way | How |
|---|---|
| Authorities from the token | A `JwtAuthenticationConverter` (or your OIDC mapper) emits the permission strings directly, for example from a `permissions` claim |
| Roles to permissions | `security.role-permissions` maps the host's role authorities to engine permissions; the starter adds the mapped permissions to the principal for engine endpoints only. `/api/bpm/me` lists them already |

```yaml
horus.bpm.rules.security.role-permissions:
  ROLE_RISK_ANALYST: [rules:read, rules:author, rules:simulate, rules:log:read]
  ROLE_RISK_MANAGER: [rules:read, rules:approve, rules:log:read]
  ROLE_SERVICE:      [rules:evaluate]
```

The endpoint-to-permission table is in [[Rule Engine Decision API]]. To see what a caller holds, call `GET /api/bpm/me` ([[Host Metadata and Permissions]]).

## Local development

This section describes Rule Engine 0.1. A host on the `dev` profile can run without an identity provider. The example hosts use this configuration:

```yaml
spring.profiles.active: dev
horus.bpm.rules:
  definitions: classpath:rules/*.yaml
  definitions-tenant: dev
  definitions-auto-publish: true          # dev profile only
horus.bpm.tenancy.dev-tenant: dev         # dev profile only; every call without a tenant runs as tenant dev
```
```java
@Bean @Profile("dev")
UserDetailsService devUsers(PasswordEncoder enc) {
  return new InMemoryUserDetailsManager(
    User.withUsername("dev").password(enc.encode("dev"))
        .authorities("rules:evaluate", "rules:read", "rules:author", "rules:approve", "rules:simulate", "rules:log:read").build(),
    User.withUsername("checker").password(enc.encode("checker")).authorities("rules:read", "rules:approve").build());
}
```
With HTTP basic enabled, `curl -u dev:dev ...` holds `rules:evaluate` and runs as tenant `dev`. Maker-checker still applies: `dev` requests publication and `checker` approves. Outside the `dev` profile the starter refuses to start with `definitions-auto-publish: true`, `maker-checker.enabled: false` or any `tenancy.dev-tenant` value.
