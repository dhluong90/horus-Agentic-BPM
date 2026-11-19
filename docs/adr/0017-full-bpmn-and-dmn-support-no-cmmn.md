# ADR-0017: Full BPMN 2.0 and DMN support on the roadmap, no CMMN

- Status: accepted; decision 3 amended 2026-11-19 by PD-084 and PD-085
- Date: 2026-11-18
- Deciders: product owner (decision of 2026-11-18), maintainers
- Module: project
- Supersedes in part: [ADR-0003](0003-own-expression-language.md) (option 4 and the consequence on DMN import), [ADR-0013](0013-diagram-notation-layout-and-library.md) (decision 3 as the final scope of BPMN interchange)

## Context
ADR-0003 rejected full DMN FEEL and planned to map a FEEL subset onto our own expression language. ADR-0013 and PD-034 kept our JSON as the canonical process notation with a BPMN subset for import and export, and rejected everything else by name. Principle 9 and the vision ("never a full BPMN 2.0 implementation") followed from them.

On 2026-11-18 the product owner decided: CMMN is out of scope; BPMN 2.0 and DMN must be fully supported on the roadmap; full support is not required for the MVP (M1, M2); the architecture must be designed now so that the engines reach full support without a rewrite.

## Decision
1. **Target.** BPMN 2.0.2 Process Modeling Conformance (import, display and export of the complete process and collaboration element set) and Process Execution Conformance, and DMN 1.5 (the latest formal OMG version at the time of writing; later versions tracked) Conformance Level 3, each with the published deviations of [`standards-compliance.md`](../architecture/standards-compliance.md) section 12.
2. **No CMMN.** CMMN documents are refused with `422 CMMN_UNSUPPORTED`; no case-management concept enters the model.
3. **Staging.** M1 and M2 do not ship full support; M3 keeps the ADR-0013 subset and the DMN decision-table import. Full support arrives in stages S1 (1.1), S2 (1.2) and S3 (1.3), each additive for the Java API, REST and definition formats.
   **Amended 2026-11-19.** The first release, 0.1.0, carries a first subset early: BPMN 2.0 XML import of `startEvent`, `serviceTask`, `businessRuleTask`, `userTask`, `exclusiveGateway` and `endEvent`, and DMN 1.5 decision table import and export with a FEEL subset; everything else is refused by name ([PD-084](../product/decisions.md#pd-084-first-bpmn-and-dmn-subset-in-the-first-release)). Full support is a target for Sun 22 Nov 2026, measured by the DMN TCK, BPMN MIWG and executable-suite pass rates published daily, and not a claim ([PD-085](../product/decisions.md#pd-085-target-full-bpmn-20-and-dmn-by-sun-22-nov-2026)). Stages S1 to S3 remain the order of work and the content of each conformance step; a stage is announced only when its suites pass (`TR-STD-05`). Decisions 1, 2, 4, 5 and 6 are unchanged.
4. **Foundations now.** The M1 and M2 designs take the "do now" changes of `standards-compliance.md` section 9.1. The larger changes in section 9.2 are decisions for the product owner; on 2026-11-19 the product owner accepted ADR-0018 (canonical model), ADR-0019 (FEEL) and ADR-0020 (execution tree), and FEEL null semantics from M1 (PD-058).
5. **Principles.** The standards are implemented inside the principles: libraries in the host JVM, host-owned data, determinism, no code in definitions, Async Wait-State for every network call, maker-checker. Script tasks run only FEEL or our expression language; service tasks bind only to registered handlers; message correlation and signals are in-process, in-transaction APIs and the host owns every transport.
6. **Diagram library unchanged.** bpmn-js and diagram-js stay excluded, now for the licence reason only (`TR-LIC-01`).

Proposed wording for principle 9, for the documentation owner: "BPMN 2.0 and DMN are the models. Our JSON is their canonical serialisation and always has a diagram. What each release executes is exactly what the standards compliance page lists." Therefore: "Teams bring and take standard models; the engines stay deterministic and code-free; every deviation is published."

## Options considered
1. Full BPMN 2.0 and DMN, staged after 1.0, with foundations in M1 and M2 (chosen by the product owner). Standard models in and out, evaluations that require standards are winnable; about 60 engineer-weeks after 1.0 (assumption); a small M1 and M2 cost for the foundations.
2. Keep the subset permanently (ADR-0013, PD-034 as decided). Smallest engines and support surface; loses every evaluation that requires BPMN or DMN conformance and leaves the "lock-in" objection half answered.
3. Full support within M1 to M3. Not possible within the dates of PD-042; M2 and M3 are already at their capacity.
4. Include CMMN. Rejected by the product owner; little use in the target banks, and case management would add a second execution model.

## Consequences
- ADR-0003: option 4 and its consequence on DMN import are superseded; the hand-written parser, the closed set of operations and the explicit clock stay, and extend to FEEL (ADR-0019).
- ADR-0013: decision 3 is no longer the end state; the M3 subset, the StAX reader with its hardening, the layout storage and the diagram library decisions stay.
- Principle 9, the vision non-goal and the process, rule and cockpit PRD non-goals change wording (documentation owner and product manager); a decision log entry records this decision (product manager).
- New requirements `TR-STD-*` in [`requirements.md`](../product/requirements.md); [`overview.md`](../architecture/overview.md) non-goals change; module DESIGN and SPEC documents carry the applied do-now items, milestone-tagged.
- New error codes listed in `standards-compliance.md` section 13.4 for the error-code page when their stage is designed.

## History
- 2026-11-18: accepted (product owner decision of 2026-11-18).
- 2026-11-19: decision 3 amended by PD-084 (first subset in 0.1.0) and PD-085 (full support targeted for 22 Nov 2026, measured by pass rates). The DMN TCK test cases are allowed as build-time test data by PD-086 (`TR-LIC-01`).
