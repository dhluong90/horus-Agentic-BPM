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

Open `http://localhost:8080/bpm/cockpit` and sign in. Expected in Cockpit 0.1 (first build): the header shows your service, your tenant and your name; the page lists the engines the service runs and what you may do. Rules lists the rule sets of your tenant.

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
| `horus.bpm.cockpit.auth-mode` | `host-session` | How the cockpit authenticates to this service. `host-session`: your session cookie, same origin. Sign-in through an identity provider is for the standalone cockpit ([Host it standalone](#host-it-standalone)) |
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

**CSRF in `host-session` mode.** Keep the CSRF token in a cookie the cockpit can read and accept it in the `X-XSRF-TOKEN` header. With Spring Security: `CookieCsrfTokenRepository.withHttpOnlyFalse()` (cookie `XSRF-TOKEN`) and a token handler that accepts the raw value in the header; `examples/rule-engine-only` shows one (`SpaCsrfTokenRequestHandler`) that works on Spring Security 6.5 and 7. On Spring Security 7 alone, `http.csrf(csrf -> csrf.spa())` does the same. The starter loads the token whenever a cockpit file is served, so the cookie exists before the cockpit's first change request.

**Tenant in `host-session` mode.** The tenant comes from your session principal, through your `TenantResolver` bean, and never from a header the browser sets.

If the service answers `/api/bpm/me` or `/api/bpm/meta` with an error, the cockpit shows the error `code` and `correlationId` (give the `correlationId` to the service's operators) and does not show a partial header. A `404` there means the service does not serve the host metadata endpoints yet.

Theming is through CSS variables, in a light and a dark theme. Labels exist in English and Vietnamese; one language is shown at a time (TR-UI-04), and the EN / VI switch in the top bar changes every label at once. Every control of the shell is reachable with Tab, opened with Enter and closed with Esc, with a visible focus ring. Each pull request that touches the cockpit runs an axe-core audit in light and dark at 1440x900 and 1440x1200, and a coverage check that fails the build and names any key missing in Vietnamese.

### Link to one decision

A host screen can link to one decision: `<cockpit>/#/decisions/{decisionId}`, with `?host={hostId}` when the cockpit lists several services (PD-108). The link survives sign-in. It carries nothing else: no tenant, no token, no customer data. Without `host` while several services are listed, the cockpit asks which service logged the decision and requests nothing until one is chosen. A user without permission to read the decision log reads "You do not have permission to open decisions."; a missing decision and another tenant's decision read the same; an id that is not a decision id reads "This link is not valid." and nothing is requested.

An embedded cockpit shows only the service that serves it. To see several services in one cockpit, host it standalone.

An embedded cockpit also works when your web application signs users in with a server-side session and no bearer token. The engine asks its `TenantResolver` for the tenant; the default reads it from the authenticated principal in your Spring Security context, so a session login and a validated token behave the same. The engine never takes the tenant from anything the browser sends. See [[Multi-Tenancy and Security]].

### Where the cockpit opens

A link into a screen always opens that screen. Otherwise the cockpit opens on Home, whose content follows the user's role: an approver sees the requests waiting for them first, an author their own drafts and requests, anyone else the recently changed rule sets. The host property `horus.bpm.cockpit.landing`, read from `/api/bpm/meta` as `cockpit.landing`, is `home` by default and can be set to `rules` or `approvals`; `decisions` arrives with 0.2.0.

| User | Cockpit 0.1 opens on |
|---|---|
| Approver (`rules:approve` or `processes:approve`) | Approvals: one queue of pending rule and process changes |
| Author (`rules:author`) | Approvals, filtered to the user's own requests |
| Auditor or support (`rules:log:read` only) | Decisions |
| Anyone else | Rule sets |

From 0.2 the process diagram is the default landing area, but only on a host that runs the Process Engine. A host that runs only the Rule Engine never opens on an empty diagram.

## Host it standalone

![Deployment shapes](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/deployment-shapes.svg)

1. Download the cockpit archive of the matching release and serve it from any static web server. It holds `index.html`, `assets/` and the sign-in callback page `auth/callback/index.html`.
2. Put `cockpit-hosts.json` next to `index.html`, one entry per service (TR-UI-01). The cockpit checks it before it calls any service; a file that breaks a rule shows the location of the first problem, such as `/hosts/1/baseUrl`, and nothing else is called.
3. Register the cockpit at your OpenID Connect identity provider as a public client (no secret) with the redirect URI `<cockpit address>/auth/callback/`, trailing slash included, and give each service a scope that yields a token with that service's audience.
4. Set `horus.bpm.rest.cors.allowed-origins` to the cockpit's exact origin and `horus.bpm.security.audiences` to the host's audience on each service (see [[Rule Engine Configuration]]); to let a user switch tenants, also `horus.bpm.tenancy.switching.enabled: true`. The cockpit calls `/api/bpm/rules/v1` and, from 0.2, `/api/bpm/processes/v1` directly. Allow the request headers the cockpit sends: `Authorization`, `Accept`, `X-Requested-With`, `X-Tenant-Id`, `If-None-Match`, `If-Match` and `Idempotency-Key`.
5. Send a `Content-Security-Policy` whose `connect-src` lists exactly the service origins and the identity provider, so a changed host list cannot reach a new origin.

A sample `cockpit-hosts.json`:

```json
{
  "schemaVersion": 1,
  "auth": {
    "mode": "oidc",
    "issuer": "https://idp.bank.example/realms/operations",
    "clientId": "horus-cockpit",
    "scopes": ["profile"],
    "tokenMode": "per-host"
  },
  "hosts": [
    { "id": "loans", "label": { "en": "Loan origination", "vi": "Khởi tạo khoản vay" },
      "baseUrl": "https://loans.api.bank.example", "scope": "loans-bpm", "environment": "production" },
    { "id": "kyc", "label": "KYC onboarding",
      "baseUrl": "https://kyc.api.bank.example", "scope": "kyc-bpm", "environment": "production" }
  ],
  "ui": { "defaultLocale": "vi" }
}
```

| Rule | Detail |
|---|---|
| Addresses | `https://` only; `http://localhost` for development. Two services may not share an id or an address |
| Scopes | With `tokenMode: per-host` (the default) every enabled service needs its `scope` |
| Sign-in | Once per page load: authorization code with PKCE (`S256`), `state` and `nonce`. The sign-in request names every service's scope; each service's token is then requested with its own scope, so a token works at one service only |
| Tokens | In memory only, never in `localStorage`, `sessionStorage` or a cookie. A reload signs in again through the identity provider's session. A token is renewed a minute before it expires |
| Address | The service comes first in the address: `#/hosts/loans/rules`. Choosing another service in the top bar opens the same area there and shows only that service's data |
| Versions | A cockpit release supports its own engine minor and the one before; Cockpit 0.3 works with engines 0.2 and 0.3. A service outside that range shows its engine version and the supported versions, and no screens |
| Errors | A service that refuses `/api/bpm/me` shows the error code and the reference for that service; the other services stay usable |
| Token renewal | A renewal the identity provider answers with a server error or `429` keeps the session: that service reads as unreachable and the next request tries again. Only a refused refresh token ends the session |
| Tenant | With `horus.bpm.tenancy.switching.enabled: true` on the service and more than one tenant in the token's `allowed_tenants` claim, a tenant switcher sits beside the service in the top bar and lists only those tenants. Choosing one reloads that service's screens for it. A tenant the service refuses (`403 TENANT_NOT_ALLOWED`) brings you back to your own tenant with one sentence and the reference. With switching off, or one tenant, there is no switcher |

Each host answers two endpoints the cockpit reads after sign-in:

| Endpoint | Returns | The cockpit uses it to |
|---|---|---|
| `/api/bpm/meta` | Engine and API versions, supported features | Hide screens the host cannot serve. The cockpit supports the current and the previous engine minor version |
| `/api/bpm/me` | Tenant, user, permissions | Hide screens the user has no permission for |

In Cockpit 0.1 you work with one host at a time and switch between hosts from the registry. From 0.2, choose **All services** on Tasks or Incidents to merge the list across every host you can reach (see Tasks and Incidents below).

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
| Compare two versions | Editor, Changes | Open a version and choose Changes; it is compared with the version in effect | Both versions side by side, one rule per line: added, changed, moved and removed rows marked in words and colour, the old value struck beside the new one, each version's effective date and content hash in its header, and changes to columns or the hit policy as sentences above the table. The comparison comes from the engine (`GET .../diff`), not the browser. A value with a character outside plain ASCII, such as a Cyrillic letter that looks like a Latin one, is flagged |
| Try a draft | A draft, Simulate | Type samples, or paste rows from a spreadsheet or CSV (an optional name, then one column per input), and choose Run simulation. Masked inputs show as dots | How many results change, then each sample by its reference (S1, S2) and name, with the draft's outcome beside the version in effect, both in the engine's plain words; nothing is logged. Simulate is offered on drafts only |
| Approve or reject a change | Approvals | Open a pending version as one dossier: the side-by-side diff with each version's effective date, the maker's note, the old reading struck and the new reading added, and checks. Run the sample simulation yourself if you hold `rules:simulate`. Approve, or reject with a comment | The decision sits in a bar below the diff that stays on screen while you read it. Status changes to published or back to draft. Your own request shows a disabled Approve button with one line saying why; the API refuses it regardless (TR-SEC-04). Approvals lists rule and process changes together, oldest first; a process change opens on its diagram diff. If the content changed after you opened it, the screen says so and asks you to review it again |
| Move running cases to a new version | Processes, then the active version, then Move running cases | Say what happens at each step the old version has and the new one does not: move its work to a renamed step, or cancel it with a reason and continue at another step. Create the plan, then Check plan: it reads "50 cases can move. 2 cannot." in words, with the old and new diagrams side by side and the carried-over steps marked and linked. Add a reason and ask for approval | One action at a time sits in a bar below: Check plan, ask for approval, then (a second person) Approve. The person who proposed the move sees Approve disabled with one line saying why. Approve names the proposal the approver was shown. The run shows "40 of 52 moved"; cases left on the old version are listed with their reason in words, and a masked reference stays masked |
| Investigate a decision | Decisions | Choose a rule set or a case reference and a month, show the decisions, open one | The decisions newest first with when, the rule set and version, the outcome in words, the case reference and a short decision id. One decision opens with the engine's plain-language explanation, then three views: the matched rule's conditions with the recorded values, every logged input, and the rule table with the matched row marked. Masked inputs show as "Masked" with a padlock and a short keyed-hash reference, never the value. Without `rules:log:read` the screen says so and asks the engine for nothing |
| Read a rule set's history | A rule set, History | Open the tab | One line per version, newest first: status, when it was in effect, who created, sent, approved or rejected it, and the comments. Each line opens to its events. Evidence exports are listed below it |
| Export evidence | A rule set, History, **Export evidence** | Tick versions, and decisions of a month if you hold `rules:log:read` (at most 100), pick CSV or JSON, export | The engine writes, hashes and records the file (`POST .../evidence`); the browser saves its bytes unchanged. The card names what was exported, the file and its size, and the SHA-256 as a short reference with **Copy SHA-256**. A refused export says why in one line, for example "The file would be larger than 8 MB. Choose fewer decisions." |
| Bring back a retired version | Editor | Export the retired version, then import the file as a new draft | A new draft with the retired version's rules, which goes through approval like any draft |
| Copy a rule set to another tenant | Editor | Export it under one tenant, then import the file under the other tenant | A new draft in the other tenant; the two copies then change independently |

Authoring in 0.1 is by cell and row editing in the cockpit, a CSV round trip, or YAML or JSON import; see [[Rule Engine Authoring Decision Tables]]. Creating a new rule set is done by import.

### Import and export a file

| Action | Where | Who | What happens |
|---|---|---|---|
| Import a new rule set | Rules, **Import rule set** | `rules:author` | A YAML, JSON or DMN file becomes version 1, as a draft (`POST /rule-sets`) |
| Import the next version | A rule set, **Import new version** | `rules:author` | The file becomes the next draft version and opens (`POST /rule-sets/{name}/versions`). The message "Draft version N created" appears only after the engine answers `201` |
| Export a version | A rule set, **Export**, then **As JSON** or **As DMN decision table** | `rules:read` | The browser saves `<name>-v<version>.json` or `.dmn`, byte for byte as the engine sent it (`GET .../export?format=json` or `dmn`). The JSON file imports back with the same content hash |

Every import carries an `Idempotency-Key`, so a double click creates one version. A file the engine refuses creates nothing: the cockpit says "Import refused. Nothing was saved." and names the file, the row, the column and the reason, for example "card-fee-bad.yaml, row R4, column Balance: compares a money amount with a plain number". A version imported on the page shows "Imported by lan from card-fee-v3.yaml" in its details. An export the engine refuses, such as a DMN export of a version with no decision table form, is shown the same way. YAML export arrives when the engine offers it. Import and export stay available when the host turns cockpit editing off.

Readings are plain-language sentences by default. The host can choose the shorthand style instead, and can override or extend the sentence patterns per language. If the 8 Jan 2027 checkpoint fails, Cockpit 0.1 ships the shorthand style only and sentences follow in 0.2.

A host that keeps production rule changes in Git can turn off cockpit editing per environment; it is on by default. Imports, approvals and reading still work. The property is `horus.bpm.rules.authoring.cockpit-drafts` ([[Rule Engine Configuration]]).

## Rule Engine tasks added in Cockpit 0.2

| Task | What you do | What you see |
|---|---|---|
| Edit a table in a grid | On a draft you wrote, choose **Edit draft**; on the version in effect, **New draft** first stores a copy of it as the next draft and opens that. Change typed cells and rows; the arrow keys move between cells, Enter checks a cell and Esc puts it back | Each cell you leave or confirm is checked by the engine (`POST .../versions/{v}:validate`), which stores nothing. Its problems show on their row and cell in your language; an empty amount says what to type, for example `Enter an amount, for example 50,000,000 VND.`, with the engine's sentence under it. **Changes in this draft** lists only cells you have left and the engine accepted. **Save draft** stays disabled, with the reason beside it, until the cells are fixed, and saves into the draft with the lock version you opened (`PUT .../versions/{v}`, `If-Match`). If someone else saved the draft in the meantime, your edits stay on screen, the save bar names the cells they changed, and you choose **Load the latest version**. Requesting publication is a separate step on the saved draft. An approved version is never edited, and a draft another person wrote is read only for you |
| Discard a draft | On a draft you wrote, choose **Discard draft**, add a reason if you like, and confirm | The button shows only where the engine offers it: on your own draft, with `rules:author`. Nothing changes before the engine answers (`POST .../versions/{v}:discard`, `If-Match`). Then the draft leaves every list and the screen returns to the rule set's version in front. Nothing is deleted: the history shows the version as Discarded, and its number is never used again |

## Process Engine tasks (Cockpit 0.2)

| Area | What you can do |
|---|---|
| Definitions | Browse versions and see each version as a diagram. A definition with no stored layout is laid out automatically. Available now: the list of definitions and the diagram of the version in effect, laid out left to right, with a step list; a step type the cockpit does not know shows as a dashed box naming its type. Stored layouts follow |
| Instances | Open an instance and see its diagram with the live token positions, its history timeline, and per step when it ran, how long it took, the attempts and who acted. Available now: a definition's Cases view lists its instances newest first; a case opens on its diagram, centred on the step where it is stuck, with a badge in words on the step where it waits and on a step with an open incident, that step selected first, and its activity in a panel beside the diagram; a wide case pans by drag or arrow keys, with a minimap and a Whole case overview, with Open the task when a task waits there. The breadcrumb of a task leads back to its case and process. The Timeline view lists the same history in time order. When a saga undid steps after a failure, the Timeline also lists each undo step, marked with the symbol and the word "Undo", in the order the engine ran them, with a line per saga scope saying whether it is undone, being undone or not fully undone; a failed undo reads calmly with its attempts and **Open the incident**, which opens that incident in the Incidents queue, and the diagram marks undone steps with a label. Every figure comes from the engine's activities read; the code of an incident is under Details |
| Tasks | Available now for one host: My tasks, Available to me and, with `processes:task:manage`, All tasks, each in due-time order with overdue tasks marked in words. **Overdue** beside the tabs shows the count of the tab's overdue tasks and, turned on, only those, oldest deadline first; which tasks are overdue is the engine's answer, and the filter stays in the link. Claim a task, release it back to the queue, or open it. A supervisor reassigns a task to a colleague's user id with a required reason (1 to 500 characters), which the engine records in the task history; a colleague the engine refuses (missing role or group, or four eyes) is named in a plain sentence and the task stays where it was. **All services** merges the lists of every registered host where `/me` lists `processes:task:work`: your tasks and tasks available to you, in exact due-date order, each row labelled with its host. A host that times out (`refresh.hostTimeoutSeconds`, 5 s by default) or fails is named in a banner ("Cards unreachable, list incomplete") and the other hosts' rows stay. **Load more** reads only the host at the frontier, so the order stays exact. The list refreshes every `refresh.inboxSeconds`; it sends `If-None-Match` when a host gave an `ETag` and keeps the page on `304`, and reads in full when there is none. Claim, release and reassign go to the host that owns the task, with that host's permissions, four-eyes rule and masking |
| Task forms | Available now: open a task from Tasks (**Open task**) to see the form its process declares, with text, number, money, date, choice and yes/no fields in your language. Fill it in and choose **Complete task**: the cockpit checks only the format, the engine checks the rest and any refusal appears under the field it names. A masked field shows one **Masked** chip and is never sent unless you choose **Replace value**. A task declared with a link shows **Open the screen**, which opens the host's own screen in a new tab. A task that declares no form says so. See [[Process Engine: API\|Process-Engine-API]] |
| Agent tasks | Inspect bounded inputs, the JSON Schema verdict, the agent's output and the guardrail rules that fired; approve or override an escalated task with a reason. Available now from the review's link: masked values stay masked, the person who started the case or reported the answer cannot decide it, and an answer with masked values can only be overridden |
| Incidents | Available now for one host: open incidents newest first with the step, the cause in words and the retry count, and a second list of results waiting for approval. **Retry** runs the step again with a reason; the row reads Retrying until the step is accepted. **Resolve** proposes the result the step should have returned, entered as named fields (text, number, amount, yes or no), with a reason; under the default `incidents.resolve-approval: four-eyes` a colleague with `processes:incident:manage` approves or rejects it, never the proposer (PD-126). An undo that must be done by hand is resolved, not retried. Readers with `processes:read` see the lists without actions. **All services** merges open incidents (and results waiting for approval) of every host where `/me` lists `processes:read`, newest first, each row labelled with its host and with the same incomplete banner. Retry, Resolve, Approve and Reject go to the host that owns the incident, with its own `Idempotency-Key` and `If-Match`, and its own four-eyes rule. Refresh follows `refresh.incidentsSeconds`, with `If-None-Match` as for tasks |
| Compensation runs | See which handlers ran, in which order, with which result |
| SLA | The Overdue filter of the task queue (PD-024) |
| Tenant switcher | If your token lists more than one allowed tenant, switch between them. The host accepts only a tenant in that list. Each screen shows one tenant; no screen combines tenants |

Live counts of active tokens and incidents on each step of a definition diagram are planned for 0.2 and move to M3 if the 5 Mar 2027 checkpoint fails.

![Where the diagram, token and heatmap overlays get their data](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/cockpit-diagram-overlay.svg)

The counts come from an index on the live tokens, never from a query over history. The heatmap (M3) reads an hourly rollup that the engine's scheduler fills a few minutes behind real time. Nothing is copied out of your service; for longer analysis, send the engine events to your own data warehouse.

Who may reassign, and why a reason is required, is decided in [PD-110](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/docs/product/decisions.md#pd-110-reassigning-a-task-needs-a-reason-and-the-manage-permission). Reassigning a role or group task needs the host's `AssigneeDirectory` (see [[Configuration and Extension Points]]); without it every such reassignment is refused.

Behaviour behind these screens is described in [[Process Engine Overview]].

## Process approval

Process definitions follow the same governed lifecycle as rule sets: draft, pending approval, active, retired. The requester can never approve their own change, and the approved version takes effect on its effective date.

![Process definition maker-checker with graph diff](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/cockpit-process-approval.svg)

| Part | When | What it means for you |
|---|---|---|
| Engine | M2, 26 Mar 2027 | Deploying a definition creates a draft. A second identity approves it through the REST API or your own tooling. A definition imported from Git also arrives as a draft, and its source hash shows which committed file in Git it was imported from; the engine runs only the approved canonical form, checked against its hash. A host can turn off draft creation from the cockpit per environment (on by default). Only the `dev` profile may approve automatically |
| Approval screens | Available now, from Processes | A key whose latest version waits for approval shows **Review change**. The version opens on **Changes**: the engine's diff from the version in effect (`GET .../diff`) drawn on the proposed diagram (added and changed steps and links) or on the diagram in effect (removed ones), one at a time, each mark in colour with its sign, and listed in words beside it: `Assignee › Role: CREDIT_OFFICER → SENIOR_CREDIT_OFFICER`, money with separators. Choosing an entry shows it on the diagram that draws it. The decision stays docked below. **Approve change** sends the content hash you first saw on the page (`expectedDefinitionHash`) with the version's lock version; if the content moves on while you read, Approve waits until you confirm you checked it again. The person who requested or imported the version sees Approve disabled with one line saying why, and no Reject. A change list cut off at 1,000 values is too large to approve here: Approve is disabled with the reason and nothing is sent; Reject, with a reason, still works. A `409 CONTENT_CHANGED` is never retried: reload the changes first |
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
| Edit draft disabled: "Only lan can change this draft" | Only the author of a draft may replace it; the engine refuses anyone else | Import your own change as a new version, or ask the author |
| "Someone else saved this draft after you opened it" | The draft's lock version moved on while you edited | Choose **Load the latest version**, then make your change again |
| Decisions screen is empty or forbidden | You lack `rules:log:read` | Map the permission to your role |
| A tenant is not offered in the tenant switcher | It is not in your token's list of allowed tenants | Ask your identity provider administrator to add it |
| Inputs shown as `***` | They are declared `mask: true` | Expected; the plaintext is never stored (TR-SEC-05) |
