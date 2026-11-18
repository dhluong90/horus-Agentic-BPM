# Cockpit

The operations dashboard for both engines. A React application shipped as static assets; embed it in your admin UI or run it standalone against your service's REST API. _Design preview._

## Rule Engine screens
- **Rule sets**: list with effective version, pending approvals, last change.
- **Editor**: decision table as a grid with typed cells, validation messages per row, YAML view, version diff.
- **Simulation**: sample inputs, replay of history, change summary before publishing.
- **Approvals**: pending versions with diff and simulation report; approve or reject with comment. The requester's approve button is disabled and the API refuses it regardless.
- **Decisions**: searchable decision log; open a decision to see the highlighted matched row and the explanation.

## Process Engine screens (milestone 2)
Definitions and versions, instances with their current step, task queues per role, agent tasks with their inputs and outputs, retry and reassign, SLA breaches, history timeline.

## Embedding
```xml
<dependency><groupId>com.horus.bpm</groupId><artifactId>cockpit-spring-boot-starter</artifactId></dependency>
```
Serves the cockpit at `horus.bpm.cockpit.path` (default `/bpm/cockpit`) using the host's authentication. Theming through CSS variables; labels in English and Vietnamese, extensible.

## Access
Screens follow the permissions in [[Rule Engine Configuration]]. Viewing the decision log requires `rules:log:read`; masked inputs stay masked in the cockpit.
