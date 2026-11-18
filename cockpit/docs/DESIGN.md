# Cockpit — Design

Status: architecture baseline (M0). Conforms to `ADR-0005` and `wiki/Cockpit.md`.

## 1. Context, Boundaries & Explicit Non-Goals

`horus-cockpit` is a stateless React 18 / TypeScript 5 operations and governance UI compiled to static assets and served either:
1. Embedded inside a host service via `cockpit-spring-boot-starter` at `horus.bpm.cockpit.path` (default `/bpm/cockpit`), inheriting the host's Spring Security session/OIDC authentication; OR
2. As a standalone static bundle configured with base URLs for one or more remote host services (`TR-UI-01`, `ADR-0005`).

- **Explicit non-goals:** No backend-for-frontend (BFF) server, no own database or user store, no direct SQL access, no client-side authorization bypass. Every security check (tenancy, RBAC, maker-checker) is enforced by `/api/bpm/rules/v1` and `/api/bpm/processes/v1`.

## 2. Module & Package Architecture

```text
cockpit/
  src/
    app/            # Router, host-config bootstrap (/config.json or meta tag), Auth & Tenant display, i18n (en/vi)
    api/            # Generated TypeScript clients & Zod schemas from openapi.yaml (rules + processes)
    components/     # Design-token UI primitives (CSS variables, light/dark, WCAG 2.1 AA focus & contrast)
    features/
      rules/        # Rule sets list, YAML/grid viewer & diff, simulation runner, maker-checker approvals, decision log
      processes/    # Definitions, instances & token map, userTask inbox, agentTask guardrail trace, incidents, migration
  cockpit-spring-boot-starter/   # Serves META-INF/resources/bpm/cockpit/* + runtime config JSON endpoint
```

## 3. Information Hierarchy (3-Tier Operational Cockpit Pattern)

Every operational landing view follows a strict 3-tier decision hierarchy:
1. **Tier 1 — Pulse (Top Strip):** 3–5 high-signal KPIs with period delta and health status (`Green / Amber / Red` paired with text label & icon, never color alone): e.g., *Pending Approvals*, *Open Incidents*, *SLA Breaches*, *Rule Error Rate (24h)*. Conditional alert strip appears only when a threshold is breached.
2. **Tier 2 — Trends (Middle):** Time-series charts (decision throughput & error rate, step transition lag) and outcome comparison bars (simulation delta before/after version change).
3. **Tier 3 — Actionable Breakdown (Bottom):** Ranked queues & tables with immediate inline actions (`Approve / Reject`, `Retry / Resolve Incident`, `Claim / Complete Task`, `Inspect Decision Explain`).

## 4. Phased Delivery Scope (Cockpit 0.1 vs Cockpit 0.2)

- **Cockpit 0.1 (M1 — Rule Governance & Audit):**
  - Rule set browser (effective version, pending approvals, last change).
  - Read-only Table & YAML Viewer + **Side-by-Side Version Diff** (`added`, `removed`, `modified` rows).
  - **Simulation Runner** (paste/CSV sample inputs or replay last N production decisions against draft; summary of changed outcomes).
  - **Maker-Checker Approval Queue** (shows diff + attached simulation report; disables Approve button when `currentUser == requestedBy`, complementing the API's `403` guard).
  - **Decision Log Explorer & Explain View** (cursor-paginated filter by ruleSet/time/outcome/matchedRuleId; masked fields shown as `*** (hmac)`).
- **Cockpit 0.2 (M2 — Process Operations, `agentTask` & Interactive Rule Authoring):**
  - **Interactive Decision-Table Grid Editor** (typed cell inputs, inline expression syntax validation via `:validate`, YAML sync).
  - Process Definitions, Running Instances (active `pre_token` branches, variables, compensation history), and Human Task Queues (claim, complete, delegate, SLA countdown).
  - **`agentTask` Inspector:** Displays bounded inputs (masked), raw structured agent output, schema validation status, Rule Engine guardrail verdict & matched rules, and human-override diff.
  - **Incidents Queue:** Root cause, retry count, stack/context, single or batch Retry / Resolve.
  - **Business Calendar Editor** (working hours & holidays) and **M3 Instance Migration Wizard** (validate dry-run conflicts → execute batch).

## 5. State Management, Validation & Accessibility

- **Data Fetching:** `@tanstack/react-query` for server state, cursor pagination, and cache invalidation after mutations (never optimistically mark maker-checker approvals or incident retries as succeeded before HTTP `200/201`).
- **Tables & Forms:** `@tanstack/react-table` for dense grid/log rendering; `react-hook-form` + `zod` schemas generated directly from OpenAPI (`TR-UI-02`).
- **Error Handling:** Parse RFC 9457 `application/problem+json` responses into field-level inline errors and actionable toast banners containing `correlationId`.
- **i18n & Accessibility (`TR-UI-03`, `TR-UI-04`):** Complete key-parity dictionaries for English (`en`) and Vietnamese (`vi`); full keyboard navigation (`Tab`, `Enter`, `Esc`), visible focus rings, and automated `axe-core` WCAG 2.1 AA verification in Playwright tests.

## 6. Open Questions
- **Standalone multi-host switching UX:** Confirm whether standalone mode targets one host per browser tab/URL profile or includes a top-bar environment switcher (Owner: maintainers, Gate: M1 Cockpit 0.1 start).
