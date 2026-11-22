# Process Engine: import a BPMN file

This guide shows how to import a BPMN 2.0 file into the Process Engine, which elements 0.1.0 accepts, and how the engine refuses the rest. The Process Engine is a preview in 0.1.0 ([[Release Notes]]): outside the `dev` profile an imported process stays a draft and cannot start.

## Before you start

| You need | Check |
|---|---|
| The example host with both engines, running on the `dev` profile | Follow [Run locally](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/examples/rule-and-process/README.md#run-locally). The log shows `Process definition card-fee-review v1 ACTIVE for tenant bank-a` |
| A user with `processes:deploy` | On the example host, `lan` with the password in `HORUS_DEMO_PASSWORD` |
| A `.bpmn` file, UTF-8, at most 1 MB | `file my-process.bpmn` says `UTF-8` or `ASCII`; `wc -c < my-process.bpmn` prints at most `1048576` |

The commands below run from the repository root and use the demo password `demo-pass`.

## What happens to the file

![BPMN import: size, safety, subset and attribute checks, then a draft that becomes active only on dev](https://raw.githubusercontent.com/wiki/dhluong90/horus-Agentic-BPM/images/bpmn-import-outcomes.svg)

1. You post the file as XML.
2. A file over 1 MB is refused before it is read.
3. A `DOCTYPE`, an entity declaration, nesting deeper than 32 or more than 5,000 elements is refused, and nothing in the file is resolved.
4. Every element outside the subset is listed in one answer.
5. Each element must carry the `horus` attributes it needs.
6. A file identical to the latest version returns that version.
7. Otherwise the file becomes the next version, as a `DRAFT`.
8. On the `dev` profile with auto-approve on, the draft becomes `ACTIVE` at once. Anywhere else it stays a `DRAFT`.

## 1. Import the file

```bash
P=demo-pass
curl -s -u lan:$P -H 'Content-Type: application/xml' \
  --data-binary @examples/rule-and-process/src/main/resources/processes/card-fee-review.bpmn \
  localhost:8080/api/bpm/processes/v1/definitions
```

Expected answer, `201`:

```json
{"key":"card-fee-review","version":1,"status":"ACTIVE","definitionHash":"3fa54143...","sourceFormat":"BPMN_XML",...}
```

This file is the one the host imported at start-up, so the engine returns version 1 and creates no new version. A changed file becomes version 2.

| Field | Meaning |
|---|---|
| `key` | The `id` of the `process` element. It must be kebab-case, for example `card-fee-review` |
| `version` | The version number of this definition |
| `status` | `ACTIVE` on `dev` with `horus.bpm.processes.definitions.auto-approve: true`; `DRAFT` everywhere else |
| `sourceFormat` | `BPMN_XML`. The engine keeps the file as received and runs each version from it |

The body may also be sent as `text/xml` or `application/bpmn+xml`.

## 2. Write a file the engine accepts

The engine reads standard BPMN 2.0 XML. Engine settings are attributes and elements in the namespace `urn:horus-bpm:bpmn:extensions:v1`, with the prefix `horus`. Conditions are written in the expression language `urn:horus-bpm:expr:v1`. A BPMN modeller can open and save the file; add the `horus` attributes in its XML or properties view.

The root element must declare the namespaces, and the process must be executable:

```xml
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                  xmlns:horus="urn:horus-bpm:bpmn:extensions:v1"
                  id="Definitions_card-fee-review"
                  targetNamespace="urn:horus-bpm:examples"
                  expressionLanguage="urn:horus-bpm:expr:v1">
  <bpmn:process id="card-fee-review" name="Card application fee review" isExecutable="true">
```

### Supported in 0.1.0

| BPMN element | Required | Optional | Example |
|---|---|---|---|
| `startEvent` with no event definition | | `name` | `<bpmn:startEvent id="start"/>` |
| `endEvent` with no event definition | | `name` | `<bpmn:endEvent id="end-approved"/>` |
| `serviceTask` | `horus:handler`, the name of a `ServiceTaskHandler` bean in your host (a local step), or of a `ServiceAdapter` bean with `horus:mode="remote"` | `horus:mode` (`local`, the default, or `remote`), `horus:timeout` on a remote step only, `horus:resultVariable`, `horus:input` elements | `horus:handler="cardProfiles.load"` |
| `businessRuleTask` | `horus:ruleSet`, the name of a published rule set | `horus:resultVariable`, `horus:input` elements | `horus:ruleSet="card-fee"` |
| `userTask` | `horus:assignee` as `user:<id>`, `role:<role>` or `group:<group>`; `horus:resultVariable` | `horus:fourEyes="true"`: the starter and earlier completers may not complete it. `horus:sla`: working time until the task is due, an ISO 8601 duration such as `PT4H`, up to 366 days; `horus:calendar`: the tenant calendar it counts in (elapsed time without one; since 0.3.0). `horus:escalation` (needs `horus:sla`): up to three levels `target[|wait];...`, for example `role:branch-supervisor;role:regional-manager|PT4H`; `horus:onClaimed`: `release` (default) or `keep` (since 0.3.0) | `horus:assignee="role:CARD_OFFICER"` |
| `exclusiveGateway` | | `default`, the id of the flow taken when no condition is true | `default="fee-check__review"` |
| `sequenceFlow` | `sourceRef`, `targetRef` | `conditionExpression` in `urn:horus-bpm:expr:v1` | `pricing.fee == 0 VND` |

Inputs to a service or rule step are `horus:input` elements inside `extensionElements`:

```xml
<bpmn:businessRuleTask id="price-card" name="Price the card" horus:ruleSet="card-fee" horus:resultVariable="pricing">
  <bpmn:extensionElements>
    <horus:input name="tier" value="${instance.profile.tier}"/>
    <horus:input name="txCount" value="${instance.profile.txCount}"/>
  </bpmn:extensionElements>
</bpmn:businessRuleTask>
```

### Accepted and skipped

These parts of a file are accepted and never executed. They do not change the definition hash, and the stored file keeps them as received.

| Part | What the engine does |
|---|---|
| Diagram interchange (`bpmndi`) | Skipped. The BPMN export writes a new layout of its own |
| `documentation` | Skipped |
| Extensions of other vendors | Skipped. The BPMN export leaves them out |
| `textAnnotation` and its `text` | Kept as a note on the diagram. The BPMN export writes it back |
| `association` | Kept as the line from an element to a note. `associationDirection` is `None`, `One` or `Both`. The BPMN export writes it back |
| `group`, and the `category` with the `categoryValue` it refers to | Kept with the category label. The BPMN export writes the group and its category back |

BPMN modellers add notes, their lines and groups to a diagram, so a file saved from a modeller imports without removing them first. Each note, line and group needs an `id` that no other element in the file uses.

A gateway takes a flow only when its condition is true. A condition that is null or false is not taken; then the `default` flow is taken, and without a `default` the step fails. Money compares only within one currency. The full rules are on [[Rule Engine Expression Language|Rule-Engine-Expression-Language]].

A complete working file is [`card-fee-review.bpmn`](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/examples/rule-and-process/src/main/resources/processes/card-fee-review.bpmn).

## 3. Read a refusal

Every refusal is an RFC 9457 problem with a `code`. Nothing is stored.

### An element outside the subset

```bash
curl -s -u lan:$P -H 'Content-Type: application/xml' \
  --data-binary @process-engine/process-engine-bpmn/src/test/resources/bpmn/unsupported.bpmn \
  localhost:8080/api/bpm/processes/v1/definitions
```

Expected answer, `422`:

```json
{"status":422,"code":"BPMN_UNSUPPORTED_ELEMENT","unsupportedElements":[
  {"element":"boundaryEvent","elementId":"be-timeout","line":6,"stage":"S1","message":"..."},
  {"element":"inclusiveGateway","elementId":"ig-1","line":7,"stage":"S1","message":"..."},
  {"element":"endEvent with terminateEventDefinition","elementId":"end","line":8,"stage":"S1","message":"..."}],...}
```

The answer lists every unsupported element in the file, each with its id, its line and its stage. Remove or replace each one and import again. At most 100 entries are listed; `violationsTruncated: true` says there were more. Ids are printed without control characters and cut at 200 characters.

**Stages.** `stage` is one value and never names a sprint or a date. It comes from the coverage table in [standards compliance](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/docs/architecture/standards-compliance.md):

| `stage` | Meaning |
|---|---|
| `S1`, `S2`, `S3` | The stage after 1.0 that adds the element |
| `not planned` | A deviation the engine keeps by design, for example an XPath condition |
| `S0` | An element of the current stage that this release has not finished: `callActivity`, `transaction` and `intermediateThrowEvent` |

Examples: `boundaryEvent`, `inclusiveGateway`, `scriptTask`, `subProcess`, data objects, `terminate`, `error` and `escalation` events are `S1`; `sendTask`, `receiveTask`, `eventBasedGateway`, message, signal and conditional events are `S2`; `complexGateway` and `adHocSubProcess` are `S3`.

These are refused in 0.2.0:

| Kind | Elements |
|---|---|
| Gateways | `inclusiveGateway`, `eventBasedGateway`, `complexGateway` |
| Events | `boundaryEvent`, `intermediateThrowEvent`, a catch event that is not a timer, and any start or end event with an event definition (timer, message, signal, error, terminate) |
| Activities | `subProcess`, `transaction`, `callActivity`, `adHocSubProcess`, `task`, `scriptTask`, `manualTask`, `sendTask`, `receiveTask`, and a loop or multi-instance marker |
| Attributes that change how a step runs | `isForCompensation="true"`, `isInterrupting="false"`, `parallelMultiple="true"`, `startQuantity` or `completionQuantity` other than 1, `implementation` other than `##unspecified` |
| Service task variants | any `horus:type` other than `agentTask` (agent steps import since 0.2.0, see [[Guarded Agent Steps]]). A `horus:mode` other than `local` or `remote` is `MODE_INVALID` (below) |
| Structure | more than one `process`, a collaboration, lanes, data objects and data stores |
| Conditions | a `conditionExpression` in any language other than `urn:horus-bpm:expr:v1`, with the problem code `BPMN_EXPRESSION_LANGUAGE_UNSUPPORTED` (FEEL is `S1`; XPath and the others are `not planned`) |

An attribute or extension element of another vendor, such as `camunda:class`, is not refused. It is never read or run, and the stored source keeps it. When the file is refused for another reason, `warnings` lists it as `FOREIGN_EXTENSION_IGNORED` with its namespace and line.

### A CMMN file

The list is `unsupportedElements`, the same field and item shape the Rule Engine uses for a DMN file, so a client reads both engines with one parser. A successful import of a file with vendor attributes, such as `camunda:class`, answers `201` with a `warnings` list in the same item form (`FOREIGN_EXTENSION_IGNORED`, the attribute, the element id and the line): the stored source keeps them and the engine does not read them.

A file whose root element is in a CMMN namespace, such as `http://www.omg.org/spec/CMMN/20151109/MODEL`, is refused as `422 CMMN_UNSUPPORTED` with the detail "CMMN is not supported; model the case as a BPMN process", the `namespace` and the `line`. The reader stops at the root element, so no other part of the file is checked. A root in any other unknown namespace, such as XPDL or BPEL, is `422 VALIDATION_FAILED` with the problem code `NOT_BPMN`.

### A missing attribute or a broken document

`422 VALIDATION_FAILED` carries a `problems` list. Each problem names its code, element, id and line:

| Problem code | Fix |
|---|---|
| `KEY_INVALID` | Make the `process` id kebab-case, for example `card-fee-review` |
| `NOT_EXECUTABLE` | Set `isExecutable="true"` on the process |
| `HANDLER_MISSING` | Add `horus:handler` to the `serviceTask` |
| `MODE_INVALID` | Set `horus:mode` to `local` or `remote`, or leave it out for a local step |
| `TIMEOUT_INVALID` | Put `horus:timeout` only on a remote step, as a positive ISO-8601 duration such as `PT10S`; a local step runs in the caller's transaction and has no timeout of its own |
| `RULE_SET_MISSING` | Add `horus:ruleSet` to the `businessRuleTask` |
| `ASSIGNEE_INVALID` | Set `horus:assignee` to `user:<id>`, `role:<role>` or `group:<group>` |
| `SLA_INVALID` | Set `horus:sla` to a positive ISO 8601 duration up to 366 days, such as `PT4H` or `P2D`; `horus:calendar` to a calendar key such as `vn-banking-hours`; a calendar needs an SLA |
| `ESCALATION_INVALID` | Give `horus:escalation` at most three levels, each `user:<id>`, `role:<role>` or `group:<group>` with an optional `\|PT4H`-style wait (the first level may wait `PT0S`, later ones need a positive wait), never the assignment it replaces; the task needs `horus:sla`; `horus:onClaimed` is `release` or `keep` and needs an escalation |
| `RESULT_VARIABLE_REQUIRED` | Add `horus:resultVariable` to the `userTask`, so a completion never overwrites other variables |
| `INPUT_INVALID`, `INPUT_DUPLICATE` | Give each `horus:input` a unique `name` (letters, digits, `_`) and a `value`; `mask` is allowed on an agent step's input only, as `true` or `false` |
| `FLOW_REF_INVALID` | Give the `sequenceFlow` both `sourceRef` and `targetRef` |
| `ASSOCIATION_DIRECTION_INVALID` | Set `associationDirection` on the `association` to `None`, `One` or `Both`, or leave it out |
| `NOT_BPMN` | The root element must be `definitions` in `http://www.omg.org/spec/BPMN/20100524/MODEL` |
| `NO_PROCESS` | Add a `process` element |
| `XML_MALFORMED` | Fix the XML at the line given |
| `AGENT_MISSING` | Add `horus:agent` to the agent step (`horus:type="agentTask"`) |
| `AGENT_TASK_INVALID` | Remove `horus:handler` and `horus:mode` from the agent step; it names its adapter with `horus:agent` |
| `AGENT_NOT_REGISTERED` | Register an `AgentAdapter` bean under the name in `horus:agent` |
| `GUARD_SPEC_INVALID` | Fix the agent step's guard spec as the message says: one `<horus:outputSchema>` holding a JSON object in the supported subset, and one `<horus:guardrail>` with a kebab-case `decision`, `onViolation` `route-to-human` (with a `reviewer`) or `fail`, and no `fourEyes` other than `true` |
| `RETRY_INVALID` | Give the agent step at most one `<horus:retry>`, with `max` from 0 to 20 (the retries after the first attempt) and, optionally, `backoff` as a positive ISO-8601 duration such as `PT1S` |

### A handler or adapter the host does not register

The import does not check that the handler or adapter a service step names is registered, so a pipeline can import a draft before the host that runs it is deployed. The check runs when the version is about to become active: `:request-approval` and `:approve` answer `422 VALIDATION_FAILED` with one problem per step, and the version stays where it was. A dev auto-approved import checks at import.

| Problem code | Example message | Fix |
|---|---|---|
| `HANDLER_NOT_REGISTERED` | `reserve-limit -> limits.reserv: no ServiceTaskHandler of this name is registered in this host` | Register a `ServiceTaskHandler` bean under that name, or correct `horus:handler` in a new draft |
| `ADAPTER_NOT_REGISTERED` | `notify-partner -> partner-hub: no ServiceAdapter of this name is registered in this host` | Register a `ServiceAdapter` bean under that name |

Agent steps are different: an unregistered `horus:agent` is refused at import (`AGENT_NOT_REGISTERED`).

### An unsafe or oversized file

| Answer | Cause | Fix |
|---|---|---|
| `422 BPMN_UNSAFE_XML` | A `DOCTYPE` or entity declaration, nesting deeper than 32, or more than 5,000 elements | Export the file again without a document type declaration |
| `413 PAYLOAD_TOO_LARGE` | The body is over 1 MB (`horus.bpm.processes.definitions.max-bytes`) | Split the process or remove unused diagram data |

All codes are on [[Common Error Codes]].

## Outside the `dev` profile

The same import works, and the answer has `"status":"DRAFT"`. In 0.1.0 nothing can approve a process draft, so it cannot start. Use the preview on `dev` for evaluation. The approval flow for process definitions comes in a later release ([[Roadmap]]).
