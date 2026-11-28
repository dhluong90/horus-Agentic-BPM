# Guide: decisions from processes

A `businessRuleTask` evaluates a published rule set and puts the answer in a variable. Rule logic stays in the rule set, so a business user changes it with four-eyes approval and no deployment.

## Call a rule set

```xml
<bpmn:businessRuleTask id="check" name="Check eligibility" horus:ruleSet="loan-eligibility" horus:resultVariable="decision">
  <bpmn:extensionElements>
    <horus:input name="age" value="${instance.age}"/>
    <horus:input name="income" value="${instance.income}"/>
  </bpmn:extensionElements>
</bpmn:businessRuleTask>
```

Each `horus:input` is one rule input, named as in the rule set. After the step, `decision` holds the outputs plus a `decisionId`: `{"reason":"OK","eligible":true,"decisionId":"01M85..."}`. Use it in a flow condition (`decision.eligible == false`) or in a later step's input (`${instance.decision.reason}`). The decision is logged in the same transaction, so the `decisionId` in the instance and the row in the decision log always match ([[Rule Engine Decision Log and Audit]]).

| Point | Detail |
|---|---|
| Version | The version in force at the instant of the call. A scheduled version takes over by itself at its `effectiveFrom` |
| Permission | The step runs as the caller, so the person who starts the process or completes the task needs `rules:evaluate` (for example `ROLE_TELLER: [rules:evaluate]`). Without it the start fails |
| Missing rule set | `RULE_SET_NOT_FOUND`, step failed, nothing stored. Publish the rule set first |
| Wrong input type | `400 INPUT_INVALID`, for example money sent as a bare number. Send `{"currency":"VND","amount":9000000}` |
| Pinning | A step cannot pin a version unless the account holds `rules:pin`, which no role holds by default |

The step catalogue (`GET /step-catalogue`) lists each rule set with its inputs and outputs, so a modeller wires them correctly.

## The expression language and its limits

Conditions on flows and rule cells use one expression language. It follows FEEL where it overlaps, so a rule gives the same result as a FEEL implementation on the overlap, but it is a subset:

- Data only: no method calls, no reflection, no network or file access. An unknown function is refused at import.
- Money is a first-class type with a currency: `10000 VND`. Money of two currencies compared is an error, never `false`.
- Null does not hold: a comparison with a missing variable is not true, so the gateway moves to the next flow or the default.
- Division by zero fails the decision (strict mode). Rounding is half-even.
- Each evaluation has a step budget, so no expression can run away.

Operators, functions and errors are in [[Rule Engine: expression language|Rule-Engine-Expression-Language]]. Writing the table itself: [[Rule Engine: author a decision table|Rule-Engine-Authoring-Decision-Tables]]. Check a change before approval with [[simulation|Rule-Engine-Simulation-and-Testing]].

## From plain Java

Outside a process, call `RuleEngine.evaluate("loan-eligibility", DecisionInput.of(...))` or `POST /api/bpm/rules/v1/decisions/loan-eligibility:evaluate`. Fields and headers: [[Rule Engine Decision API]].
