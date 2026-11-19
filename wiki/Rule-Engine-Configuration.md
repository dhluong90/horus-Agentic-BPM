# Configuration

Reference for every `horus.bpm.rules.*` property of the Rule Engine starter and the permission strings its REST layer checks. _Design preview: two properties are built; the rest describe Rule Engine 0.1._

## Status of each property

| Status | Meaning |
|---|---|
| Built | Read by the starter in the first engine build ([pull request #12](https://github.com/dhluong90/horus-Agentic-BPM/pull/12)). Not yet released |
| 0.1 (M1) | Planned for Rule Engine 0.1, milestone M1, 15 Jan 2027. Setting it today has no effect |
| 0.1 (M1), may move | Planned for 0.1; the [[Roadmap]] names the milestone it moves to if M1 runs late |
| Pending PD-nnn | Waits for a product decision in the [decision log](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/docs/product/decisions.md) |

## Properties

All names are under `horus.bpm.rules.`.

| Property | Default | Status | Meaning |
|---|---|---|---|
| `enabled` | `true` | Built | Register the engine beans. The engine needs exactly one `DataSource` in the host (or one marked `@Primary`); without it the starter stays inactive and logs a warning |
| `schema-prefix` | `rle_` | Pending PD-072 | Table prefix in the host database. The Flyway history table is `<prefix>schema_history`, separate from the host's. The first build fixes the prefix at `rle_` and the history table at `rle_schema_history` |
| `migrate-on-start` | `true` | Built | Run the engine's Flyway migrations at start-up with the host `DataSource`; `false` for hosts that run migrations in a separate job |
| `definitions` | unset | 0.1 (M1) | Resource pattern of YAML rule sets to load as drafts at start-up |
| `definitions-tenant` | `default` | 0.1 (M1) | Tenant that owns rule sets loaded from `definitions`. A document may override it with a top-level `tenant:` field; multi-tenant hosts load per-tenant files or use the import endpoint |
| `definitions-auto-publish` | `false` | 0.1 (M1) | Publish loaded drafts without maker-checker. Refused unless the `dev` profile is active |
| `rest.enabled` | `true` | 0.1 (M1) | Expose the REST API |
| `rest.base-path` | `/api/bpm/rules/v1` | 0.1 (M1) | Base path of the REST API. Keep the version segment when you change it; the cockpit reads the same value |
| `rest.cors.allowed-origins` | empty | 0.1 (M1) | Origins allowed to call the REST API, needed only for a standalone cockpit. Empty disables CORS |
| `decision-log.enabled` | `true` | 0.1 (M1) | Write `rle_decision` rows |
| `decision-log.mode` | `transactional` | 0.1 (M1); `async` may move to M2 | `transactional`: one `INSERT` in the caller's transaction. `async`: insert into `rle_decision_outbox`, relayed by the scheduler thread |
| `decision-log.async.batch-size` | `100` | 0.1 (M1), may move to M2 | Outbox rows relayed per poll in `async` mode |
| `decision-log.partitions-ahead` | `P7D` | 0.1 (M1) | How far ahead the scheduler creates monthly `rle_decision` partitions. See [Decision log partitions](#decision-log-partitions) |
| `decision-log.retention` | unset | 0.1 (M1); milestone pending PD-020, default pending PD-050 | ISO 8601 period, for example `P12M`. When set, the scheduler drops whole monthly partitions that end before `now() - retention`. Unset keeps everything. No decision row is ever deleted by date |
| `decision-log.mask-default` | `false` | 0.1 (M1) | Mask every input unless the rule set says `mask: false` |
| `masking.hmac-key` | unset | 0.1 (M1) | Base64 key, 32 bytes or more, for HMAC-SHA256 of masked inputs. Supply from an environment variable or secret store. Start-up fails if any loaded rule set masks an input and no key is set |
| `evaluation.max-rows` | `5000` | 0.1 (M1) | Largest table a version may contain |
| `evaluation.max-batch` | `1000` | 0.1 (M1), may move to M2 | Largest `:evaluate-batch` request |
| `evaluation.regex-timeout-ms` | `50` | 0.1 (M1) | Bounded matcher timeout; a hit fails the decision with `EVALUATION_ERROR` |
| `evaluation.regex-max-input-length` | `4096` | 0.1 (M1) | Longer inputs fail `matches()` deterministically |
| `cache.max-entries` | `1000` | 0.1 (M1) | Compiled versions kept per replica, LRU eviction |
| `cache.listen-notify` | `true` | 0.1 (M1) | Open one session-mode connection for `LISTEN horus_rle_cache`; set `false` behind a transaction-mode pooler |
| `cache.epoch-check-interval` | `PT5S` | 0.1 (M1) | Interval of the `cache_epoch` sweep; this is the staleness bound when NOTIFY is unavailable |
| `idempotency.retention` | `PT24H` | 0.1 (M1) | Lifetime of `rle_idempotency` rows |
| `maker-checker.enabled` | `true` | 0.1 (M1) | `false` is refused unless the `dev` profile is active |
| `tenancy.resolver` | `security-context` | 0.1 (M1) | `security-context`, `header:X-Tenant-Id` (behind a gateway only), or the name of a `TenantResolver` bean |
| `events.decision-evaluated` | `false` | 0.1 (M1) | Publish `rules.decision.evaluated.v1` for every decision |
| `scheduler.enabled` | `true` | 0.1 (M1) | Run the engine's scheduler thread (outbox relay, partition creation, partition drop, idempotency cleanup) on this replica |
| `security.role-permissions` | empty | 0.1 (M1) | Map of host role authorities to engine permissions; see [Mapping permissions](#mapping-permissions-to-the-hosts-authorities) |

Properties with a prefix of `tenancy`, `masking` and `rest.cors` are shared with the Process Engine starter under `horus.bpm.processes.*`; when both engines run in one host, set them once under `horus.bpm.*`.

There is no property to turn decision log partitioning on or off. `rle_decision` is partitioned by month in every install (ADR-0010).

There is no `clock` property either. The engine's time comes from the host's `Clock` bean (see [Host beans](#host-beans-the-starter-picks-up)), and a single decision can reason about another instant through the request's `clock` field, which needs `rules:simulate` over REST. To fix time in tests, declare a fixed `Clock` bean, for example `Clock.fixed(Instant.parse("2026-12-01T00:00:00Z"), ZoneOffset.UTC)`.

### What the first build reads

```yaml
horus:
  bpm:
    rules:
      enabled: true            # default
      migrate-on-start: true   # default
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
| Drop partitions older than `decision-log.retention` | From the scheduler thread, only when `retention` is set | 0.1 (M1), milestone pending PD-020 |

Several replicas can start at once: a transaction-scoped advisory lock lets one replica create the partitions at a time, and the others find them present. Each creation waits at most 5 s for locks (`lock_timeout`).

With `migrate-on-start: false`, the engine creates partitions only if its schema is current. If migrations are pending, it skips the partitions, logs a warning, and the `ruleEngine` health contributor reports `DOWN`. Run the migrations, then restart.

The `ruleEngine` health contributor also reports whether next month's partition exists. A missing partition is a detail, not a failure: decisions of a month without a partition go to `rle_decision_default` until that partition is created.

## Host beans the starter picks up

| Bean | Default | Override when |
|---|---|---|
| `TenantResolver` | Reads `tenant_id` claim from the JWT, or the configured header | Tenant comes from somewhere else (realm, path, subdomain) |
| `CallerResolver` | Principal name | Audit needs a different identity |
| `RuleFunctionRegistry` | Built-in pure functions | Host registers extra pure functions, listed in the cockpit |
| `RuleEventPublisher` | Spring `ApplicationEventPublisher` | Events go to an outbox or a bus |
| `Clock` | System UTC | Tests and replays. The engine's only source of time: the default for `DecisionRequest.clock`, partition creation and the health check. The engine uses the host's bean when there is exactly one and never registers a `Clock` bean of its own |

The engine's default `TenantResolver`, `CallerResolver`, `RuleFunctionRegistry` and `RuleEventPublisher` beans are `@ConditionalOnMissingBean`, so a host bean replaces them. `Clock` is read from the host and has no engine bean. The starter never component-scans the host. In the first build only `Clock` is read; the others arrive with 0.1.

## Permissions

| Permission | Grants |
|---|---|
| `rules:evaluate` | `:evaluate`, `:evaluate-batch` on published versions |
| `rules:read` | Read rule sets, versions, diffs, exports |
| `rules:author` | Create and edit drafts, validate, import, `:request-publish` |
| `rules:approve` | `:approve`, `:reject`, `:retire`. Approval by the requester is refused regardless |
| `rules:simulate` | `:simulate`, evaluate drafts, set `clock` on `:evaluate` |
| `rules:log:read` | Read the decision log and explanations |

### Mapping permissions to the host's authorities

The REST layer checks `hasAuthority('<permission>')` on the authenticated principal. Give the principal the permission strings as `GrantedAuthority` values; nothing else is needed. Two ways to do that:

| Way | How |
|---|---|
| Authorities from the token | A `JwtAuthenticationConverter` (or your OIDC mapper) emits the permission strings directly, for example from a `permissions` claim |
| Roles to permissions | `security.role-permissions` maps the host's role authorities to engine permissions; the starter adds the mapped permissions to the principal for engine endpoints only |

```yaml
horus.bpm.rules.security.role-permissions:
  ROLE_RISK_ANALYST: [rules:read, rules:author, rules:simulate, rules:log:read]
  ROLE_RISK_MANAGER: [rules:read, rules:approve, rules:log:read]
  ROLE_SERVICE:      [rules:evaluate]
```

The endpoint-to-permission table is in [[Rule Engine Decision API]].

## Local development

This section describes Rule Engine 0.1. A host on the `dev` profile can run without an identity provider. The example hosts use this configuration:

```yaml
spring.profiles.active: dev
horus.bpm.rules:
  definitions: classpath:rules/*.yaml
  definitions-tenant: dev
  definitions-auto-publish: true          # dev profile only
  tenancy.resolver: header:X-Tenant-Id    # send X-Tenant-Id: dev with every request
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
With HTTP basic enabled, `curl -u dev:dev -H 'X-Tenant-Id: dev' ...` holds `rules:evaluate`. Maker-checker still applies: `dev` requests publication and `checker` approves. Outside the `dev` profile the starter refuses `definitions-auto-publish: true` and `maker-checker.enabled: false`, and logs a warning when `tenancy.resolver` is a header.
