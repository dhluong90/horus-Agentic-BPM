# How to use the cockpit

Embed the cockpit in your service or host it standalone, then perform the operator tasks it offers: edit rule drafts, review and approve rule versions, simulate, read the decision log, and (from 0.2) edit tables in a grid and operate processes on their diagrams from one inbox.

Design preview: Rule Engine screens and the host registry describe the Cockpit 0.1 target (M1, 15 Jan 2027). The grid editor, diagrams, merged inbox, tenant switcher and Process Engine screens describe the Cockpit 0.2 target (M2, 26 Mar 2027). Process approval screens and the modeller describe the M3 target (21 May 2027). Scope per version is owned by the [[Roadmap]].

## What it is

A React application built to static assets. It keeps no state and has no backend of its own; it talks only to the REST layer of your services (ADR-0005). Authentication and permissions are your services'; see [[Multi-Tenancy and Security]] and the permission list in [[Rule Engine Configuration]].

Screens sit in three tiers: the shell (sign-in, host and tenant), one area per job (Rules, Processes, Work), and the screens inside each area. The version after each screen is the first cockpit version that has it.

![Cockpit screen hierarchy in three tiers: shell, areas, screens](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/cockpit-screen-hierarchy.svg)

There is no dashboard of counts (a "pulse strip") before 1.0. Each queue shows its own count in its header, for example the number of pending approvals or open incidents.

## Embed it in your service

Add the starter next to the engine starter:

```xml
<dependency>
  <groupId>io.github.dhluong90.bpm</groupId>
  <artifactId>cockpit-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

The starter serves the cockpit at `/bpm/cockpit` behind your service's own sign-in. It adds no authentication of its own: protect the path with your Spring Security configuration as you do any other page. As a second line, the starter answers `401` with no file to a request that reaches it without a signed-in user.

Open `http://localhost:8080/bpm/cockpit` and sign in. Expected in Cockpit 0.1 (first build): the header shows your service, your tenant and your name; the page lists the engines the service runs and what you may do. The Rule sets screen follows in the next build.

```mermaid
sequenceDiagram
    autonumber
    participant B as Browser
    participant H as Your service
    B->>H: GET /bpm/cockpit/
    H-->>B: 302 to your sign-in page (no session)
    B->>H: sign in, then GET /bpm/cockpit/
    H-->>B: index.html and assets, with Content-Security-Policy script-src 'self'
    B->>H: GET /bpm/cockpit/cockpit-hosts.json
    H-->>B: one host, baseUrl "", auth mode host-session
    B->>H: GET /api/bpm/meta and /api/bpm/me with the session cookie
    H-->>B: engines and versions; user, tenant and permissions
    Note over B: Header shows service, tenant and user. A problem answer shows its code and correlationId instead.
```

| Property | Default | Meaning |
|---|---|---|
| `horus.bpm.cockpit.enabled` | `true` | `false` stops serving the cockpit |
| `horus.bpm.cockpit.path` | `/bpm/cockpit` | Path under which the host serves the assets; starts with `/`, no trailing slash |
| `horus.bpm.cockpit.auth-mode` | `host-session` | How the cockpit authenticates to this service. `host-session`: your session cookie, same origin. Sign-in through an identity provider arrives with standalone mode |
| `horus.bpm.cockpit.host-label` | the host id | Name of this service in the cockpit header, up to 80 characters |
| `horus.bpm.cockpit.environment` | none | Short label next to the service name, for example `UAT` |
| `horus.bpm.cockpit.default-locale` | `en` | `en` or `vi`; the language until the user picks one with the EN / VI switch |
| `horus.bpm.host-id` | `self` | Id of this service in the cockpit, kebab-case, up to 40 characters |

What the starter sends with every cockpit file:

| Header | Value |
|---|---|
| `Content-Security-Policy` | `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'self'` |
| `X-Frame-Options` | `SAMEORIGIN`, held on cockpit paths even when Spring Security sends `DENY` elsewhere. Together with `frame-ancestors 'self'`: your admin UI may frame the cockpit on the same origin, no other site can |
| `X-Content-Type-Options` | `nosniff` |
| `Referrer-Policy` | `no-referrer` |
| `Cache-Control` | `no-store` for the page and `cockpit-hosts.json`; `private, max-age=31536000, immutable` for the hashed files under `assets/` |

A cockpit route such as `/bpm/cockpit/rules/card-fee`, reloaded or bookmarked, gets the cockpit page; a missing file under the path stays `404`.

**CSRF in `host-session` mode.** Keep the CSRF token in a cookie the cockpit can read and accept it in the `X-XSRF-TOKEN` header. With Spring Security that is one line: `http.csrf(csrf -> csrf.spa())` (cookie `XSRF-TOKEN`). The starter loads the token whenever a cockpit file is served, so the cookie exists before the cockpit's first change request.

**Tenant in `host-session` mode.** The tenant comes from your session principal, through your `TenantResolver` bean, and never from a header the browser sets.

If the service answers `/api/bpm/me` or `/api/bpm/meta` with an error, the cockpit shows the error `code` and `correlationId` (give the `correlationId` to the service's operators) and does not show a partial header. A `404` there means the service does not serve the host metadata endpoints yet.

Theming is through CSS variables, in a light and a dark theme. Labels exist in English and Vietnamese; one language is shown at a time (TR-UI-04).

An embedded cockpit shows only the service that serves it. To see several services in one cockpit, host it standalone.

An embedded cockpit also works when your web application signs users in with a server-side session and no bearer token. The engine asks its `TenantResolver` for the tenant; the default reads it from the authenticated principal in your Spring Security context, so a session login and a validated token behave the same. The engine never takes the tenant from anything the browser sends. See [[Multi-Tenancy and Security]].

### Where the cockpit opens

A link into a screen always opens that screen. Otherwise the cockpit opens on a landing area chosen by the user's role. This is the host property `horus.bpm.cockpit.landing`, default `by-role`, which the cockpit reads from `/api/bpm/meta` as `cockpit.landing`; the host can set another landing area.

| User | Cockpit 0.1 opens on |
|---|---|
| Approver (`rules:approve`) | Approvals: the queue of pending changes |
| Author (`rules:author`) | Approvals, filtered to the user's own drafts and requests |
| Auditor or support (`rules:log:read` only) | Decisions |
| Anyone else | Rule sets |

From 0.2 the process diagram is the default landing area, but only on a host that runs the Process Engine. A host that runs only the Rule Engine never opens on an empty diagram.

## Host it standalone

![Deployment shapes](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/deployment-shapes.svg)

1. Download the cockpit archive of the matching release and serve it from any static web server.
2. List your services in the cockpit's host registry, one base URL per service (TR-UI-01). The configuration format will be documented here before Cockpit 0.1 ships.
3. Configure one OpenID Connect identity provider whose tokens every listed service accepts. The cockpit signs in once, as a public client with PKCE, and uses that sign-in for every host.
4. Allow the cockpit's origin in each service's CORS configuration; the cockpit calls `/api/bpm/rules/v1` and, from 0.2, `/api/bpm/processes/v1` directly. Allow the request headers the cockpit sends: `Authorization`, `Accept`, `X-Requested-With`, `X-Tenant-Id`, `If-None-Match`, `If-Match` and `Idempotency-Key`.

Each host answers two endpoints the cockpit reads after sign-in:

| Endpoint | Returns | The cockpit uses it to |
|---|---|---|
| `/api/bpm/meta` | Engine and API versions, supported features | Hide screens the host cannot serve. The cockpit supports the current and the previous engine minor version |
| `/api/bpm/me` | Tenant, user, permissions | Hide screens the user has no permission for |

In Cockpit 0.1 you work with one host at a time and switch between hosts from the registry. From 0.2, task and incident lists merge across hosts.

![Standalone cockpit federating several hosts with one OIDC sign-in](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/cockpit-federation.svg)

| Step | What happens |
|---|---|
| 1 | The browser loads the cockpit archive and the host registry from your static web server |
| 2 | The cockpit signs in once at your identity provider: authorization code with PKCE, as a public client. It holds no client secret |
| 3 | For each host it calls `/api/bpm/meta` and `/api/bpm/me` with the same bearer token |
| 4 | It calls the rules and, from 0.2, processes APIs of the selected host. Each host must allow the cockpit origin in CORS |
| 5 | Each host validates the token itself |
| 6 | Each host reads only its own PostgreSQL. No data is copied to a central store |

## Rule Engine tasks (Cockpit 0.1)

| Task | Screen | What you do | What you see |
|---|---|---|---|
| Check which version is live | Rule sets | Open the list | Effective version, pending approvals, last change per rule set |
| Read a version | Editor | Open a version | The table, each row also readable as a plain-language sentence; YAML or JSON view; import and export buttons |
| Edit a draft | Editor | On a draft of an existing rule set, change a cell using the condition shorthand (for example `< 5000000 VND`); add, copy, delete or move a row; save | The draft is validated on save, and each error is shown on its cell |
| Edit in a spreadsheet | Editor | Export the rows as CSV, edit them in a spreadsheet, import the file | A new draft. Errors name the row and column in plain language |
| Compare two versions | Editor, Diff | Pick two versions | Added, removed and changed rows and columns side by side |
| Try a draft | Simulation | Paste rows or upload a CSV, run | Outputs and matched rule id per row; nothing is logged |
| Approve or reject a change | Approvals | Open a pending version as one dossier: effective date, the maker's note, the diff, the old reading struck and the new reading added, and checks. Run the sample simulation yourself if you hold `rules:simulate`. Approve, or reject with a comment | Status changes to published or back to draft. Your own request shows a disabled Approve button; the API refuses it regardless (TR-SEC-04) |
| Investigate a decision | Decisions | Filter by rule set, period, outcome, rule id or output value; open an entry | The rule table with the matched row highlighted, and the plain-language explanation. Masked inputs stay masked |
| Bring back a retired version | Editor | Export the retired version, then import the file as a new draft | A new draft with the retired version's rules, which goes through approval like any draft |
| Copy a rule set to another tenant | Editor | Export it under one tenant, then import the file under the other tenant | A new draft in the other tenant; the two copies then change independently |

Authoring in 0.1 is by cell and row editing in the cockpit, a CSV round trip, or YAML or JSON import; see [[Rule Engine Authoring Decision Tables]]. Creating a new rule set is done by import.

Readings are plain-language sentences by default. The host can choose the shorthand style instead, and can override or extend the sentence patterns per language. If the 8 Jan 2027 checkpoint fails, Cockpit 0.1 ships the shorthand style only and sentences follow in 0.2.

A host that keeps production rule changes in Git can turn off cockpit editing per environment; it is on by default. Imports, approvals and reading still work. The property is `horus.bpm.rules.authoring.cockpit-drafts` ([[Rule Engine Configuration]]).

## Rule Engine tasks added in Cockpit 0.2

| Task | What you do | What you see |
|---|---|---|
| Edit a table in a grid | Change typed cells, start from a template, validate | Validation messages bound to the row and cell; publication request blocked until errors are fixed |

## Process Engine tasks (Cockpit 0.2)

| Area | What you can do |
|---|---|
| Definitions | Browse versions and see each version as a diagram. A definition with no stored layout is laid out automatically |
| Instances | Open an instance and see its diagram with the live token positions, its history timeline, and per step when it ran, how long it took, the attempts and who acted |
| Inbox | Work one task inbox merged across the hosts in the registry, ordered by due date; claim, complete, reassign; filter to SLA breaches |
| Task forms | Complete a task through the form it declares, or follow its link to the host's own screen |
| Agent tasks | Inspect bounded inputs, the JSON Schema verdict, the agent's output and the guardrail rules that fired; approve or override an escalated task with a reason |
| Incidents | Work one incident list merged across hosts; read cause and retry count; retry or resolve |
| Compensation runs | See which handlers ran, in which order, with which result |
| SLA | List breaches |
| Tenant switcher | If your token lists more than one allowed tenant, switch between them. The host accepts only a tenant in that list. Each screen shows one tenant; no screen combines tenants |

Live counts of active tokens and incidents on each step of a definition diagram are planned for 0.2 and move to M3 if the 5 Mar 2027 checkpoint fails.

![Where the diagram, token and heatmap overlays get their data](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/cockpit-diagram-overlay.svg)

The counts come from an index on the live tokens, never from a query over history. The heatmap (M3) reads an hourly rollup that the engine's scheduler fills a few minutes behind real time. Nothing is copied out of your service; for longer analysis, send the engine events to your own data warehouse.

Behaviour behind these screens is described in [[Process Engine Overview]].

## Process approval

Process definitions follow the same governed lifecycle as rule sets: draft, pending approval, active, retired. The requester can never approve their own change, and the approved version takes effect on its effective date.

![Process definition maker-checker with graph diff](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/cockpit-process-approval.svg)

| Part | When | What it means for you |
|---|---|---|
| Engine | M2, 26 Mar 2027 | Deploying a definition creates a draft. A second identity approves it through the REST API or your own tooling. A definition imported from Git also arrives as a draft, and its content hash shows whether the running version matches Git. A host can turn off draft creation from the cockpit per environment (on by default). Only the `dev` profile may approve automatically |
| Approval screens | M3, 21 May 2027 | Review the change as a graph diff (steps and connections added, removed or changed), then approve or reject. Approve is disabled for the requester |
| Modeller | M3, 21 May 2027 | Change a process on a canvas that offers only the steps your host has registered. The engine validates on save, and the result is a draft that needs approval. If the M3 checkpoint fails, the modeller moves to 1.1 |

Moving a box on a diagram changes only the layout. It needs no approval; see principle 6 in [[Principles]].

## Added in M3 (21 May 2027)

| Task | What you see |
|---|---|
| Replay history through a draft | Choose "Replay last N decisions" in Simulation; count and breakdown of changed outcomes |
| Edit business calendars | Working hours and holidays per tenant. Moves after 1.0 if the M3 checkpoint fails |
| Instance migration | Create a plan, map renamed steps, run the dry run, execute in batches with progress; the plan shows as two diagrams side by side with the mapped steps. Until M3, engineers run migrations through the REST API |
| Read a stored simulation report | The report attached to a publication request, in the dossier. A host can require a report before a request is accepted, per rule set; off by default |
| Confirm a retirement | Only when the host sets the retirement approval mode to `four-eyes`: a second `rules:approve` holder confirms a retire request, never the requester. The version keeps serving until then. Moves to 1.1 if the M3 checkpoint fails |
| Edit nested conditions | An optional condition builder lens for And, Or and Not groups in one row, off by default; the host turns it on. Moves after 1.0 if the M3 checkpoint fails |
| See where processes slow down | A heatmap on the definition diagram: counts, incidents and p50 and p95 durations per step for a chosen window. Moves after 1.0 if the M3 checkpoint fails |

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Blank page at `/bpm/cockpit` | Starter missing, or a different `horus.bpm.cockpit.path` | Check the dependency and the property |
| Standalone cockpit shows "cannot reach service" | CORS not allowed, or wrong base URL | Allow the cockpit origin in the host; check the base URL in the host registry |
| A screen is missing for one host | The host's engine version does not support it, or you lack the permission | Check `/api/bpm/meta` and `/api/bpm/me` on that host |
| Approve button disabled for you | You requested this publication | Ask a different identity with `rules:approve` |
| No edit controls on a draft | Cockpit editing is turned off in this environment, or you lack `rules:author` | Change the rule set in Git and import it, or map the permission to your role |
| Decisions screen is empty or forbidden | You lack `rules:log:read` | Map the permission to your role |
| A tenant is not offered in the tenant switcher | It is not in your token's list of allowed tenants | Ask your identity provider administrator to add it |
| Inputs shown as `***` | They are declared `mask: true` | Expected; the plaintext is never stored (TR-SEC-05) |
