# Feature status

Nothing is released yet. The Rule Engine, the Process Engine and the Cockpit are specified in this wiki and in the module specifications; no version is tagged.

The state of each feature, its milestone and its specification are kept in one place, the [feature map](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/docs/product/feature-map.md). This page does not copy it, so the two cannot disagree.

## Where to look

| You want | Read |
|---|---|
| Rule Engine features and their milestone | [Feature map: Rule Engine](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/docs/product/feature-map.md#rule-engine) |
| Process Engine features, including `agentTask` | [Feature map: Process Engine](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/docs/product/feature-map.md#process-engine) |
| Cockpit features | [Feature map: Cockpit](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/docs/product/feature-map.md#cockpit) |
| Cross-cutting features: tenancy, security, contracts | [Feature map: Cross-cutting](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/docs/product/feature-map.md#cross-cutting) |
| Features per milestone and what moves first if a milestone runs late | [Feature map: Summary](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/docs/product/feature-map.md#summary) |
| Milestone scope and dates | [[Roadmap]] |
| What each tagged version shipped | [[Release Notes]] |

## Milestones

| Milestone | Delivers | Target |
|---|---|---|
| M0 | Architecture and product decisions | 4 Dec 2026 |
| M1 | Rule Engine 0.1, Cockpit 0.1 | 15 Jan 2027 |
| M2 | Process Engine 0.1 with `agentTask`, Cockpit 0.2 | 26 Mar 2027 |
| M3 | Agent depth, compatibility, 1.0 of all modules | 21 May 2027 |

Dates are targets, not commitments. The [[Roadmap]] owns them.

## How to read a status in the feature map

| Status | Meaning |
|---|---|
| specified | At least one numbered specification statement covers the feature |
| spec gap | In scope for a milestone; the specification statement is not written yet |
| design preview | Described, but on no milestone before 1.0 |

Once a version is tagged, [[Release Notes]] state what shipped and this page links each released feature there.
