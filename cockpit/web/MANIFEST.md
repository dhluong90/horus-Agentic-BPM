# Spreadsheet round trip for rule tables (cockpit)

Issue #39. Contract: the Rule Engine OpenAPI (`format=csv` on export, `text/csv` with `base` on import); the engine owns the file format (PD-107, PD-142).

## What it adds

| Area | File |
|---|---|
| Export "As spreadsheet (CSV)" in the Export menu | `src/features/rules/RuleSetPage.tsx` |
| Confirm dialog, "Based on version N", then one `POST ...?base=N` | `src/features/rules/components/ImportSpreadsheetDialog.tsx` |
| `.csv` accepted by the Import picker, bytes kept as picked | `src/features/rules/components/ImportFileButton.tsx` |
| Hook with one Idempotency-Key per file and attempt | `src/features/rules/api/useRules.ts` |
| Client method and exact-bytes body | `src/api/rules/rulesClient.ts`, `src/api/hostCall.ts` |
| Engine `CSV_*` code to a plain reason in the user's language | `src/features/rules/model/csvRefusals.ts`, `src/features/rules/components/ProblemNotice.tsx` |
| Fake engine export and import for dev, Vitest and Playwright | `src/mocks/csvTable.ts`, `src/mocks/ruleEngine.ts` |
| Tests | `src/features/rules/Spreadsheet.test.tsx`, `e2e/spreadsheet.spec.ts` |
| Specification | CKP-038 to CKP-040 in `docs/SPEC.md` and `docs/TEST-PLAN.md` |

## Behaviour

- The import always creates the next draft. The engine has no CSV form of "replace this draft" and no non-storing CSV check, so the user confirms before anything is sent.
- A `422` stores nothing and lists one line per problem, by row and column. The reason comes from the engine's stable code, so a screen stays in one language, and no reason quotes a cell: a masked value never shows.
- A `409` or any failure that is not a refusal keeps the file and the dialog; "Try again" sends a new key. A repeated click sends one key.
- Import is offered with `rules:author` only; Export with `rules:read`. Nothing in the UI names a role.

## Styleguide self-check

- Focal point and one primary button per view: "Import as draft" in the confirm state; "Choose another file" after a refusal; "Try again" after a conflict. Cancel and Close are quiet.
- 60-30-10: neutral dialog on the scrim; saturated colour only on the primary button and the red alert edge. Every coloured state has a word and an icon.
- Copy: subtitles of at most ten words, one language per screen, no ids, no raw JSON; the error code and reference stay behind the existing Details disclosure.
- Amounts: the only amounts shown are examples of the file's own syntax, written without separators because the engine refuses separators in a file. This is the one place the "separators everywhere" rule is deliberately not applied.
- Checked at 1440x900 (one shot per state, rotating light and dark and English and Vietnamese) and at 1440x1200 (refusal, new draft). Primary action fully above the fold in every state; axe-core clean; keyboard path: Tab reaches the primary action, Escape cancels with nothing sent.
