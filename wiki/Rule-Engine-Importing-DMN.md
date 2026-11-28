# How to import a DMN decision table

Import a decision table saved by a DMN modelling tool as a draft rule set. The draft follows the normal four-eyes path: nothing is published by an import.

## Before you start

- You hold `rules:author`. Publishing still needs a second person with `rules:approve` ([[Rule Engine: publish a version|Rule-Engine-Versioning-and-Publishing]]).
- Your file is DMN 1.2, 1.3, 1.4 or 1.5 with **one decision** that holds **one decision table**. The decision `id` becomes the rule set name. A kebab-case id such as `card-fee` is used as written; any other id is turned into one (see below).

## Import a file

```bash
curl -X POST http://localhost:8080/api/bpm/rules/v1/rule-sets \
  -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: dmn-card-fee-1" \
  -H "Content-Type: application/dmn+xml" --data-binary @card-fee.dmn
```

`application/xml` and `text/xml` work too. `201` returns `card-fee` version 1 with status `DRAFT` and `sourceFormat: DMN_XML`. Use `POST /rule-sets/card-fee/versions` for the next version, or `PUT /rule-sets/card-fee/versions/{v}` to replace your own draft. Then request publication and have a second person approve, as for a YAML draft.

What the import does:

- The file is kept byte for byte as the source document, diagram and vendor extensions included (`?include=source`).
- Rule ids and labels come from the file. A DMN `number` becomes `decimal`, `integer` stays `integer`. DMN has no money type, so a fee imported from DMN has no currency; write the output as `money` in a new draft when you need one.
- A decision id that is not kebab-case gives a rule set name made from it: `cardFee` becomes `card-fee`, `Decision_1` becomes `decision-1` and a generated id such as `_3b2953a3-745f` becomes `decision-3b2953a3-745f`. The decision name, then the names of the definitions, are used when the id has no letter or digit.
- An output column with no `name` in a table with one output takes the name of the decision, with `_` for spaces (`Approval Status` becomes `Approval_Status`).
- A rule id that does not start with a letter, such as `_5f3a1` written by some modellers, becomes `r_5f3a1`; the original is kept in the rule description as `(DMN id _5f3a1)` and export writes it back.
- Extension elements and attributes of other namespaces change neither the table nor its hash.
- Export with `?format=dmn` and import again gives the same canonical hash. The same behaviour written in YAML and in DMN has one hash.
- A table imported from DMN evaluates in `strict` error mode.

## What is supported

- Hit policies `FIRST`, `UNIQUE` and `COLLECT`. `COLLECT` gives a list, or one value with the aggregation `SUM`, `COUNT`, `MIN` or `MAX` on a table with one output. Export writes the aggregation back.
- Input entries in FEEL unary tests: literals (`"GOLD"`, `42`, `true`, `null`, `date("2026-01-01")`), comparisons (`>= 3`, `< 18`), ranges (`[300..600)`), lists (`"ATM", "POS"`), `not(...)` and `-` or an empty cell for any value. Output entries are literals.
- Input expressions that are a plain input name; types `string`, `boolean`, `number`, `integer`, `date`, `date and time`.
- Item definitions as types: a name for a built-in type, with or without allowed values (`"SALARIED", "SELF_EMPLOYED"`), a collection of a built-in type (a `list<...>` input), and a structure of components that types a decision with several outputs. Allowed values of an input become its closed set. `outputValues` are checked against every rule's output when you import, so a rule that answers outside them is refused.

See [[Rule Engine: expression language|Rule-Engine-Expression-Language]] for the exact subset.

## What is refused

Everything outside the list above is refused with `422` and a message that names it, its line and, where there is one, the stage that adds it. Nothing is stored and nothing is dropped silently.

| You wrote | Answer |
|---|---|
| Hit policy `PRIORITY`, `ANY`, `RULE ORDER`, `OUTPUT ORDER` | Refused, arrives in stage S1 |
| `defaultOutputEntry` (the value when no rule matches) | `DMN_UNSUPPORTED_ELEMENT`, stage S1 |
| `COLLECT` with an aggregation on a table with several outputs, an aggregation on another hit policy, or one other than `SUM`, `COUNT`, `MIN`, `MAX` | `DMN_AGGREGATION_UNSUPPORTED` |
| A second decision, or a wire to another decision (`requiredDecision`) | `DMN_UNSUPPORTED_ELEMENT` or "More than one decision" (S1) |
| `literalExpression`, `knowledgeSource` | Refused, stage S1 |
| A type with no v1 form: `time`, `Any`, durations, a `typeConstraint`, a function item, a collection of structures, a structure in a structure, a type that is not defined or refers to itself | `DMN_TYPE_UNSUPPORTED`, naming the type and its line |
| A column typed by a structure (v1 columns are scalars and lists), or outputs that do not match the components of the decision's structure | `DMN_TYPE_UNSUPPORTED` |
| Allowed values or `outputValues` that are a range or a comparison, such as `[0..100]` | `DMN_ALLOWED_VALUES_UNSUPPORTED` |
| A rule that answers a value outside the table's `outputValues` | `DMN_OUTPUT_NOT_ALLOWED`, naming the rule and output |
| `businessKnowledgeModel`, `decisionService`, `context`, `invocation`, `relation`, functions | Refused, stage S2 |
| An output entry that is a calculation, such as `age * 2` | Refused naming the rule and output |
| An input expression such as `applicant.age` | Refused: it must be a plain input name |
| An input entry such as `? > 18`, `abs(-20)`, `!= 18`, `> 18 and < 65`, or a date that does not exist | Refused naming the rule and input |
| `expressionLanguage` other than FEEL | `EXPRESSION_LANGUAGE_UNSUPPORTED` |
| A CMMN file | `CMMN_UNSUPPORTED` |
| A decision id and name with no letter or digit, such as `___` | `DMN_RULE_SET_NAME` |
| Several output columns without a `name` | Refused: every output needs a name |
| Two rule ids that map to the same id, such as `_a1` and `r_a1` | `DMN_RULE_ID_COLLISION`, naming both ids |
| A rule id with a space or a non-ASCII letter | Refused naming the rule |
| `horus:errorMode="spec"` | Refused, stage S1 |
| A DTD or external entity, nesting deeper than 32, text over 65,536 characters | Refused naming the limit |
| A file over 1,048,576 bytes | `413 PAYLOAD_TOO_LARGE` |

The refusal codes are in [[Common Error Codes|Common-Error-Codes]]. Fix the file in your tool and import again.
