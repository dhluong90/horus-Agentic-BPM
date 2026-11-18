# How to use the cockpit

Embed the cockpit in your service or host it standalone, then perform the operator tasks it offers: review and approve rule versions, simulate, read the decision log, and (from 0.2) edit tables and operate processes on their diagrams from one inbox.

Design preview: Rule Engine screens and the host registry describe the Cockpit 0.1 target (M1, 15 Jan 2027). The grid editor, diagrams, merged inbox, tenant switcher and Process Engine screens describe the Cockpit 0.2 target (M2, 26 Mar 2027). Process approval screens and the modeller describe the M3 target (21 May 2027). Scope per version is owned by the [[Roadmap]].

## What it is

A React application built to static assets. It keeps no state and has no backend of its own; it talks only to the REST layer of your services (ADR-0005). Authentication and permissions are your services'; see [[Multi-Tenancy and Security]] and the permission list in [[Rule Engine Configuration]].

Screens sit in three tiers: the shell (sign-in, host and tenant), one area per job (Rules, Processes, Work), and the screens inside each area. The version after each screen is the first cockpit version that has it.

![Cockpit screen hierarchy in three tiers: shell, areas, screens](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/cockpit-screen-hierarchy.svg)

## Embed it in your service

Add the starter next to the engine starter:

```xml
<dependency>
  <groupId>com.horus.bpm</groupId>
  <artifactId>cockpit-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

Open `http://localhost:8080/bpm/cockpit`. Expected: the Rule sets screen lists `loan-eligibility` (from [[Getting Started]]) with its effective version.

| Property | Default | Meaning |
|---|---|---|
| `horus.bpm.cockpit.path` | `/bpm/cockpit` | Path under which the host serves the assets |

Theming is through CSS variables. Labels exist in English and Vietnamese and can be extended (TR-UI-04).

An embedded cockpit shows only the service that serves it. To see several services in one cockpit, host it standalone.

## Host it standalone

![Deployment shapes](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/deployment-shapes.svg)

1. Download the cockpit archive of the matching release and serve it from any static web server.
2. List your services in the cockpit's host registry, one base URL per service (TR-UI-01). The configuration format will be documented here before Cockpit 0.1 ships.
3. Configure one OpenID Connect identity provider whose tokens every listed service accepts. The cockpit signs in once, as a public client with PKCE, and uses that sign-in for every host.
4. Allow the cockpit's origin in each service's CORS configuration; the cockpit calls `/api/bpm/rules/v1` and, from 0.2, `/api/bpm/processes/v1` directly.

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
| Read a version | Editor | Open a version | YAML or JSON view; import and export buttons |
| Compare two versions | Editor, Diff | Pick two versions | Added, removed and changed rows and columns side by side |
| Try a draft | Simulation | Paste rows or upload a CSV, run | Outputs and matched rule id per row; nothing is logged |
| Approve or reject a change | Approvals | Open a pending version, read the diff, approve or reject with a comment | Status changes to published or back to draft. Your own request shows a disabled Approve button; the API refuses it regardless (TR-SEC-04) |
| Investigate a decision | Decisions | Filter by rule set, period, outcome, rule id or output value; open an entry | The rule table with the matched row highlighted, and the plain-language explanation. Masked inputs stay masked |
| Bring back a retired version | Editor | Export the retired version, then import the file as a new draft | A new draft with the retired version's rules, which goes through approval like any draft |
| Copy a rule set to another tenant | Editor | Export it under one tenant, then import the file under the other tenant | A new draft in the other tenant; the two copies then change independently |

Authoring in 0.1 is by YAML or JSON import; see [[Rule Engine Authoring Decision Tables]].

## Rule Engine tasks added in Cockpit 0.2

| Task | What you do | What you see |
|---|---|---|
| Edit a table in a grid | Change typed cells, validate | Validation messages bound to the row and cell; publication request blocked until errors are fixed |

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
| Instance migration | Create a plan, run the dry run, execute in batches with progress; the plan shows as two diagrams side by side with the mapped steps |
| See where processes slow down | A heatmap on the definition diagram: counts, incidents and p50 and p95 durations per step for a chosen window. Moves after 1.0 if the M3 checkpoint fails |

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Blank page at `/bpm/cockpit` | Starter missing, or a different `horus.bpm.cockpit.path` | Check the dependency and the property |
| Standalone cockpit shows "cannot reach service" | CORS not allowed, or wrong base URL | Allow the cockpit origin in the host; check the base URL in the host registry |
| A screen is missing for one host | The host's engine version does not support it, or you lack the permission | Check `/api/bpm/meta` and `/api/bpm/me` on that host |
| Approve button disabled for you | You requested this publication | Ask a different identity with `rules:approve` |
| Decisions screen is empty or forbidden | You lack `rules:log:read` | Map the permission to your role |
| A tenant is not offered in the tenant switcher | It is not in your token's list of allowed tenants | Ask your identity provider administrator to add it |
| Inputs shown as `***` | They are declared `mask: true` | Expected; the plaintext is never stored (TR-SEC-05) |
