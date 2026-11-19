# ADR-0013: Diagram notation, layout storage and diagram library

- Status: accepted
- Date: 2026-11-18
- Deciders: product owner (PD-032, PD-034, PD-039, PD-041), maintainers
- Module: process-engine, cockpit
- Superseded in part by: [ADR-0017](0017-full-bpmn-and-dmn-support-no-cmmn.md) (decision 3 is the M3 scope, no longer the end state of BPMN interchange) and, since 2026-11-19, [ADR-0018](0018-canonical-model-bpmn-and-dmn-semantic-models.md) (decision 1, notation: the BPMN semantic model is canonical and our JSON is its serialisation). Layout storage, the hardened StAX reader and the diagram library stay.

## Context
The product owner put read-only diagrams in M2 and a modeller in M3 (PD-032) and kept our own JSON as the canonical notation with a BPMN subset for import and export (PD-034). Definitions carry no layout today. Three questions follow: what the source notation is, where layout lives so that moving a box does not change behaviour or need approval, and which diagram library passes the licence rule (`TR-LIC-01`: Apache 2.0, MIT and BSD only).

The licence finding: bpmn-js and diagram-js are published under the bpmn.io licence, which is MIT-like but requires the bpmn.io watermark to stay visible and unchanged in every rendered diagram. That is not one of the permitted licences, so the build would reject it. elkjs (EPL-2.0) and JointJS (MPL-2.0) fail for the same reason. React Flow (`@xyflow/react`, MIT), dagre (`@dagrejs/dagre`, MIT) and maxGraph (Apache 2.0) pass. Licences are checked again when each package is adopted.

## Decision
1. **Notation.** Our definition JSON (or YAML) stays canonical, owned by `process-definition.v1.schema.json`. It may carry an optional `diagram` part (node positions, edge waypoints, lanes) in the format of `definition-layout.v1.schema.json`.
2. **Layout storage.** On import the `diagram` part is split off into `pre_definition_layout`, keyed by tenant, definition id and revision, append-only with author and time. Layout is never part of `definition_hash`, never part of the graph diff, and needs `processes:author` but no approval. A definition with no layout is laid out automatically by the cockpit, so definitions kept in Git still show as diagrams.
3. **BPMN interchange.** A documented subset (start and end events, user, service and business-rule tasks, `agentTask` as a service task with `horus:type`, exclusive and parallel gateways, timer catch events, call activities, transaction subprocesses as `saga`, compensation throw events, sequence flows, BPMNDI shapes and edges) is converted in `process-engine-bpmn` with the JDK's StAX parser, with DTDs and external entities disabled and size, depth and element limits. Anything else is rejected with `422 BPMN_UNSUPPORTED_ELEMENT` naming the element, id and line. Export writes the same subset; export then import gives the same hash.
4. **Library.** The cockpit draws with React Flow and lays out with dagre; maxGraph is the fallback if React Flow proves too limited. bpmn-js, diagram-js, elkjs and JointJS are not used, and the licence gate test rejects bpmn-js by name.

## Options considered
Notation:
1. Own JSON canonical, BPMN subset for interchange (chosen). One notation to validate; our step types (`agentTask`, `decision`, `saga`, async wait-state) need no extension symbols; Camunda 7 and Flowable users can bring and take models.
2. BPMN 2.0 XML canonical with our step types as extension elements. Familiar files, but full-BPMN expectations, every unsupported element a support question, and principle 9 contradicted.
3. Own JSON only. Least work; no migration path from existing BPMN models.

Layout:
1. Separate append-only table outside the hash (chosen). Moving a box is not a behaviour change and needs no approval; layout history is auditable.
2. Layout inside the definition and the hash. One document, but every cosmetic change needs four-eyes approval and a new version, and Git and production hashes differ for cosmetic reasons.
3. Layout only in the browser. No storage; every operator sees a different picture and the modeller cannot save.

Library:
1. React Flow with dagre (chosen). Permissive licences, custom node types for our step types, viewport culling for 500-node definitions, a large user base.
2. bpmn-js. The most complete BPMN modeller, but fails `TR-LIC-01` and would offer symbols the engine cannot run.
3. maxGraph. Apache 2.0 and mature, but a lower-level API and more work for custom nodes; kept as the fallback.
4. elkjs for layout. Better layouts for complex graphs, but EPL-2.0.

BPMN parsing:
1. JDK StAX (chosen). No dependency, streaming, easy to harden.
2. A BPMN model library (for example the Camunda model API, Apache 2.0). Full model, but a large dependency for a subset, and its own interpretation of elements we reject.

## Consequences
- New table `pre_definition_layout`, schema `definition-layout.v1.schema.json`, endpoints `GET` and `PUT .../layout` and `.../layout/revisions` (M3), and `POST /definitions:import-bpmn` (M3) and `GET .../export?format=bpmn` (M3, cut candidate).
- New module `process-engine-bpmn`, depending only on core and the JDK.
- Cockpit diagram chunk of about 70 KB gzipped, loaded on demand; dependency justifications in the cockpit DESIGN section 2.
- The wiki principle 9 text (PD-039) and the vision non-goal (PD-041) match this decision.
- Requirements: `TR-DEF-01`, `TR-DEF-02`, `TR-DEF-05`, `TR-UI-07`. Verified by `PRE-024`, `PRE-026`, `PRE-033`, `PRE-034`, `PRE-035`, `CKP-016` and a licence gate test that fails on bpmn-js.
- Follow-up from PD-032: a two-day spike in M0 rendering `kyc-onboarding` with automatic layout and a token overlay.
