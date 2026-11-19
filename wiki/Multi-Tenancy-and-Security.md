# Multi-Tenancy and Security

This page explains how the engines isolate tenants, which security controls they enforce themselves, which they delegate to the host, and how each control is verified. The engines add no authentication of their own; they run under the host's Spring Security.

![Security boundary across four zones](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/security-boundary.svg)

Each numbered step is a control the request passes. Steps 1 to 3 run in your gateway, your identity provider and your Spring Security configuration. Steps 4 to 9 are enforced by the engine modules inside your service, whatever the cockpit shows.

## Tenant resolution

| Source (`tenancy.resolver`) | How | Use when |
|---|---|---|
| `security-context` (default) | `TenantResolver` reads the `tenant_id` claim of the `JwtAuthenticationToken`, or the issuer realm when configured | OIDC hosts |
| `header:X-Tenant-Id` | Trusted header set by a gateway after authentication | The gateway terminates authentication and the host network is private |
| Host bean | A `TenantResolver` bean (`com.horus.bpm.host.spi`), which replaces both modes above | Host session (form, SAML, LDAP sign-in and the embedded cockpit): the tenant comes from the signed-in principal, never from a header. Also path or subdomain |

Rules that hold in every mode: every table has `tenant_id` first in every index (`TR-TEN-01`); every repository method takes the tenant as a parameter; a `tenantId` in a request body is ignored (`TR-SEC-03`, `RLE-011`); a resource of another tenant answers `404`, never `403`, so names do not leak. The Java API resolves the tenant per call; `DecisionRequest.tenantId` for batch jobs needs an explicit host grant. Whatever the resolver, a token that carries the tenant claim must match the resolved tenant, so a gateway header cannot move a caller into another tenant; header mode outside the `dev` profile also needs `tenancy.trusted-gateway=true`. Until tenant switching arrives (M2), an `X-Tenant-Id` header that differs from the resolved tenant is refused with `403 TENANT_NOT_ALLOWED` and the security log entry `tenant.switch.refused`. Both are built for `/api/bpm/meta` and `/api/bpm/me` ([[Host Metadata and Permissions]]).

## Permissions

The REST layer checks one permission string per endpoint with `@PreAuthorize`; the host maps strings to roles.

| Engine | Permissions | Reference |
|---|---|---|
| Rule Engine | `rules:evaluate`, `rules:read`, `rules:author`, `rules:approve`, `rules:simulate`, `rules:log:read` | [[Rule Engine Configuration]] |
| Process Engine | `processes:read`, `processes:deploy`, `processes:start`, `tasks:work`, `tasks:manage`, `incidents:manage`, `migrations:manage`, `calendars:manage` | [[Process Engine Overview]] |

## Maker-checker in the service layer

![Publish sequence with maker-checker](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/publish-sequence.svg)

| Control | Implementation |
|---|---|
| Distinct permissions | Request needs `rules:author`; approval needs `rules:approve`. A user may hold both |
| Distinct identities | `VersionService.approve` rejects `approverId.equals(requestedBy)` with `SELF_APPROVAL_FORBIDDEN` before any state change. The check lives in the service, so the Java API and REST behave the same (`TR-SEC-04`, `RLE-009`) |
| No replay | Approval runs `UPDATE rle_version SET status='PUBLISHED' ... WHERE status='PENDING_APPROVAL' AND lock_version=:v`; zero rows gives `409 VERSION_CONFLICT` or `409 INVALID_STATE` |
| Four-eyes on tasks | `pre_task.forbidden_users` lists identities that acted earlier in the instance or triggered the `agentTask`; `TaskService` rejects them with `403 SELF_APPROVAL_FORBIDDEN` (`PRE-007`) |
| Cockpit | Disables the button for the requester, and still relies on the API refusal |
| Switch-off | `maker-checker.enabled=false` is refused outside the `dev` profile |

## No code execution

Expressions are parsed by a hand-written recursive-descent parser into a sealed, typed tree; there is no scripting engine, reflection, class loading or deserialisation of arbitrary types (`TR-SEC-01`, ADR-0003). Host-registered pure functions are the only extension point and are listed in the cockpit. Regex matching is bounded by input length (4 096 characters) and time (50 ms); nested quantifiers are rejected at publish time (`TR-SEC-02`, `RLE-007`).

## Data protection

| Control | Detail |
|---|---|
| Masking | Inputs with `mask: true` are stored as `hmac:<hex>` using HMAC-SHA256 with the host-provided `masking.hmac-key` (32 bytes or more, from an environment variable or secret store). Plain SHA-256 is not used because national ids and phone numbers are enumerable. Equal values hash equally, so audit search still works (`TR-SEC-05`, `RLE-012`) |
| Key handling | The key never appears in logs, responses or the cockpit; start-up fails when masking is required and the key is missing (`TR-SEC-10`). Rotation: configure the new key, old rows stay searchable only by the old key |
| Append-only logs | `rle_decision` and `pre_history` have no update or delete path in the engine (`TR-OBS-01`); retention is the host's: drop monthly partitions |
| Responses | No stack traces; RFC 9457 bodies with a `correlationId` (`TR-SEC-08`) |
| Agent inputs | Masked before they are written to `pre_history`; the adapter receives the clear values (`TR-AGT-02`) |

## Cockpit security headers and CORS

| Shape | Headers set by `cockpit-spring-boot-starter` |
|---|---|
| Embedded at `horus.bpm.cockpit.path` | `Content-Security-Policy: default-src 'self'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `Cache-Control: no-store` on API responses. Assets are served only to authenticated sessions |
| Standalone | The host sets `rest.cors.allowed-origins` to the exact cockpit origin; credentials via bearer token, not cookies; `connect-src` on the cockpit side lists the host URLs |

The cockpit holds no secrets and no state (ADR-0005). It never marks an approval, a task completion or an incident retry as done before the API returns `200` or `201`.

## Threat model (STRIDE)

| Category | Asset | Threat and exploit scenario | Control | Verification |
|---|---|---|---|---|
| Spoofing | Tenant boundary | Caller sends `tenantId: "tenant-b"` in the body, or forges `X-Tenant-Id` from outside the gateway | Body tenant ignored; header mode only behind a trusted gateway; JWT claim by default | `RLE-011`; REST test with forged body and header |
| Spoofing | Approvals | Compromised author token approves its own version | Service-layer identity check plus distinct permission | `RLE-009` at service and REST level |
| Tampering | Rule definitions | Expression such as `T(java.lang.Runtime).getRuntime()` or a regex bomb in a draft | Closed grammar, no reflection; bounded regex; publish-time rejection | `RLE-004`, `RLE-007`; ArchUnit on `javax.script`, `java.lang.reflect.Method` |
| Tampering | Task state | Two completions of one task, or a replayed approval | `lock_version` predicate; status guard in the same `UPDATE` | `PRE-003`; concurrency test with two threads |
| Tampering | Idempotency | Replay of a key with a changed payload to smuggle a different start | `request_hash` compared; `422 IDEMPOTENCY_KEY_REUSE_MISMATCH` | `RLE-014`, `TR-IDEM-05` |
| Repudiation | Decisions and process history | Operator claims a decision never happened or was different | Append-only rows with version, clock, caller, correlation id; no update path | `TR-OBS-01`; SQL capture test shows only `INSERT` |
| Repudiation | Agent steps | Dispute over what an agent saw and returned | History stores masked inputs, raw output, schema result, guardrail decision id, human override | `PRE-012` |
| Information disclosure | Personal data in logs | National id readable in `rle_decision` or the cockpit | Keyed HMAC masking; cockpit renders `*** (hmac)` | `RLE-012`; UI test |
| Information disclosure | Cross-tenant reads | Guessable ULID of another tenant's decision | Every query filters `tenant_id`; `404` for other tenants | `RLE-011`; two-tenant integration test |
| Information disclosure | Error bodies | Stack trace reveals class names and SQL | Problem Details only; `INTERNAL_ERROR` with `correlationId` | WebMvc test on an injected failure |
| Denial of service | Evaluation threads | Adversarial regex input stalls a request thread | 50 ms bounded matcher, 4 096-character input limit | `RLE-007` |
| Denial of service | Connection pool | 20 slow agent calls inside transactions exhaust a 20-connection pool | Async Wait-State: no connection during adapter calls (ADR-0008) | `PRE-009`: 10 concurrent 2 s calls on a 2-connection pool |
| Denial of service | Batch endpoint | 1 000 000-item batch | `evaluation.max-batch` 1 000; `limit` max 200 | REST tests |
| Denial of service | Scheduler | Poison job retried forever | `max_attempts` then incident; lease prevents hot loops | `PRE-011` |
| Elevation of privilege | Approval endpoint | Missing `@PreAuthorize` lets an author call `:approve` | Permission per endpoint; service check independent of the controller | REST test with an author token |
| Elevation of privilege | Time travel | Caller sets `clock` to pick a future fee version | `clock` requires `rules:simulate`; `403 CLOCK_NOT_ALLOWED` | REST test |
| Elevation of privilege | Agent output | Agent returns an output that skips approval | Guardrail rule set runs in-process after schema validation; `route-to-human` creates a task the triggering identity cannot approve | `PRE-010` |

## Supply chain

Third-party dependencies carry Apache 2.0, MIT or BSD licences only; the build fails on anything else (`TR-LIC-01`). Every release ships a CycloneDX SBOM; high or critical vulnerabilities block it (`TR-SEC-06`). Example container images run as non-root and are scanned. Report vulnerabilities as described in the repository's `SECURITY.md`.
