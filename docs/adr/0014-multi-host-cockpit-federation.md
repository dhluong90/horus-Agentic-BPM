# ADR-0014: Multi-host cockpit federation

- Status: accepted
- Date: 2026-11-18
- Deciders: product owner (PD-035, PD-037), maintainers
- Module: cockpit, project
- Amends: ADR-0005 (cockpit as static assets) and `TR-UI-01`

## Context
ADR-0005 makes the cockpit static assets with no state, served by a host or standalone. A bank runs several services that each embed the engines, each with its own database. All replicas of one service share one database, so any replica's REST layer gives that service's full picture; the gap is across services. Operators need one inbox and one incident list, sign-in once, and a way to work for several tenants, without an engine server (principle 1) and without copying data into a new store.

## Decision
1. **Model.** Option B of PD-035: the standalone cockpit federates hosts from the browser. It reads a host registry, `cockpit-hosts.json` (schema `cockpit-hosts.v1.schema.json`), served beside `index.html`; signs in once with OIDC authorization code and PKCE as a public client; obtains one access token per host scope by default (`tokenMode: per-host`), kept in memory only; and calls each host directly. Option A (embedded) stays supported: the starter generates a one-host registry. Option D (the bank's gateway) is documented, not built. Option C (an aggregator fed by events) is rejected for now and reopened only with a paying customer.
2. **Host metadata.** Every host serves `GET /api/bpm/meta` (engines, versions, features, settings, tenancy, limits) and `GET /api/bpm/me` (user, current and allowed tenants, permissions, roles, groups), M1. The cockpit supports the current and the previous engine minor version, turns screens on by feature flag, and marks older hosts unsupported.
3. **Merged lists.** Inbox and incident lists merge across hosts in the browser in M2, with an exact k-way merge on a shared sort key, per-host cursors, `ETag` and `304`, a per-host timeout and a visible "incomplete" banner.
4. **Host security.** CORS is off by default and lists exact origins; a host validates the token audience and rejects tokens for another host with `401`; when CORS is configured, the starter refuses to start outside `dev` without an audience setting.
5. **Tenant switching (PD-037).** The cockpit may send `X-Tenant-Id`; with `tenancy.switching.enabled` the host accepts it only for a tenant in the token's allowed-tenants claim (`tenancy.allowed-tenants-claim`, default `allowed_tenants`). Anything else is `403 TENANT_NOT_ALLOWED`, with a security log entry. No screen or API call combines tenants. M2.

## Options considered
1. A: embedded only. Works today; one bookmark and one inbox per service.
2. B: standalone federation in the browser, with A supported (chosen). No server, no state, no copied data; needs CORS and one identity provider; merging is designed for about 10 to 15 hosts.
3. C: read-side aggregator fed by engine events. Fleet-wide views and analytics; copies business data and personal data into another store, eventual consistency, its own identity and operations: a product's worth of work.
4. D: our own control plane (registry, token exchange, proxy). Solves CORS and discovery, but is a server with identity, which breaks ADR-0005; most banks already run a gateway that does this.

Token audience, within option 2:
- a. One token with every host in its audience (`shared`). Fewer identity provider requests; a token leaked from one host is valid at all of them. Allowed as an explicit choice.
- b. One token per host (chosen default). A leaked token is valid at one host; costs one refresh grant per host per token lifetime.

Refused tenant status:
- a. `404`, as for another tenant's resource. Hides a misconfigured identity provider behind "not found".
- b. `403 TENANT_NOT_ALLOWED` (chosen). Reveals only what the caller's own token already says.

## Consequences
- ADR-0005 stands, with three additions: the registry file, sign-in across hosts, and version skew handling. The cockpit still has no server and no state.
- A small shared module serves `/meta` and `/me` once per host; each engine contributes its entry.
- The identity provider is the first shared component to feel load: with 5-minute per-host tokens, 2,000 operators and 15 hosts make about 125 refresh requests per second (cockpit DESIGN section 9). Banks size it or choose `shared` mode.
- The supported envelope (proposed 15 hosts, 2,000 operators, merged p95 300 ms) is pending PD-046.
- Dependency: `oidc-client-ts` (Apache 2.0), justified in the cockpit DESIGN section 2.
- Requirements: `TR-UI-01` (amended), `TR-UI-05` (proposed, pending PD-046), `TR-UI-08`, `TR-SEC-03` (amended), `TR-SEC-12`. Verified by `CKP-001`, `CKP-012` to `CKP-015`, `CKP-019`, `CKP-020`, `CKP-022`, `PRE-038`, `RLE-018`, `RLE-019`.
