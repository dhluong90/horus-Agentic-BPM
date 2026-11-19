# Configuration and Extension Points

The engines are generic: nothing in them is specific to one bank, product or country. You shape them to your business with four tools, and you upgrade them by changing a version number. Decision record: ADR-0024.

Design preview: describes the 0.1 target for the Rule Engine and the 0.2 target for the Process Engine.

![Configuration and extension points](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/extension-points.svg)

## The four tools

| Tool | Use it for | Example |
|---|---|---|
| 1. Properties under `horus.bpm.*` | Limits, schedules, retention, tenant source, wording options | `horus.bpm.rules.lifecycle.sweep-interval: PT5M` |
| 2. Host beans that replace an engine default | Where tenants, callers, currencies, calendars, explanations or extra checks come from | A `TenantResolver` that reads the tenant from the request path |
| 3. Rule sets and process definitions | Business logic: fees, limits, eligibility, approvals, SLAs, escalations | A decision table versioned under four-eyes |
| 4. Event listeners | Reacting to changes: outbox, notifications, reporting | Write `rules.version.published.v1` to your outbox |

A use case you do not configure stays off or uses its documented default.

## Rules every setting follows

| Rule | What it means for you |
|---|---|
| Typed and validated | An invalid value stops start-up with a message naming the property |
| Documented | Every property has a description and a default in your IDE's Spring configuration hints |
| Protective limits only go down | Page sizes, ranges, batch sizes and definition sizes may be lowered, never raised |
| Controls stay on | Maker-checker, the audit trail, idempotency, tenant isolation, masking and fail-closed guardrails can be tightened, never switched off outside the `dev` profile. The emergency single-approver path exists only when you turn it on, and every use is logged |

## Extension points

Each is an interface with an engine default. Declare a bean of the same type and the engine uses yours; you do not exclude any auto-configuration.

| Extension point | Engine | Default | Replace it when |
|---|---|---|---|
| `TenantResolver` | both | The `tenancy.claim` claim of the token (`tenant_id`) | The tenant comes from a realm, a path or a subdomain |
| `CallerResolver` | both | Principal name | Audit needs another identity |
| `RuleFunctionRegistry` | rules | Built-in pure functions | You add pure functions for authors |
| `CurrencyRegistry` | rules | ISO 4217 scales | You trade a currency without an ISO scale, such as gold |
| `DecisionExplainer` | rules | English explanation | You want another language or house wording in API answers; the cockpit already shows English and Vietnamese |
| `LifecycleGuard` | both | Allows every transition | You need an extra check, such as a change ticket on retire. A guard can refuse; it can never let through what the engine refuses |
| `BusinessCalendar` | processes | 24x7 | SLAs count business hours and holidays |
| `StepRegistry`, `FormRegistry`, `RoleCatalogue` | processes | Empty | You offer handlers, forms and roles to authors |
| `AgentAdapter`, `ServiceAdapter` | processes | None | Your definitions call models or services |
| Event publisher | both | Spring application events | Events go to an outbox or a bus |
| `Clock` | both | System UTC | Tests and replays |

Properties per engine: [[Rule Engine Configuration]]; the Process Engine list arrives with its 0.2 configuration page.

## Why not a fork or a script hook

A fork stops upgrades and security fixes reaching you. A script hook (Groovy, JavaScript, SpEL) would run arbitrary code inside your service, which the engines refuse by design. If none of the four tools fits your case, open an issue: a new extension point is added in a minor release, without breaking existing hosts.
