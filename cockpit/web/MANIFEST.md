# Cockpit change: display names and "not classified" note

Covers #438 (cockpit side), #415 and #442 (cockpit side). Built on the API of the stacked pull request.

## What changed

| Area | Change |
|---|---|
| Display names | One lookup of `GET /api/bpm/display-names` per screen, in the screen's language. A screen says which role, group and user ids it shows; the answer replaces them. |
| Where names show | Task queue (assignee, holder), task inspector, reassign dialog, case page (waiting for, taken by, history actors, cases list), approvals requester (`requestedByName`), version subtitles. |
| Fallback | No name, or a failed lookup: the id in sentence case, as before. Never an error on screen. |
| Hand-over refusal | `ASSIGNEE_NOT_ALLOWED` reads by its `reason`: already holds, worked on the case, system account, missing role, missing group. The missing role or group is named by its title. An unknown reason keeps the general sentence. |
| Not classified | `UNCLASSIFIED_INPUT` (rule sets) and `UNCLASSIFIED_FIELD` (processes) show as one neutral band, "Not classified", above a pending or draft version. It names fields only, has no value to show, is not an alert or a dialog, and is never part of what an approver accepts. |

## Not done

- Tenant display name in the switcher: the API gives none. `/me` has `tenant.current` and `tenant.allowed` as plain ids, and the display-names answer has no tenants. Needs an API addition first.
- `:validate` is not called by the cockpit today, so its warnings have no screen. The note reads the version view, which lists the same warnings.

## Styleguide self-check

- Colour: the note uses ground and structure tokens only (`--surface-2`, `--line`, `--ink`, `--muted`); amber stays for what needs attention. Saturated colour on these screens is unchanged.
- Words and icons: the note has the word "Not classified" and an icon. No spec id, code or raw JSON appears.
- Copy: one language at a time, from the catalogue; every subtitle and helper is 10 words or fewer ("Masking is not set. Approval is not blocked." is 8).
- Above the fold: the note is one band of two lines at 1440x900; the decision dock and its primary action stay visible.
- Vietnamese: strings fit without truncation at 1440x900 and 1440x1200.
- Accessibility: the note is a labelled group, not a live region; axe finds no serious violation in light and dark.

## Tests

- Vitest: display names in the queue and inspector, Vietnamese titles, failed lookup, each refusal reason, the note on rule sets and processes, the long list in Vietnamese.
- Playwright with axe: `e2e/display-names.spec.ts`, six states at 1440x900 and two at 1440x1200.
