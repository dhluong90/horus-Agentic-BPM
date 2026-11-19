# Process Engine

Code: `PRE` · Version: 0.1.0-SNAPSHOT · Status: pre-alpha

Embeddable workflow library: process definitions, human tasks, gateways, timers, maker-checker, history.

Documents: `docs/DESIGN.md`, `docs/SPEC.md`, `docs/TEST-PLAN.md`, `docs/adr/`. Public documentation is in the project wiki.

## Code layout and contracts

The process engine is split so that the BPMN model, the XML codecs and the behaviours of each element family can be built in parallel without editing a shared file.

```mermaid
flowchart LR
    classDef engine fill:#E0F2F1,stroke:#00796B,stroke-width:2px,color:#16231F
    classDef host fill:#E8F5E9,stroke:#2E7D32,color:#16231F
    classDef test fill:#ECEFF1,stroke:#546E7A,color:#16231F

    XML["process-engine-bpmn<br/>ElementCodec, BpmnCodecs, XmlHardening"]:::engine -->|1 reads into| MODEL["core: internal.model<br/>BPMN 2.0.2 semantic model"]:::engine
    MODEL -->|2 compiled by| KERNEL["core: internal.exec<br/>ExecutionKernel (no I/O)"]:::engine
    BEH["BehaviorModule per stream<br/>listed in BehaviorCatalog"]:::engine -->|3 registers behaviours| KERNEL
    KERNEL -->|4 Transition: state, effects, history| SHELL["persistence shell<br/>pre_* tables, transactions"]:::host
    SHELL -->|5 Command| KERNEL
    TEST["process-engine-test<br/>BpmnModelBuilder, ScenarioRunner"]:::test -.->|6 drives in memory| KERNEL
```

1. A BPMN 2.0.2 document is read through one `ElementCodec` per element family; `BpmnCodecs` is the fixed dispatch table and `XmlHardening` the only place that creates XML readers.
2. The model in `internal.model.bpmn` is the canonical model (ADR-0018): sealed roots `RootElement`, `FlowElement` (`FlowNode` with `Activity`, `Gateway` and `Event`), `EventDefinition`, `LoopCharacteristics`, `Artifact`, one record per element, each with typed `horus` extensions, opaque foreign fragments and other attributes.
3. Each element family's semantics is a `FlowNodeBehavior`, registered by a `BehaviorModule`. An element with no behaviour is refused as not executable at activation.
4. `ExecutionKernel.apply(state, command, clock)` returns the new state, the effects and the history. It performs no network, adapter or database call; every external action is an `Effect`.
5. The persistence shell applies the effects in the caller's transaction, except `InvokeService` in `ASYNC` mode, which runs outside any transaction (ADR-0008), and feeds results back as commands.
6. Behaviours and scenarios are tested in memory with `BpmnModelBuilder`, `RecordingExecutionContext`, `ScenarioRunner` and `TestClock`.

New code uses the package root `io.github.dhluong90.bpm.process` (ADR-0022).

## Walking skeleton

`process-engine-postgres` (the `pre_*` tables and `pre_schema_history`), `process-engine-rest` (`/api/bpm/processes/v1`) and `process-engine-spring-boot-starter` form the persistence shell around the kernel. The first executable subset is none start and end events, local `serviceTask`, `businessRuleTask` (through `RuleEngine.evaluate`), `userTask` and `exclusiveGateway`, read from BPMN 2.0 XML. The runnable sample is [`examples/rule-and-process`](../examples/rule-and-process/README.md).
