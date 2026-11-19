# Configuration and Extension Points

The engines are generic by design ([[Principles]] 13): nothing in them is specific to one bank, product or country. Every business behaviour is optional and configurable per host or tenant, with a safe default. You shape the engines to your business with four tools, and you upgrade them by changing a version number. Decision record: ADR-0024.

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
| Safe default, documented range, tested | Every setting has a default and a range; the default and each boundary are tested. A setting that no named user needs is not added |
| Documented | Every property has a description and a default in your IDE's Spring configuration hints |
| Visible at run time | Every setting appears in the `settings` object of the host's `/api/bpm/meta` with its effective value, so an operator or auditor sees what is in force without reading `application.yaml`. Secrets such as `masking.hmac-key` show only as `set` or `unset`. A setting that relaxes a control is listed whenever it is on (`TR-EXT-08`) |
| Protective limits only go down | Page sizes, ranges, batch sizes and definition sizes may be lowered, never raised |
| Controls stay on | Maker-checker, the audit trail, idempotency, tenant isolation, masking and fail-closed guardrails can be tightened, never switched off outside the `dev` profile. A setting that relaxes a control is off by default, logged when used and shown in `/api/bpm/meta`: the emergency single-approver path exists only when you turn it on, and every use is logged |
| No business values in engine code | Currencies, tiers, fee codes, reason codes, roles, languages, calendars and SLA periods come from your definitions, configuration or beans. Examples and seed data may use banking data; the engine core does not |

## Extension points

Each is an interface with an engine default. Declare a bean of the same type and the engine uses yours; you do not exclude any auto-configuration.

| Extension point | Engine | Default | Replace it when |
|---|---|---|---|
| `TenantResolver` | both | The `tenancy.claim` attribute (`tenant_id`) of the authenticated principal: a validated token or a server-side session login. Never a value the browser sends, except a switch to a tenant in the user's allowed list | The tenant comes from your user directory, a realm, a path or a subdomain |
| `CallerResolver` | both | Principal name | Audit needs another identity |
| `RuleFunctionRegistry` | rules | Built-in pure functions | You add pure functions for authors |
| `CurrencyRegistry` | rules | ISO 4217 scales | You trade a currency without an ISO scale, such as gold |
| `DecisionExplainer` | rules | Explanation from a message catalogue per language (English and Vietnamese) | You want house wording or another language in API answers |
| `LifecycleGuard` | both | Allows every transition | You need an extra check, such as a change ticket on retire. A guard can refuse; it can never let through what the engine refuses |
| `BusinessCalendar` | processes | 24x7 | SLAs count business hours and holidays |
| `StepRegistry`, `FormRegistry`, `RoleCatalogue` | processes | Empty | You offer handlers, forms and roles to authors |
| `AgentAdapter`, `ServiceAdapter` | processes | None | Your definitions call models or services |
| Event publisher | both | Spring application events | Events go to an outbox or a bus |
| `Clock` | both | System UTC | Tests and replays |

Properties per engine: [[Rule Engine Configuration]]; the Process Engine list arrives with its 0.2 configuration page.

## Business choices that are settings

Where businesses differ, the engines offer a setting with a safe default instead of one fixed behaviour.

| Choice | Default | Other option | From |
|---|---|---|---|
| Who may edit rule drafts in the cockpit, per environment | Cockpit editing on | Off, for hosts that keep production changes in Git | M1 |
| How the cockpit reads a row | Plain-language sentence | Condition shorthand; your own sentence patterns per language | M1 |
| Where the cockpit opens | By the user's permissions | A landing area the host sets | M1 |
| Where the tenant comes from | The authenticated principal | Your own `TenantResolver` bean | M1 |
| How errors in an expression behave | `strict` for rule sets, `spec` for imported DMN models | Set per model in a new, approved version; guardrails fail closed in every mode | 1.1 (target) |
| How the decision log is written | In your transaction | Asynchronous, through an outbox, for high-volume paths | M2 |
| Evidence before a publication request | Not required | A stored simulation report, per rule set | M3 |
| Retiring a live rule version by hand | One approver with a reason | A second approver confirms (`four-eyes`) | M3 |
| Nested condition editing | Hidden | An optional builder lens | M3 |
| Removing a tenant's data | No purge | A governed purge after export | 1.1 (target) |

## Why not a fork or a script hook

A fork stops upgrades and security fixes reaching you. A script hook (Groovy, JavaScript, SpEL) would run arbitrary code inside your service, which the engines refuse by design. If none of the four tools fits your case, open an issue: a new extension point is added in a minor release, without breaking existing hosts.
