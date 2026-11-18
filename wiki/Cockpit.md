# Cockpit

The operations dashboard for both engines. A React application shipped as static assets; embed it in your admin UI or run it standalone against your service's REST API. _Design preview._

Cockpit 0.1 (with Rule Engine 0.1) is a **viewer and approver**: rule sets and versions, diff, simulation, approvals with maker-checker, decision log with explain. Authoring in 0.1 is by YAML/JSON import. Cockpit 0.2 adds the interactive grid editor and the Process Engine screens.

## Rule Engine screens
- **Rule sets**: list with effective version, pending approvals, last change.
- **Editor** (0.2): decision table as a grid with typed cells, validation messages per row, YAML view. In 0.1: YAML/JSON view, import and export, version diff.
- **Simulation**: sample inputs, replay of history, change summary before publishing.
- **Approvals**: pending versions with diff and simulation report; approve or reject with comment. The requester's approve button is disabled and the API refuses it regardless.
- **Decisions**: searchable decision log; open a decision to see the highlighted matched row and the explanation.

## Process Engine screens (milestone 2)
Definitions and versions, instances with their current step, task queues per role, agent tasks with their inputs and outputs, retry and reassign, SLA breaches, history timeline, compensation runs, **instance migration** (plan, dry-run report, batched execution with progress), and **business calendars** (working hours, holidays per tenant).

## Embedding
```xml
<dependency><groupId>com.horus.bpm</groupId><artifactId>cockpit-spring-boot-starter</artifactId></dependency>
```
Serves the cockpit at `horus.bpm.cockpit.path` (default `/bpm/cockpit`) using the host's authentication. Theming through CSS variables; labels in English and Vietnamese, extensible.

## Access
Screens follow the permissions in [[Rule Engine Configuration]]. Viewing the decision log requires `rules:log:read`; masked inputs stay masked in the cockpit.
