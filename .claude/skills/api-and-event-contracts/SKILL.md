---
name: api-and-event-contracts
description: "Design contract-first OpenAPI 3.1 REST APIs, additive domain events, RFC 9457 Problem Details errors, Idempotency-Key & optimistic locking headers, cursor pagination, Money JSON shapes, and Backend-to-Frontend handoff specs. Use when defining OpenAPI contracts, event schemas, API versioning, or frontend/cockpit integration specs. Adapted from affaan-m/ECC (api-design) and softaworks/agent-toolkit (backend-to-frontend-handoff-docs)."
---

# API & Event Contracts (Contract-First Banking Standard)

Design clean, predictable, backward-compatible HTTP APIs and domain events before writing controllers or UI components.

## When to Use
- Creating or modifying `api/openapi.yaml` or `api/events/` in any Banking Accelerator.
- Defining or reviewing `/api/bpm/rules/v1` and `/api/bpm/processes/v1` REST contracts for `horus-Agentic-BPM` and Cockpit.
- Producing an API handoff document for `frontend-engineer`.

---

## 1. URL, Versioning & Naming Conventions
- **Versioned Base Paths:** Always include the major version in the path (`/api/bpm/rules/v1/...`, `/api/bpm/processes/v1/...`, `/api/v1/wallets/...`). Breaking changes require `/v2/...`; additive fields remain in `/v1`.
- **Plural kebab-case nouns** for resources: `/rule-sets/{name}/versions/{version}`, `/process-instances/{id}/incidents`.
- **Custom RPC actions (non-CRUD transitions):** Use Google AIP-style `:verb` suffix for state transitions and evaluations:
  - `POST /decisions/{ruleSet}:evaluate`
  - `POST /rule-sets/{name}/versions/{v}:request-publish`
  - `POST /rule-sets/{name}/versions/{v}:approve`
  - `POST /incidents/{id}:retry`
  - `POST /migrations/{id}:validate`

## 2. Banking Money, Dates, IDs & Tenancy Rules
- **Money JSON Representation:** NEVER use JSON floating-point numbers (`12.50`) for money. Always use structured currency + integer minor units (or exact string decimal where currency scale requires):
  ```json
  { "currency": "VND", "amount": 5000000 }
  ```
- **Identifiers:** ULID strings (`01JD2X...`) — lexicographically sortable, replica-safe, URL-safe.
- **Timestamps:** ISO-8601 UTC (`2026-11-18T09:12:44Z`).
- **Tenancy (`TR-SEC-03`):** Never accept `tenantId` in request JSON bodies to override context. Derive tenant exclusively from the authenticated security context / trusted gateway resolver.

## 3. Idempotency & Concurrency Control Headers
- **Idempotency (`ADR-0006`, `TR-IDEM`):** Every mutating `POST`/`PUT`/`PATCH` endpoint (and `:evaluate` when logging decisions) accepts `Idempotency-Key: <uuid-or-ulid>`.
  - Replay with identical key + identical request hash → returns the **original HTTP status and response body** (`200`/`201`) with no second side effect.
  - Replay with identical key + **different request payload hash** → returns **`422 Unprocessable Entity`** or **`409 Conflict`** (`IDEMPOTENCY_KEY_REUSE_MISMATCH`).
  - Concurrent in-flight duplicate → returns **`409 Conflict`** (`IDEMPOTENCY_IN_FLIGHT`) with `Retry-After: 1`.
- **Optimistic Locking (`ETag` / `version`):** Task completions and draft edits include the entity `version` (in payload or `If-Match` header). Stale writes return **`409 Conflict`** (`VERSION_CONFLICT`).

## 4. Standardized Error Format (RFC 9457 Problem Details)
Never return `200 OK` with `{ "success": false }`, and never leak Java stack traces in `500` responses. Return `application/problem+json` with stable machine-readable error codes:
```json
{
  "type": "https://horus.dev/errors/unique-hit-violation",
  "title": "Unique Hit Policy Violated",
  "status": 409,
  "code": "UNIQUE_HIT_VIOLATION",
  "detail": "Rule set 'pricing-tier' v3 matched 2 rules (R2, R4); expected exactly 1.",
  "instance": "/api/bpm/rules/v1/decisions/pricing-tier:evaluate",
  "correlationId": "01JD2X9...",
  "violations": []
}
```

## 5. Pagination & Filtering
- **Audit / Decision Logs / History (large append-only streams):** **Cursor-based pagination** (`?cursor=<opaque>&limit=50`) returning `data`, `nextCursor`, `hasNext`. Never rely on deep `OFFSET` scans.
- **Small management lists (`rule-sets`, `definitions`):** Bounded page/size (`limit <= 100`) or cursor pagination with explicit sort whitelist.

## 6. Domain Events & Outbox Contract
- Event envelope (`api/events/*.json` or AsyncAPI):
  - `eventId` (ULID), `eventType` (`rules.version.published.v1`), `occurredAt` (UTC), `tenantId`, `correlationId`, `causationId`, `producer`, `schemaVersion`, `payload`.
- **Evolution Rule:** Additive-only changes (`new optional fields` allowed; never rename, remove, or change field types within the same `eventType` version).
- **Delivery:** Persist event to transactional outbox table inside the **same DB transaction** as the domain state change.

## 7. Backend-to-Frontend / Cockpit Handoff Checklist
When an API slice is ready for `frontend-engineer`, verify or generate:
1. Updated OpenAPI 3.1 YAML (`openapi.yaml`) that cleanly generates TypeScript types & Zod validators.
2. Exact Auth / RBAC permission required per endpoint (e.g. `rules:publish:approve` vs `rules:publish:request` for maker-checker UI disable + API enforcement).
3. Complete HTTP error code matrix (`400`, `401`, `403`, `404`, `409`, `422`) and optimistic-UI safety guidance (e.g. *never* optimistically mark maker-checker approvals or financial postings complete before `200/201`).

## HORUS Program Integration
- Enforces HORUS Non-negotiable #2 (*API-first: OpenAPI before UI; events for every meaningful state change*).
- Keep `horus-Agentic-BPM/wiki/Rule-Engine-Decision-API.md` and generated OpenAPI documents aligned (`TR-QA-02`).
