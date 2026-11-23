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

The engine reads standard BPMN 2.0 XML. Engine settings are attributes and elements in the namespace `urn:horus-bpm:bpmn:extensions:v1`, with the prefix `horus`. Conditions are written in the expression language `urn:horus-bpm:expr:v1` or in FEEL. A BPMN modeller can open and save the file; add the `horus` attributes in its XML or properties view.

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
| `task`, `manualTask` | | `name` | `<bpmn:manualTask id="post-card"/>`. Both pass the token on at once and the timeline records them as done by the engine; a manual task opens no work item |
| `scriptTask` | `horus:resultVariable`; a `<bpmn:script>` child with one expression in `urn:horus-bpm:expr:v1` | `scriptFormat` set to `urn:horus-bpm:expr:v1` | `<bpmn:script>amount * 2</bpmn:script>` |
| `callActivity` (a `callProcess` step, 0.3.0) | `calledElement`, the key of the definition to call, in your tenant | `horus:calledVersion` to pin a version, `horus:input` and `horus:output` elements, `horus:compensate`, `horus:onFailure="compensate"` | `calledElement="kyc-check"` |
| `subProcess` (embedded, since 0.3.0) | a none `startEvent`, steps and at least one `endEvent` inside it | `name`. It reads and writes the process variables like the rest of the process; no flow crosses its border | `<bpmn:subProcess id="hand-over">` |
| `boundaryEvent` (since 0.3.0) | `attachedToRef`, the id of a task, call activity or sub-process in the same scope; one `timerEventDefinition` (`timeDuration` or `timeDate`), `errorEventDefinition`, `escalationEventDefinition`, `messageEventDefinition`, `signalEventDefinition` or `conditionalEventDefinition`; at least one outgoing flow | `cancelActivity="false"` for a non-interrupting one (not for an error boundary); `errorRef`, `escalationRef`, `messageRef` or `signalRef` naming a root element; `horus:correlationKey` on a message boundary | `<bpmn:boundaryEvent id="overdue" attachedToRef="review" cancelActivity="false">` |
| `endEvent` with an `errorEventDefinition`, `escalationEventDefinition` or `terminateEventDefinition` (since 0.3.0) | for an error: `errorRef` to a root `error` with an `errorCode` | `escalationRef` on an escalation end | `<bpmn:endEvent id="refused"><bpmn:errorEventDefinition errorRef="Err_limit"/></bpmn:endEvent>` |
| `intermediateThrowEvent` with an `escalationEventDefinition`, or a `linkEventDefinition`; `intermediateCatchEvent` with a `linkEventDefinition` (since 0.3.0) | `name` on each link definition; a throw needs a catch of the same name in the same scope | `escalationRef` | `<bpmn:linkEventDefinition name="notify"/>` |
| `startEvent` with a `signalEventDefinition` (since 0.3.0) | `signalRef`, the id of a document-level `signal` element | `name` | `<bpmn:signalEventDefinition signalRef="Signal_rate"/>` |
| `startEvent` with a `conditionalEventDefinition` (since 0.3.0) | a `condition` in `urn:horus-bpm:expr:v1` | `name` | `<bpmn:condition>amount >= 1000000</bpmn:condition>` |
| `eventBasedGateway` (since 0.3.0) | two or more outgoing flows, each to a message, signal, timer or conditional `intermediateCatchEvent` or a `receiveTask` that no other flow enters | none; `instantiate="true"` and `eventGatewayType="Parallel"` are refused (`S3`) | `<bpmn:eventBasedGateway id="wait-payment"/>` |
| `intermediateCatchEvent` with a signal or conditional definition (since 0.3.0) | as above | `name` | `<bpmn:signalEventDefinition signalRef="Signal_rate"/>` |
| `intermediateThrowEvent` and `endEvent` with a signal definition (since 0.3.0) | `signalRef` | `name` | `<bpmn:endEvent id="end"><bpmn:signalEventDefinition signalRef="Signal_done"/></bpmn:endEvent>` |
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

### Script tasks

A script is one expression, not a program. It reads the process variables, has at most 1,000 characters and 500 evaluation steps, and its value is stored in `horus:resultVariable`. It cannot call host code, name a Java class, assign a variable or loop. A script that does not parse is refused at import with `SCRIPT_INVALID` and its line; a script over the step budget fails its step with `SCRIPT_ERROR`. The language is the one the gateway conditions use ([[Rule Engine Expression Language|Rule-Engine-Expression-Language]]); a script may also be FEEL (`scriptFormat` the FEEL URI). A worked file is [`card-welcome.bpmn`](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/examples/rule-and-process/src/main/resources/processes/card-welcome.bpmn).

### Sub-processes (0.3.0)

A `subProcess` groups steps that run from their own start event to their own end event. The process goes on after it only when the inner end is reached, and a parallel join inside it waits only for the branches of the same sub-process. It reads the process variables and writes them back, so a value set inside, such as `yearlyFee`, is there for the steps after it. Cancelling the instance cancels every step, task and call inside it. The cockpit draws it closed, with the count of its steps, or open in place, and the timeline nests its steps; it does not edit it. A worked file is [`card-delivery.bpmn`](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/examples/rule-and-process/src/main/resources/processes/card-delivery.bpmn).

```xml
<bpmn:subProcess id="hand-over" name="Hand the card over">
  <bpmn:startEvent id="hand-over-start"/>
  <bpmn:manualTask id="post-card" name="Post the card to the customer"/>
  <bpmn:endEvent id="hand-over-end"/>
  <bpmn:sequenceFlow id="h1" sourceRef="hand-over-start" targetRef="post-card"/>
  <bpmn:sequenceFlow id="h2" sourceRef="post-card" targetRef="hand-over-end"/>
</bpmn:subProcess>
```

A flow from inside to outside is refused at activation (`FLOW_CROSSES_SAGA`). An event sub-process is refused as `S1`.

### Boundary events, errors and escalations (0.3.0)

A boundary event sits on a task, a call activity or a sub-process and takes a path when something happens while that step runs. A timer uses the same timers and calendar rules as any timer. An interrupting boundary cancels the step (its work item, its pending call, a called child, or everything inside a sub-process) and takes its own flow; with `cancelActivity="false"` the step stays open and the flow runs once. The timer is cancelled when the step ends on its own.

An error boundary catches a business error before it becomes an incident: the code a handler answers with, or an error end event inside a sub-process. The code comes from a root `error`; a boundary without `errorRef` catches every code, and one with a code wins over it. A call activity whose child failed or was cancelled raises `CHILD_FAILED` or `CHILD_CANCELLED`. An error always interrupts. An error end event that nothing catches opens an incident on the end event, as an unhandled business error does.

An escalation goes up the same way. An escalation end or throw event that nothing catches is recorded in the timeline and the flow goes on.

```xml
<bpmn:error id="Err_limit" name="Refund limit exceeded" errorCode="REFUND_LIMIT_EXCEEDED"/>
<bpmn:process id="card-dispute" isExecutable="true">
  <bpmn:serviceTask id="post-refund" horus:handler="disputes.refund"/>
  <bpmn:boundaryEvent id="over-limit" attachedToRef="post-refund">
    <bpmn:errorEventDefinition errorRef="Err_limit"/>
  </bpmn:boundaryEvent>
  <bpmn:sequenceFlow id="f6" sourceRef="over-limit" targetRef="senior-approval"/>
  ...
```

The worked example `examples/rule-and-process/src/main/resources/processes/card-dispute.bpmn` has a non-interrupting timer boundary, an error boundary and a link pair. [`payment-review.bpmn`](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/examples/rule-and-process/src/main/resources/processes/payment-review.bpmn) has a message boundary, a signal boundary, a conditional boundary and an event-based gateway. An error cannot be thrown by an intermediate event (it is not BPMN); use an error end event. A boundary on a step with several boundaries is drawn side by side on its lower edge in the cockpit; the diagram and the timeline show them and never edit them.

### Terminate and link events (0.3.0)

A `terminateEventDefinition` on an end event cancels every other token of its scope, with their tasks, timers and called children: at the top level the instance completes, inside a sub-process only the sub-process ends and the parent goes on. A link throw jumps to the link catch of the same name in the same scope with no sequence flow, so several paths can end at one step. A throw with no catch of its name is refused at import as `LINK_CATCH_MISSING` with the element id; a name used by two catches in one scope is `LINK_CATCH_DUPLICATE`.

### Message, signal and conditional boundaries, and the event-based gateway (0.3.0)

A boundary event can wait for a message, a signal or a condition while its step runs, and interrupt it (the default) or run beside it (`cancelActivity="false"`).

- **Message.** The boundary needs `horus:correlationKey`, the variable whose value the message must carry, read when the step is entered; `horus:output` maps payload fields as it does on a catch event. `POST /messages:correlate` with the name and key delivers into the boundary like into any waiting step. A non-interrupting boundary stays armed and runs its flow once for each message until the step ends.
- **Signal.** One subscription row, woken by a broadcast through a job. A non-interrupting boundary stays armed for the next broadcast.
- **Condition.** The engine asks again whenever the instance's variables change, in that transaction under the instance lock, and the boundary fires once. A condition that is already true when the step is entered fires on the next change of a variable, not on arrival.
- **Cleanup.** When the step ends on its own, the subscriptions of its boundaries are deleted in the same transaction. When the step is cancelled, the same applies.
- **Event-based gateway.** The token waits on every catch event that follows: one subscription or timer for each, written in one transaction. The first event that arrives runs its branch and the others are cancelled in the same transaction under the instance lock, so when two events arrive together on two replicas exactly one branch runs and the other delivery is refused as stale (`409`, or `MESSAGE_NOT_CORRELATED` for a message). A condition that is true on arrival wins at once. An instance cancelled while it waits loses every subscription and timer. The instantiating form and the parallel type are `S3`.

The cockpit draws the symbols with their markers and the timeline names the message, signal or condition that fired and, at a gateway, the winner and the events it stopped; it never edits them.

### Signals and conditions (0.3.0)

A signal is declared once in the document, as `<bpmn:signal id="Signal_rate" name="rate-card-updated"/>`, and events refer to it by `signalRef`. The name is 1 to 100 letters, digits, `.`, `_`, `:` or `-`, starting with a letter or digit (`SIGNAL_NAME_INVALID`); a reference to no signal is `SIGNAL_REF_INVALID`. Signals are broadcast **within one tenant**: a throw or `POST /signals` reaches the waiters and the signal starts of that tenant, never another's.

- **Catch.** The token waits and costs one row. A broadcast does not run the waiters inline: it writes one wake-up job for each, and each waiter continues in its own transaction, once.
- **Throw and end.** The event broadcasts and the instance goes on at once. The timeline shows `SIGNAL_THROWN` with the number of waiters.
- **Start.** A broadcast starts one instance of the version in effect of each definition whose start event is a signal start for that name. A start request for such a definition starts it too.
- **Conditional catch.** A condition that is true on arrival passes at once. Otherwise the token waits and the engine asks again whenever the instance's variables change, in that transaction and for that instance only. A condition that cannot be evaluated yet is not true.
- **Conditional start.** The instance starts only when the condition is true for the start variables, otherwise the start is refused (`422 STEP_FAILED`, reason `START_CONDITION_FALSE`) and nothing is stored. A conditional start that waits for a change of variables belongs to an event sub-process, which comes with them.

Signals carry no data into an instance. A broadcast needs `processes:signal:broadcast` and an `Idempotency-Key`; see [[Process Engine API]]. A worked file is [`rate-card-watch.bpmn`](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/examples/rule-and-process/src/main/resources/processes/rate-card-watch.bpmn).

### Calling another process (0.3.0)

A `callActivity` starts a child instance of another definition of the same tenant and waits for it. The child gets only the values the step passes, and the step takes back only the outputs it names:

```xml
<bpmn:callActivity id="kyc" name="KYC check" calledElement="kyc-check" horus:calledVersion="6">
  <bpmn:extensionElements>
    <horus:input name="customerId" value="${instance.customerId}"/>
    <horus:output name="kycLevel" from="kycLevel"/>
  </bpmn:extensionElements>
</bpmn:callActivity>
```

`horus:input` names the child's variable and gives its value, as on any step. `horus:output` sets the parent's variable `name` from the child's variable `from` when the child completes. Without `horus:calledVersion` the child starts on the version effective when the step runs and stays on it. A definition may name a process that is not deployed yet; the step raises the incident `NO_EFFECTIVE_VERSION` if it still is not when it runs. A call inside a `saga` must declare its undo with `horus:compensate`, because a completed child is undone only by its call step's own handler. `horus:in="all"` is not supported.

### Accepted and skipped

These parts of a file are accepted and never executed. They do not change the definition hash, and the stored file keeps them as received.

| Part | What the engine does |
|---|---|
| Diagram interchange (`bpmndi`) | Never executed, and not part of the definition hash. Since 0.3.0 the import stores the shapes of the steps and the waypoints of the flows as layout revision 1 of the new version (see [[Process Engine API]], layout), so the cockpit opens it as drawn. A shape of something that is not a step, or a coordinate outside the layout schema, is dropped. The BPMN export writes a layout of its own |
| `documentation` | Skipped |
| Extensions of other vendors | Skipped. The BPMN export leaves them out |
| `textAnnotation` and its `text` | Kept as a note on the diagram. The BPMN export writes it back |
| `association` | Kept as the line from an element to a note. `associationDirection` is `None`, `One` or `Both`. The BPMN export writes it back |
| `group`, and the `category` with the `categoryValue` it refers to | Kept with the category label. The BPMN export writes the group and its category back |

BPMN modellers add notes, their lines and groups to a diagram, so a file saved from a modeller imports without removing them first. Each note, line and group needs an `id` that no other element in the file uses.

A gateway takes a flow only when its condition is true. A condition that is null or false is not taken; then the `default` flow is taken, and without a `default` the step fails. Money compares only within one currency. The full rules are on [[Rule Engine Expression Language|Rule-Engine-Expression-Language]].

**FEEL conditions.** Write `language` on the `conditionExpression` (or `expressionLanguage` on the definitions) as the FEEL URI of DMN 1.2, 1.3 or 1.5 (`https://www.omg.org/spec/DMN/20191111/FEEL/`), and the condition reads as FEEL: `amount > 1000 and tier = "gold"`. FEEL equality is `=` and strings use double quotes; `==`, single quotes and `is null` belong to `urn:horus-bpm:expr:v1` and are refused in FEEL at import with their position (`CONDITION_INVALID`). The parts of FEEL a condition or script needs are read: comparisons, `and`, `or`, `not(...)`, `in [...]`, `between`, `null`, arithmetic, `date("...")`, `date and time("...")`, `matches(x, "...")` and the HORUS extension `money(12.50, "USD")`. Null, number and money rules are the same as in the other language. A FEEL condition is hashed in the normal form of `urn:horus-bpm:expr:v1`, so the same meaning written in either language is one `definitionHash`, and the cockpit shows the normal form. A worked file is [`fee-routing-feel.bpmn`](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/examples/rule-and-process/src/main/resources/processes/fee-routing-feel.bpmn).

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
  {"element":"boundaryEvent","elementId":"be-timeout","line":6,"stage":"not planned","message":"..."},
  {"element":"inclusiveGateway","elementId":"ig-1","line":7,"stage":"S1","message":"..."},
  {"element":"endEvent with messageEventDefinition","elementId":"end","line":8,"stage":"S2","message":"..."}],...}
```

The answer lists every unsupported element in the file, each with its id, its line and its stage. Remove or replace each one and import again. At most 100 entries are listed; `violationsTruncated: true` says there were more. Ids are printed without control characters and cut at 200 characters.

**Stages.** `stage` is one value and never names a sprint or a date. It comes from the coverage table in [standards compliance](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/docs/architecture/standards-compliance.md):

| `stage` | Meaning |
|---|---|
| `S1`, `S2`, `S3` | The stage after 1.0 that adds the element |
| `not planned` | A deviation the engine keeps by design, for example an XPath condition |
| `S0` | An element of the current stage that this release has not finished: an `intermediateThrowEvent` with no event definition |
| `S0` | An element of the current stage that this release has not finished: `intermediateThrowEvent` other than a `throwCompensation` or a signal throw |

Examples: `inclusiveGateway`, an event sub-process, data objects and a timer cycle are `S1`; `sendTask`, `receiveTask`, message, signal and conditional events are `S2`; `complexGateway` and `adHocSubProcess` are `S3`.
Examples: `boundaryEvent`, `inclusiveGateway`, `subProcess`, data objects, `terminate`, `error` and `escalation` events are `S1`; `sendTask`, `receiveTask` and message events are `S2`; `complexGateway` and `adHocSubProcess` are `S3`.
Examples: `boundaryEvent`, `inclusiveGateway`, `subProcess`, data objects, `terminate`, `error` and `escalation` events are `S1`; signal and conditional events are `S2`; `complexGateway` and `adHocSubProcess` are `S3`.

These are refused in 0.2.0:

| Kind | Elements |
|---|---|
| Gateways | `inclusiveGateway`, `eventBasedGateway`, `complexGateway` |
| Events | a boundary event with no definition, an intermediate error or terminate throw, an intermediate catch event that is neither a timer nor a link, an intermediate throw event that is neither a compensation, an escalation nor a link, and any start event or an end event with a message or signal definition |
| Activities | an event sub-process (`subProcess` with `triggeredByEvent="true"`), `adHocSubProcess`, `sendTask`, `receiveTask`, and a loop or multi-instance marker |
| Events | `boundaryEvent`, an `intermediateThrowEvent` that is not a signal throw or a `throwCompensation`, a catch event that is not a timer, signal or conditional event, a start event with a definition other than signal or conditional, an end event with a definition other than signal (timer, message, error, terminate), a conditional end or throw event, and an event with several definitions |
| Activities | `subProcess`, `adHocSubProcess`, `sendTask`, `receiveTask`, and a loop or multi-instance marker |
| Events | `boundaryEvent`, an `intermediateThrowEvent` that is not a `throwCompensation` or a message throw, a catch event that is neither a timer nor a message, and any start or end event with an event definition other than message (timer, signal, error, terminate) |
| Activities | `subProcess`, `adHocSubProcess`, a `receiveTask` with `instantiate="true"` (start with a message start event instead), and a loop or multi-instance marker |
| Attributes that change how a step runs | `isForCompensation="true"`, `isInterrupting="false"`, `parallelMultiple="true"`, `startQuantity` or `completionQuantity` other than 1, `implementation` other than `##unspecified` |
| Service task variants | any `horus:type` other than `agentTask` (agent steps import since 0.2.0, see [[Guarded Agent Steps]]). A `horus:mode` other than `local` or `remote` is `MODE_INVALID` (below) |
| Structure | more than one `process`, a collaboration, lanes, data objects and data stores |
| Conditions and scripts | a `conditionExpression` or `scriptFormat` in a language other than `urn:horus-bpm:expr:v1` or FEEL, with the problem code `BPMN_EXPRESSION_LANGUAGE_UNSUPPORTED` or `BPMN_SCRIPT_FORMAT_UNSUPPORTED` naming the language (XPath, JavaScript and the others are `not planned`) |

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
| `SCRIPT_MISSING`, `SCRIPT_INVALID` | Give the `scriptTask` a `<bpmn:script>` with one valid expression of at most 1,000 characters; the message names the position |
| `RESULT_VARIABLE_INVALID` | Name the `scriptTask`'s `horus:resultVariable` with letters, digits and `_` |
| `MODE_INVALID` | Set `horus:mode` to `local` or `remote`, or leave it out for a local step |
| `TIMEOUT_INVALID` | Put `horus:timeout` only on a remote step, as a positive ISO-8601 duration such as `PT10S`; a local step runs in the caller's transaction and has no timeout of its own |
| `RULE_SET_MISSING` | Add `horus:ruleSet` to the `businessRuleTask` |
| `ASSIGNEE_INVALID` | Set `horus:assignee` to `user:<id>`, `role:<role>` or `group:<group>` |
| `SLA_INVALID` | Set `horus:sla` to a positive ISO 8601 duration up to 366 days, such as `PT4H` or `P2D`; `horus:calendar` to a calendar key such as `vn-banking-hours`; a calendar needs an SLA |
| `ESCALATION_INVALID` | Give `horus:escalation` at most three levels, each `user:<id>`, `role:<role>` or `group:<group>` with an optional `\|PT4H`-style wait (the first level may wait `PT0S`, later ones need a positive wait), never the assignment it replaces; the task needs `horus:sla`; `horus:onClaimed` is `release` or `keep` and needs an escalation |
| `RESULT_VARIABLE_REQUIRED` | Add `horus:resultVariable` to the `userTask` or `scriptTask`, so a completion never overwrites other variables |
| `INPUT_INVALID`, `INPUT_DUPLICATE` | Give each `horus:input` a unique `name` (letters, digits, `_`) and a `value`; `mask` is allowed on an agent step's input only, as `true` or `false` |
| `FLOW_REF_INVALID` | Give the `sequenceFlow` both `sourceRef` and `targetRef` |
| `BOUNDARY_ATTACHED_INVALID`, `BOUNDARY_HAS_INCOMING`, `BOUNDARY_ERROR_NOT_INTERRUPTING` | Attach the boundary event to a task, call activity or sub-process in its own scope with `attachedToRef`; no flow enters a boundary event; an error boundary has no `cancelActivity="false"` |
| `ERROR_CODE_REQUIRED`, `ERROR_REF_UNRESOLVED`, `ESCALATION_REF_UNRESOLVED` | Point an error end event's `errorRef` at a root `error` that has an `errorCode`; point `errorRef` and `escalationRef` at a root element of the file |
| `LINK_CATCH_MISSING`, `LINK_CATCH_DUPLICATE`, `LINK_NAME_REQUIRED`, `LINK_THROW_HAS_OUTGOING` | Name every link event, give each link throw one link catch of the same name in its scope, and draw no flow out of a throw |
| `ASSOCIATION_DIRECTION_INVALID` | Set `associationDirection` on the `association` to `None`, `One` or `Both`, or leave it out |
| `SIGNAL_REF_MISSING`, `SIGNAL_REF_INVALID`, `SIGNAL_NAME_INVALID` | Give a signal event a `signalRef` that is the id of a `signal` element of the document, with a name of 1 to 100 letters, digits, `.`, `_`, `:` or `-` |
| `CONDITION_MISSING`, `CONDITION_INVALID` | Give a conditional event a `condition` with an expression in `urn:horus-bpm:expr:v1` that parses |
| `NOT_BPMN` | The root element must be `definitions` in `http://www.omg.org/spec/BPMN/20100524/MODEL` |
| `NO_PROCESS` | Add a `process` element |
| `XML_MALFORMED` | Fix the XML at the line given |
| `AGENT_MISSING` | Add `horus:agent` to the agent step (`horus:type="agentTask"`) |
| `AGENT_TASK_INVALID` | Remove `horus:handler` and `horus:mode` from the agent step; it names its adapter with `horus:agent` |
| `AGENT_NOT_REGISTERED` | Register an `AgentAdapter` bean under the name in `horus:agent` |
| `GUARD_SPEC_INVALID` | Fix the agent step's guard spec as the message says: one `<horus:outputSchema>` holding a JSON object in the supported subset, and one `<horus:guardrail>` with a kebab-case `decision`, `onViolation` `route-to-human` (with a `reviewer`) or `fail`, and no `fourEyes` other than `true` |
| `CALLED_ELEMENT_INVALID` | Give the `callActivity` a `calledElement` that is a definition key (lower-case words joined by hyphens) |
| `CALLED_VERSION_INVALID` | Set `horus:calledVersion` to a version number, 1 or more |
| `CALL_INPUT_ALL_REFUSED` | Remove `horus:in`; a call passes only the values named by `horus:input` |
| `OUTPUT_INVALID`, `OUTPUT_DUPLICATE` | Give each `horus:output` of a call a unique `name` (the parent's variable) and a `from` (the child's variable) |
| `COMPENSATION_REQUIRED` | Add `horus:compensate` (a registered handler, or `manual`) to the `callActivity` inside the `saga` |
| `FIELD_TITLES_INVALID` | Give the agent step at most one `<horus:fieldTitles>`, a JSON object with `inputs` and `outputs`, each mapping a field name to a map of language tag to a title of 1 to 120 characters of plain text, at most 100 fields a side and 10 languages a field. The message names the rule and never a title |
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

## Messages

A process can send and wait for messages. Declare each message once at the top of the file, then name it from the step:

```xml
<bpmn:message id="m-paid" name="payment-received"/>
<bpmn:receiveTask id="wait-for-payment" messageRef="m-paid" horus:correlationKey="orderId">
  <bpmn:extensionElements><horus:output name="paymentRef" from="reference"/></bpmn:extensionElements>
</bpmn:receiveTask>
```

| Element | What it does |
|---|---|
| Message start event | A message of that name starts an instance; the message key becomes the business key and the payload the variables |
| Intermediate message catch event, receive task | The token waits until a message with the name and the key in `horus:correlationKey` arrives; `horus:output` copies named fields into variables |
| Intermediate message throw event, message end event, send task | Hand a message to the host's `MessageAdapter`; `horus:input` names the values it carries and `horus:correlationKey` the key |

`horus:correlationKey` names an instance variable. A catch event or receive task needs it (`CORRELATION_KEY_REQUIRED`); a message that the file does not declare is `MESSAGE_REF_UNRESOLVED`. The host's consumer delivers each message it receives with `POST /messages:correlate` (permission `processes:message:correlate`, `Idempotency-Key` required). A message with no waiting instance is refused with `422 MESSAGE_NOT_CORRELATED` and is not kept; two waiting instances with one key give `409 MESSAGE_CORRELATION_AMBIGUOUS` and nothing runs. A message boundary event is still refused. A worked file is [`order-payment.bpmn`](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/examples/rule-and-process/src/main/resources/processes/order-payment.bpmn).
