# Configuration

Reference for every `horus.bpm.rules.*` property of the Rule Engine starter and the permission strings its REST layer checks. _Design preview._

## Properties

| Property | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Register the engine beans |
| `schema-prefix` | `rle_` | Table prefix in the host database. The Flyway history table is `<prefix>schema_history`, separate from the host's |
| `migrate-on-start` | `true` | Run the engine's Flyway migrations at start-up with the host `DataSource`; `false` for hosts that run migrations in a separate job |
| `definitions` | unset | Resource pattern of YAML rule sets to load as drafts at start-up |
| `definitions-tenant` | `default` | Tenant that owns rule sets loaded from `definitions`. A document may override it with a top-level `tenant:` field; multi-tenant hosts load per-tenant files or use the import endpoint |
| `definitions-auto-publish` | `false` | Publish loaded drafts without maker-checker. Refused unless the `dev` profile is active |
| `rest.enabled` | `true` | Expose the REST API |
| `rest.base-path` | `/api/bpm/rules/v1` | Base path of the REST API. Keep the version segment when you change it; the cockpit reads the same value |
| `rest.cors.allowed-origins` | empty | Origins allowed to call the REST API, needed only for a standalone cockpit. Empty disables CORS |
| `decision-log.enabled` | `true` | Write `rle_decision` rows |
| `decision-log.mode` | `transactional` | `transactional`: one `INSERT` in the caller's transaction. `async`: insert into `rle_decision_outbox`, relayed by the scheduler thread |
| `decision-log.async.batch-size` | `100` | Outbox rows relayed per poll in `async` mode |
| `decision-log.partitioning` | `none` | `none` or `monthly`; `monthly` creates `rle_decision` partitions 7 days ahead from the scheduler thread |
| `decision-log.mask-default` | `false` | Mask every input unless the rule set says `mask: false` |
| `masking.hmac-key` | unset | Base64 key, 32 bytes or more, for HMAC-SHA256 of masked inputs. Supply from an environment variable or secret store. Start-up fails if any loaded rule set masks an input and no key is set |
| `evaluation.max-rows` | `5000` | Largest table a version may contain |
| `evaluation.max-batch` | `1000` | Largest `:evaluate-batch` request |
| `evaluation.regex-timeout-ms` | `50` | Bounded matcher timeout; a hit fails the decision with `EVALUATION_ERROR` |
| `evaluation.regex-max-input-length` | `4096` | Longer inputs fail `matches()` deterministically |
| `cache.max-entries` | `1000` | Compiled versions kept per replica, LRU eviction |
| `cache.listen-notify` | `true` | Open one session-mode connection for `LISTEN horus_rle_cache`; set `false` behind a transaction-mode pooler |
| `cache.epoch-check-interval` | `PT5S` | Interval of the `cache_epoch` sweep; this is the staleness bound when NOTIFY is unavailable |
| `idempotency.retention` | `PT24H` | Lifetime of `rle_idempotency` rows |
| `maker-checker.enabled` | `true` | `false` is refused unless the `dev` profile is active |
| `tenancy.resolver` | `security-context` | `security-context`, `header:X-Tenant-Id` (behind a gateway only), or the name of a `TenantResolver` bean |
| `events.decision-evaluated` | `false` | Publish `rules.decision.evaluated.v1` for every decision |
| `clock` | system | Fixed instant for tests, for example `2026-12-01T00:00:00Z` |
| `scheduler.enabled` | `true` | Run the engine's scheduler thread (outbox relay, partition creation, idempotency cleanup) on this replica |

Properties with a prefix of `tenancy`, `masking`, `rest.cors` and `clock` are shared with the Process Engine starter under `horus.bpm.processes.*`; when both engines run in one host, set them once under `horus.bpm.*`.

## Host beans the starter picks up

| Bean | Default | Override when |
|---|---|---|
| `TenantResolver` | Reads `tenant_id` claim from the JWT, or the configured header | Tenant comes from somewhere else (realm, path, subdomain) |
| `CallerResolver` | Principal name | Audit needs a different identity |
| `RuleFunctionRegistry` | Built-in pure functions | Host registers extra pure functions, listed in the cockpit |
| `RuleEventPublisher` | Spring `ApplicationEventPublisher` | Events go to an outbox or a bus |
| `Clock` | System UTC | Tests and replays |

All are `@ConditionalOnMissingBean`; the starter never component-scans the host.

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

A host on the `dev` profile can run without an identity provider. The example hosts use this configuration:

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
