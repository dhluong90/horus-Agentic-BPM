# Guide: modelling in BPMN

Draw the process in a standard BPMN 2.0 modeler, add a few `horus:` attributes, and import the file. The engine runs what the file says; there is no built-in modeller before 1.0.

## Draw and import

1. Draw the process in a standard BPMN 2.0 modeler (a plain BPMN diagram, not a vendor-specific flavour) and keep `isExecutable="true"` on the process.
2. Declare the namespace `xmlns:horus="urn:horus-bpm:bpmn:extensions:v1"` and set `expressionLanguage="urn:horus-bpm:expr:v1"` on `definitions`.
3. Import it: `POST /api/bpm/processes/v1/definitions` with `Content-Type: application/xml`, or list it in `definitions.locations` for start-up. File limits are 1 MB and 500 steps.

An import creates a `DRAFT` version. A second person approves it (`:request-approval`, then `:approve` with the `definitionHash` they reviewed). On the `dev` profile, `auto-approve` activates it at once. An identical file returns the existing version, so re-importing is safe. Full steps and every refusal are in [[Process Engine: import a BPMN file|Process-Engine-Importing-BPMN]].

## The `horus:` attributes

| Where | Attribute or element | Meaning |
|---|---|---|
| `serviceTask` | `horus:handler="bureau.score"` | Name of your `ServiceTaskHandler` |
| `businessRuleTask` | `horus:ruleSet="loan-eligibility"` | Rule set to evaluate |
| any task | `horus:resultVariable="decision"` | Variable that receives the result |
| any task | `<horus:input name="age" value="${instance.age}"/>` | One input, read from an instance variable or a literal |
| `userTask` | `horus:assignee="role:LOAN_OFFICER"` | Who may take the task: `role:NAME`, `group:NAME` or a user id |
| `userTask` | `horus:fourEyes="true"` | The starter and earlier completers cannot take it |
| `userTask` | `horus:form="credit-review"` | A declared form ([[Guide: task handlers and forms|Guide-Task-Handlers-and-Forms]]) |
| catch and send steps | `horus:correlationKey="orderId"` | Variable that holds the message key |
| compensation task | `isForCompensation="true"` with `horus:handler` | The handler that undoes an earlier step ([[Guide: errors and compensation|Guide-Errors-and-Compensation]]) |

Conditions on flows use `language="urn:horus-bpm:expr:v1"`, for example `decision.eligible == false` ([[Guide: decisions from processes|Guide-Decisions-from-Processes]]). Give a gateway a `default` flow with no condition.

## What is supported

The engine executes the elements listed in the [standards compliance table](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/docs/architecture/standards-compliance.md), and nothing else. An element outside it is refused at import with a named problem, never ignored. Pools, lanes and message flows are read and shown; messages move only through your code.

## Models to copy

More than twenty working models, each with a header comment, are in [`examples/rule-and-process/src/main/resources/processes`](https://github.com/dhluong90/horus-Agentic-BPM/tree/main/examples/rule-and-process/src/main/resources/processes). They open in a standard BPMN modeler with their diagram.

If the import refuses your file, read the `problems` list in the answer: each has a `code` and the `elementId` to fix ([[Troubleshooting]]).
