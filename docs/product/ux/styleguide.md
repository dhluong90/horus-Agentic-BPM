# Cockpit styleguide

| Field | Value |
|---|---|
| Type | Design specification |
| Owner area | Product |
| Last reviewed | 2026-11-28 |
| Status | Light Green default theme added; otherwise proposed; menu visibility, the page ground and card titles decided in PD-093; no chips ruled by the product owner on 28 Nov 2026 |

This is the build-ready styleguide for the cockpit and for the sign-in page of the example host. It turns the approved hybrid direction ([UX design](../ux-design.md), [PD-056](../decisions.md#pd-056-rule-management-ux-direction)) into CSS variables, a type scale, spacing, radius, elevation, the shell layout and the state of every core component.

It extends the token table in [UX design section 6](../ux-design.md#6-design-tokens); it does not replace it. Every existing token keeps its name and value, so `cockpit/web/src/components/tokens.css` changes by addition only. Where this page and section 6 disagree on a value, section 6 wins and this page is corrected.

## Contents

1. [Principles](#1-principles)
   - [No chips](#no-chips)
2. [Colour roles and the 60-30-10 balance](#2-colour-roles-and-the-60-30-10-balance)
3. [New colour tokens](#3-new-colour-tokens)
4. [Type scale](#4-type-scale)
5. [Spacing and layout](#5-spacing-and-layout)
6. [Radius](#6-radius)
7. [Elevation](#7-elevation)
8. [Shell: top bar and menu](#8-shell-top-bar-and-menu)
9. [Component states](#9-component-states)
10. [Sign-in page of the example host](#10-sign-in-page-of-the-example-host)
11. [Copy rules](#11-copy-rules)
12. [CSS to add today](#12-css-to-add-today)
13. [Review checklist](#13-review-checklist)

## Default theme: Green

The cockpit's default look is a light Green theme, chosen by the product owner. The light values in this guide are the Green values; the dark theme is unchanged. Blue and Orange are designed alternates (same roles, other hues); a theme switch comes after 1.0. The light theme has no dark surface: the contrast card is a pale sage tint with dark text.

| Role | Token | Value | Use |
|---|---|---|---|
| Page and cards | `--bg`, `--surface` | `#F7F7F7`, `#FFFFFF` | Flat grey page, white cards and menu. Borders `--line` `#ECEEED`. |
| Primary | `--primary-fill`, `--on-primary` | `#158370`, `#FFFFFF` | Primary button, 4.7:1 with white. Hover `#0E6B5C`, pressed `#0E4D41`. |
| Accent text | `--accent` | `#0F7362` | Links and selected text, at least 4.5:1 on white and on `--accent-soft` `#DDF3EC`. |
| Soft surfaces | `--surface-contrast`, `--accent-soft`, `--select`, `--teal-soft` | `#E1EFD4`, `#DDF3EC`, `#EAF6F2`, `#D6F0E8` | Sage focal card, mint tint, selected row. Text on them is `--ink` `#0E2F29`. |
| Strong marks | `--spark-max`, `--rel-1`, `--teal` | `#0E4D41` | Selected chart bar, icon discs, headings on a tint. Light bars use `#3B9C88` (3.3:1 on white); a mark lighter than 3:1 carries a direct value label. |
| Text | `--ink`, `--muted` | `#0E2F29`, `#5B6B66` | Body and secondary text, 5.6:1 on white. |
| Done | `--ok`, `--ok-soft` | `#2A7236`, `#E1EFD4` | Published, approved. Always with an icon and a label. |
| Warning | `--warn`, `--warn-soft` | `#8A5A00`, `#FBEFCF` | Needs attention soon. |
| Error | `--bad`, `--bad-soft` | `#B53A36`, `#FBE4E1` | Failed, rejected. Done, warning and error never share a hue with the accent. |

Fonts stay system fonts only. Later sections give the same tokens in full; their light column matches this table.

## 1. Principles

- **Calm authority.** Bank staff use the cockpit all day beside spreadsheets and core banking screens. Light is the default; dark is a stepped obsidian, never pure black.
- **One focal point per screen.** Each screen has one thing the eye lands on first and one primary action, visible at 1440x900 without scrolling.
- **One hue, one meaning.** Colour marks state, never decoration. Every coloured state also has a word and an icon.
- **The engine decides, the cockpit shows.** The page never computes a rule, a money amount or a status; it shows what the API returns.
- **No chips.** A rounded box that holds a border, a tinted fill and text is not allowed for status, counts, tags, metrics or legends. See [No chips](#no-chips).
- **System grammar is locked.** The shell, type, spacing, radius, buttons, tables and status marks are the same on every screen. Only each screen's centre piece (the rule table, the diff, the try-it result) gets its own treatment.

### No chips

The product owner ruled on 28 Nov 2026: no screen, diagram, dialog or docs page shows a chip. A chip is any small rounded box that combines a border or a tinted fill with text, used for a status, a count, a tag, a metric or a legend entry. It looks like decoration, hides the number or word it carries, and turns a calm page into a field of small boxes. There is no exception, and no variant (outlined, soft, filled, square-cornered) is allowed.

Colour marks meaning on the **text, a dot or an icon**, never on a box. The page ground, the card and the row stay the only surfaces.

| Use | Replace with | Before | After |
|---|---|---|---|
| Status | Plain `--type-caption` text at 600, optionally after a 6 px dot or a 14 px icon. Colour only on the dot or the text. Word and dot or icon always appear together | A green box, `Published` | ● Published, the dot `--ok`, the word `--ink` |
| Count or metric | The number in tabular figures at `--type-label` 600 `--ink`, then a `--muted` label. Several metrics sit on one line, separated by 24 px of space | A mint box, `47 escalated` | **47** escalated |
| Alert or warning | One quiet accent colour in the text plus a 14 px icon. No fill, no border, no left bar. The sentence is plain; the code stays behind `Details` | A coral box with a left edge, `Step timed out` | ⚠ Step timed out, icon and sentence in `--bad` |
| Tag or environment | Plain `--muted` text, several separated by a middot with a space on each side | Boxes `Demo`, `Preview`, `Platinum` | Demo · Retail banking, or Gold · Platinum |
| Legend | A 8 px dot or a 16 px line swatch, then the label in `--muted`. Never a box that holds the label | Coloured boxes `n`, `p95` | ● Cases ┄ Median time, as a dot or line, then the word |
| Diagram marker | The number or word beside the step, as text at 12 px with a dot or icon; a token count is a plain numeral. The 14 px completed-step check may stay as an icon with no ring | A rounded box over the step, `1 incident` | ⚠ 1 incident, text only, in `--bad` |
| Count on the menu | The number in `--muted`, right aligned, in tabular figures. When the menu is collapsed, a 6 px dot | A filled box with `4` | `4` |
| Diff mark | A leading symbol and a word in `--ok`, `--warn` or `--bad`, over the existing row tint | A box, `Added` | + Added |

What stays: buttons, inputs, selects, the segmented control, tabs, the selected menu item and avatars. They are controls or identity, and a chip is a label. A button is never styled as a tag, and a tag is never made clickable; if the user can act on it, it is a button or a link with a verb.

Tests: a screen fails review if any element has a `border-radius` of 4 px or more together with a background colour or a border and holds only a word or number that is not a control. The checklist in section 13 includes it. The legacy `.badge`, `.pill`, `.tag`, `.diff-tag`, `.value-pill`, `.key-chip` and `.dg-badge` classes are removed, not restyled.

## 2. Colour roles and the 60-30-10 balance

Every screen balances three weights. Check it on the 1440x900 screenshot: if saturated colour covers more than about 15 % of the viewport, remove colour until only the focal point and the primary action carry it.

| Weight | Share | Tokens | What it covers |
|---|---|---|---|
| Neutral ground | about 60 % | `--bg`, `--surface`, `--surface-2` | Page, cards, menu, table headers |
| Structure | about 30 % | `--ink`, `--ink-2`, `--muted`, `--line`, `--surface-3`, `--surface-contrast` | Text, dividers, at most one contrast card per screen |
| Accent and state | about 10 % | `--accent*`, `--primary-fill`, `--ok*`, `--warn*`, `--bad*`, `--indigo*`, `--violet*` | Primary action, selected menu item, links, status dots and text, diff marks |

Meaning of each hue (from UX design section 6.1, repeated here because the build depends on it):

| Hue | Tokens | Means only | Never |
|---|---|---|---|
| Green accent | `--accent*`, `--primary-fill`, `--select` | Primary action, selected, link, focus, waiting for approval, matched row in Try it | A second accent hue for selection |
| Done green | `--ok*` | Published, approved, check passed, added row | Pending or review |
| Amber | `--warn*` | Needs attention soon; the Demo tag | Plain information, a page frame, any environment tag other than Demo |
| Red | `--bad*` | Error, failed validation, reject, destructive action | A planned fee cut, an unmatched row |
| Indigo | `--indigo*`, `--diff-mod` | A changed value in a diff | Selection |
| Violet | `--violet*` | Agent steps and route-to-human only | Anything in the rule screens |
| Contrast sage | `--surface-contrast` | The single focal card of a screen (Home's next step; the sign-in brand panel) | More than one card per screen |

## 3. Colour tokens for the shell

The shell's colour tokens in one table. `--primary-fill` and `--on-primary` already exist in `tokens.css`; the others are added. Ratios are WCAG 2.x, measured by script on the real surface.

| Token | Role | Light | Dark | Ratio, light | Ratio, dark | Measured against |
|---|---|---|---|---|---|---|
| `--primary-fill` | Primary button fill | `#158370` | `#0C9AD9` | 4.65 with `--on-primary`; 4.65 against `--surface` | 5.95 with `--on-primary`; 5.51 against `--surface` | `--on-primary`, `--surface` |
| `--on-primary` | Text and icon on the primary fill | `#FFFFFF` | `#04131D` | 4.65 | 5.95 | `--primary-fill` |
| `--surface-contrast` | The one focal card, sign-in brand panel | `#E1EFD4` | `#1B2840` | 1.12, separated by `--contrast-line` | 1.30, separated by `--contrast-line` | `--bg` |
| `--contrast-line` | Hairline of the contrast card | `#CFE3BE` | `#3B5A86` | decorative | 2.73, decorative | `--bg` |
| `--on-contrast` | Text on the contrast card | `#0E2F29` | `#F1F5F9` | 12.01 | 13.46 | `--surface-contrast` |
| `--on-contrast-muted` | Secondary text on it | `#3F5650` | `#A9B4C6` | 6.58 | 7.04 | `--surface-contrast` |
| `--on-contrast-accent` | Link or count on it | `#0E4D41` | `#7DD3FC` | 8.11 | 8.84 | `--surface-contrast` |
| `--contrast-action` | Button fill on the contrast card | `#158370` | `#F1F5F9` | 4.65 with white text | 13.46 | text `#FFFFFF` / `#0B0F17` |
| `--primary-hover` | Primary button, hover | `#0E6B5C` | `#38BDF8` | 6.41 with `--on-primary` | 8.78 with `--on-primary` | |
| `--primary-active` | Primary button, pressed | `#0E4D41` | `#0284C7` | 9.74 with `--on-primary` | 4.59 | |
| `--rail` | Menu background | `#FFFFFF` | `#0E141F` | | | |
| `--rail-line` | Menu edge | `var(--line)` | `var(--line)` | decorative | decorative | |
| `--env-tag` | Environment tag other than Demo (Production, UAT), fill | `#F1F4F3` | `rgba(148,163,184,.12)` | | | |
| `--env-tag-ink` | Its text | `#2C4640` | `#C5CCD9` | 9.22 | 8.94 on `--surface` | `--env-tag` |
| `--env-tag-line` | Its 1 px inner edge | `#B5C2BE` | `rgba(148,163,184,.34)` | decorative | decorative | |

Two changes to how existing tokens are used:

- **No grid on the page.** `--grid` stays defined but is drawn only on the diagram canvas (`--canvas`). The page ground is flat `--bg`. The grid paper behind cards and the sign-in form is the main reason the screens read as a developer tool.
- **Disabled is not a colour.** A disabled control uses `--surface-3` fill, `--muted` text (5.64:1) and no border; the reason it is disabled is written beside it in words.

## 4. Type scale

System fonts only (`--font-ui`); no font files ship. `--font-data` (monospace) is for machine artefacts only: content hashes, references, keyboard keys. Rule set keys, column names and values are text, not code, and use `--font-ui`.

Six stops. Line height is at least 1.4 on every stop that can hold Vietnamese words, so stacked diacritics (`Phê duyệt`, `Quy tắc`) never clip.

| Token | Size / line height / weight / tracking | Use |
|---|---|---|
| `--type-display` | 32 / 1.25 / 700 / -0.02em, `tabular-nums` | Numerals only: the count on the contrast card |
| `--type-h1` | 24 / 1.4 / 650 / -0.015em | Page title, one per screen |
| `--type-h2` | 16 / 1.4 / 600 / 0 | Card titles, dialog titles, rule set name in a list row |
| `--type-body` | 14 / 1.5 / 400 / 0 | Body, table cells, inputs, buttons (buttons at 600) |
| `--type-label` | 13 / 1.45 / 500 / 0 | Form labels, table headers, menu items, meta values |
| `--type-caption` | 12 / 1.4 / 500 / 0.01em | Status text, timestamps, helper text, counts |

Rules:

- Card titles are sentence case at `--type-h2`. No upper-case letter-spaced eyebrows: in Vietnamese they become hard to read (`CHỜ PHÊ DUYỆT`).
- Every number in a column, every amount and every date uses `font-variant-numeric: tabular-nums`.
- A page title has at most one subtitle line at `--type-body` in `--muted`, ten words or fewer.

## 5. Spacing and layout

A 4 px base. Use only these steps.

| Token | px | Typical use |
|---|---|---|
| `--space-1` | 4 | Dot or icon to its text in a status |
| `--space-2` | 8 | Between a label and its field; between buttons |
| `--space-3` | 12 | Table cell vertical padding; menu item padding |
| `--space-4` | 16 | Card gap; table cell horizontal padding |
| `--space-5` | 20 | Card padding |
| `--space-6` | 24 | Between page header and content |
| `--space-8` | 32 | Page side padding; between sections |
| `--space-10` | 40 | Sign-in card padding |
| `--space-12` | 48 | Empty state padding |

Layout tokens:

| Token | Value | Rule |
|---|---|---|
| `--topbar-h` | 64 px | Full width, sticky, `--surface` with a bottom `--line` |
| `--rail-w` | 248 px | Expanded menu |
| `--rail-w-collapsed` | 72 px | Icons only, label in a tooltip |
| `--inspector-w` | 360 px | Right panel of the rule set screen and the review screen |
| `--page-pad` | 32 px sides, 28 px top | Content area |
| `--card-pad` | 20 px | Every card |
| `--gap` | 16 px | Between cards |

Grid: the content area is a 12-column grid with a 16 px gap. At 1440 px with the menu expanded the content is 1128 px wide. A workbench and its inspector stay side by side down to 1180 px and stack below that.

Whitespace: 35 to 45 % of each screen is open ground. A card that holds one short list is sized to its content; it does not stretch to the bottom of the page.

## 6. Radius

Nested corners are concentric: outer radius = inner radius + the padding between them.

| Token | px | Use |
|---|---|---|
| `--radius-xs` | 4 | Keyboard keys |
| `--radius-s` | 6 | Cells in the table editor |
| `--radius-m` | 8 | Buttons, inputs, selects, segmented items, menu items |
| `--radius-seg` | 10 | Segmented control container (8 + 2 px padding) |
| `--radius-l` | 12 | Cards, popovers, the table frame |
| `--radius-xl` | 16 | Dialogs, the contrast card, the sign-in panel |
| `--radius-pill` | 999 | Avatars only. Never a box that holds a status, count, tag or legend |

Never put a square input in a rounded card, or a 12 px card inside an 8 px frame.

## 7. Elevation

Depth comes from a hairline plus a soft shadow. In dark mode shadows are nearly invisible, so each level adds a one-pixel inner highlight instead.

| Level | Token | Light | Dark | Use |
|---|---|---|---|---|
| 0 | none | flat `--bg` | flat `--bg` | Page ground |
| 1 | `--sh-1` | 1 px `--line` + `0 1px 2px rgba(15,23,42,.06)` | 1 px `--line` + inner highlight `rgba(255,255,255,.05)` | Cards, the table frame, the menu |
| 2 | `--sh-2` | `0 6px 18px -8px rgba(15,23,42,.16), 0 1px 3px rgba(15,23,42,.06)` | inner highlight `.06` + `0 12px 28px -12px rgba(0,0,0,.75)` | Hovered list card, selected queue item |
| 3 | `--sh-dock` | `0 -8px 24px -12px rgba(14,47,41,.18)` + top `--line` | inner highlight `.07` + top `--line-strong` | Sticky action dock at the bottom of the editor and the review |
| 4 | `--sh-pop` | `0 16px 40px -8px rgba(15,23,42,.18), 0 2px 6px rgba(15,23,42,.06)` | 1 px `--line-strong` + `0 16px 40px -8px rgba(0,0,0,.8)` | Menus, selects, tooltips, toasts |
| 5 | `--sh-3` + `--scrim` | `--sh-3` over `--scrim` | `--sh-3` over `--scrim` | Dialogs: request publish, approve, reject |

## 8. Shell: top bar and menu

The shell is the same on every screen after sign-in.

**Top bar** (`--topbar-h` 64 px, full width):

| Zone | Content |
|---|---|
| Left, over the menu column | Product mark and `HORUS Cockpit` |
| Left, over the content | Breadcrumb: `Rules / card-fee / Version 2`. On Home, nothing |
| Right | Service line, plain text: green dot, `Loan origination`, tenant `bank-a`, and `· Demo` in `--warn` text when the host is a demo, with no box around any of it; EN / VI segmented control; theme button; avatar button with the user's initials |

The avatar opens a menu (level 4): full name, roles in words (`Author`, `Approver`), Sign out. The labels "Service", "Tenant" and "Signed in as" are not shown above each value; the service line and the avatar make them clear and the full text is in their accessible names.

**Menu** (`--rail-w` 248 px):

| Group | Items |
|---|---|
| (no heading) | Home · Rules · Approvals, with the number of requests the user can review as plain tabular text on the right |
| Preview | Decisions · Processes |

- Each item is an icon (20 px) and one label. No subtitle under the label: the page header carries it.
- Items are always shown in this order. Every user with a rule permission sees all five, authors included (PD-093).
- An area is hidden only when it holds data the user may not read, and never shown disabled:
  - Decisions, from 0.2.0, shows only to users with permission to read the decision log;
  - Processes shows only when a Process Engine host is registered;
  - a user with no rule permission sees Home only.
- "Preview" is said once in the menu: as the group heading. The items under it carry no tag of their own. In the collapsed menu the group keeps a divider, and the tooltip reads "Decisions (Preview)".
- Footer: Collapse menu. The collapsed menu is 72 px wide with icons and a tooltip label; the count becomes a 6 px dot.
- Item height 44 px (`--target-min`), padding 0 12 px, gap 12 px between icon and label, 4 px between items.

## 9. Component states

Every state below is designed. A state that is not listed does not ship.

### Buttons

Height 44 px (`--target-min`), padding 0 16 px, `--radius-m`, `--type-body` at 600. Icon 16 px, 8 px from the label. Dense table controls, such as the row `⋯` menu, may draw at 32 px but extend their hit area to 44 px ([UX design section 8](../ux-design.md#8-accessibility-and-viewport-checks)).

| Variant | Default | Hover | Pressed | Focus | Loading | Disabled |
|---|---|---|---|---|---|---|
| Primary | `--primary-fill`, `--on-primary` | `--primary-hover` | `--primary-active` | 3 px `--focus` ring, 2 px offset | Spinner replaces the icon, label stays, width fixed, `aria-busy` | `--surface-3`, `--muted`, reason in words beside it |
| Secondary | `--surface`, 1 px `--control`, `--ink` | `--surface-2` | `--surface-3` | as primary | as primary | as primary |
| Ghost | transparent, `--accent` | `--accent-soft` | `--select` | as primary | as primary | `--muted`, no fill |
| Danger (Reject) | `--surface`, 1 px `--bad-fill`, `--bad` | `--bad-soft` | `--bad-soft` with `--bad-fill` 2 px | as primary | as primary | as primary |

One primary button per screen region. Reject is never filled red: it is the secondary choice beside Approve.

### Inputs, selects and the money field

Height 44 px (`--target-min`), padding 0 12 px, `--radius-m`, `--type-body`. Cells in the table editor follow the dense-control rule above.

| State | Treatment |
|---|---|
| Default | `--surface`, 1 px `--control` |
| Hover | border `--ink-2` |
| Focus | border `--focus` and 3 px `--focus` ring at 0 offset |
| Filled | as default; money shows separators while typing: `50,000,000` in English, `50.000.000` in Vietnamese |
| Error | border `--bad-fill`, message below in `--bad` at `--type-label` with a ✕ icon, linked by `aria-describedby` |
| Disabled | `--surface-2`, 1 px `--line`, `--muted` |
| Read only | no border, `--surface-2`, `--ink` |

The money field has its currency as a suffix inside the field (`VND`), not as a separate box.

### Table editor cells (draft editor)

| Cell kind | Read view | Edit view |
|---|---|---|
| Any value (wildcard) | `—` in `--muted` | Empty cell showing `Any` in `--muted` on hover; never an empty input box |
| Choice (`Tier`) | Plain text `Gold` | Select with the allowed labels; several values show as text separated by a middot |
| Number or money with a comparison | `≥ 50,000,000 VND` | Comparison select (`=`, `≥`, `>`, `≤`, `<`, `between`) and the money field |
| Yes or no | `Yes` / `No` | Two-option segmented control |
| Row id | `R3` in `--muted` | Not editable |

Row actions (move up, move down, duplicate, delete) are in one `⋯` menu at the end of the row, and in the row's context menu. They are not four visible buttons on every row.

### Status

A status is plain text, never a box (see [No chips](#no-chips)). `--type-caption` at 600, in `--ink`, after a 6 px dot or a 14 px icon with 4 px between them. Colour only on the dot or icon; the word stays `--ink` unless the state needs attention, when the word takes the state colour too. The dot or icon and the word always appear together.

| Status | Dot or icon | Colour |
|---|---|---|
| Draft | pencil icon | `--muted` |
| Waiting for approval | clock icon | `--accent` |
| Published | 6 px dot | `--ok` |
| Failed, rejected | ✕ icon | `--bad` |
| Preview (page title only) | none, the word in `--muted` text | `--muted` |
| Demo (top bar, sign-in) | none, the word in `--warn` text after a middot | `--warn` |
| Any other environment (top bar, sign-in), first letter upper case: `Production` | none, plain `--muted` text after a middot | `--muted` |

A version has three states: Draft, Waiting for approval and Published. A rejected or withdrawn request sends the version back to Draft, so there is no Rejected or Withdrawn status. Those words appear only in history rows and events, as text with an icon (`✕ Rejected by Trần Văn Minh`, `↩ Withdrawn by Nguyễn Thị Lan`).

### Menu items

| State | Treatment |
|---|---|
| Default | `--ink-2` label, `--muted` icon |
| Hover | `--select` fill; on the selected item `--primary-hover` |
| Selected | Filled item: `--primary-fill`, `--on-primary` label and icon at 600, 10 px radius, no edge tick, `aria-current="page"`. Collapsed, the icon sits in the same item |
| Focus | 3 px `--focus` ring, 2 px offset |
| With count | The number on the right in tabular figures, `--muted`; on the selected item `--on-primary`. No box |

### Tabs

Underline tabs for the views of one object (Table · Changes · History): `--type-label` 600, 2 px `--accent` underline on the active tab, `--muted` for the others. Segmented control for filters inside a card (To review · Your requests): `--surface-3` container, `--radius-seg`, the active item on `--surface` with `--sh-1`.

### Rows and lists

| State | Treatment |
|---|---|
| Default | `--surface`, bottom `--line` |
| Hover | `--surface-2`, cursor pointer when the row opens something |
| Selected | `--select` fill, 3 px `--accent` left bar |
| Matched (Try it) | Selected treatment plus `✓ Matched` as `--accent` text |
| Added (diff) | `--diff-add` fill, `+ Added` in `--ok` |
| Changed value (diff) | `--diff-mod` on the cell, old value struck in `--ink-2`, `→`, new value in `--ink` |
| Removed (diff) | `--diff-del` fill, values struck, `− Removed` in `--bad` |

### Alerts and messages

| Kind | Treatment |
|---|---|
| Error | ✕ icon and the sentence in `--bad`, on the card, with no fill, no border and no left bar. One plain sentence. The error code and the correlation id stay one click away: a `Details` disclosure shows both, each with a Copy button |
| Warning | Same shape: ⚠ icon and sentence in `--warn` |
| Success | Toast at level 4, bottom right, `--ok` check icon, 6 seconds, `aria-live="polite"` |
| Information | An info icon and the sentence in `--accent`; there is no separate info hue |

### Cards and states of a card

| State | Treatment |
|---|---|
| Standard | `--surface`, level 1, `--radius-l`, `--card-pad`, title at `--type-h2` |
| Contrast | `--surface-contrast`, `--on-contrast` text, `--radius-xl`, at most one per screen |
| Empty | A 32 px line icon in `--muted`, one sentence of what will appear, one action if the user may take it |
| Loading | Skeleton bars in `--surface-3` at the height of the real rows; no spinner in a card |
| Error | The card keeps its title; the body becomes the error alert with Retry |

## 10. Sign-in page of the example host

The host owns sign-in; the cockpit never shows a sign-in form. The example host's page uses this styleguide so that sign-in and cockpit read as one product, and a bank copies it and replaces the brand panel.

Layout at 1440x900:

| Region | Spec |
|---|---|
| Ground | Flat `--bg`, no grid |
| Brand panel | Left, 560 px wide, full height, `--surface-contrast`, `--radius-xl` inset 16 px from the window edge. Product mark, service name at `--type-h1` in `--on-contrast`, one line of what the service is for in `--on-contrast-muted`. At the bottom: `HORUS Cockpit` in `--type-caption` |
| Form column | Right, the form centred in the remaining width, 400 px wide, no card frame |
| Form | Heading `Sign in` at `--type-h1`, one subtitle line, Username, Password, a full-width primary `Sign in` button |
| Preferences | Top right of the form column: EN / VI and the theme button |
| Demo accounts | Below the form, only when the host runs the dev profile, never in any other environment: a `--surface-2` area with no border, the word `Demo` in `--warn` text, one sentence, and the demo users as a list of rows: name and role in words. Choosing a row fills the username. No amber frame and no upper-case heading |
| Error | The error alert sits between the subtitle and the fields; both fields get `aria-invalid` |
| Signed out | A success line in the same place |

Below 960 px wide the brand panel becomes a 120 px band above the form.

## 11. Copy rules

- One language at a time; every string comes from the message catalogue.
- Page subtitles and card helper text: ten words or fewer.
- No specification ids, error codes or raw expressions on a product surface. Write `Gold`, `≥ 50,000,000 VND`, `Yes`, never `== 'GOLD'`, `>= 50000000`, `true`.
- Buttons are verbs that say what happens: `Request publish`, `Approve and publish`, `Save draft`, `Run`.
- Errors say what to do: `Add a currency, for example 10,000 VND.`

## 12. CSS to add today

Add these blocks to `cockpit/web/src/components/tokens.css` and to the example host's `sign-in.css`. Dark mode is defined twice: once for an explicit dark choice (`data-theme="dark"`) and once for a dark system setting before any choice is stored. Both dark blocks below carry the same values and must stay identical, so a user on a dark system setting gets the same tokens and contrast as one who chose dark.

```css
:root {
  /* Contrast card and sign-in brand panel: one per screen */
  --surface-contrast: #E1EFD4; --contrast-line: #CFE3BE;
  --on-contrast: #0E2F29; --on-contrast-muted: #3F5650; --on-contrast-accent: #0E4D41;
  --contrast-action: #158370; --on-contrast-action: #FFFFFF;
  --primary-hover: #0E6B5C; --primary-active: #0E4D41;
  --rail: #FFFFFF; --rail-line: var(--line);

  /* Type: size / line height */
  --type-display: 700 32px/1.25 var(--font-ui);
  --type-h1: 650 24px/1.4 var(--font-ui);
  --type-h2: 600 16px/1.4 var(--font-ui);
  --type-body: 400 14px/1.5 var(--font-ui);
  --type-label: 500 13px/1.45 var(--font-ui);
  --type-caption: 500 12px/1.4 var(--font-ui);

  /* Space, radius, layout */
  --space-8: 32px; --space-10: 40px; --space-12: 48px;
  --radius-xs: 4px; --radius-seg: 10px; --radius-xl: 16px; --radius-pill: 999px;
  --topbar-h: 64px; --rail-w: 248px; --rail-w-collapsed: 72px; --inspector-w: 360px;
  --card-pad: 20px; --gap: 16px;

  /* Elevation 3 and 4 */
  --sh-dock: 0 -8px 24px -12px rgba(14, 47, 41, .18);
  --sh-pop: 0 16px 40px -8px rgba(14, 47, 41, .18), 0 2px 6px rgba(14, 47, 41, .06);
}

/* Explicit dark choice */
:root[data-theme="dark"] {
  --surface-contrast: #1B2840; --contrast-line: #3B5A86;
  --on-contrast: #F1F5F9; --on-contrast-muted: #A9B4C6; --on-contrast-accent: #7DD3FC;
  --contrast-action: #F1F5F9; --on-contrast-action: #0B0F17;
  --primary-hover: #38BDF8; --primary-active: #0284C7;
  --rail: #0E141F; --rail-line: var(--line);
  --sh-dock: 0 1px 0 rgba(255, 255, 255, .07) inset, 0 -12px 28px -12px rgba(0, 0, 0, .8);
  --sh-pop: 0 0 0 1px rgba(148, 163, 184, .34), 0 16px 40px -8px rgba(0, 0, 0, .8);
}

/* Dark system setting, before a choice is stored: same values as above */
@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) {
    --surface-contrast: #1B2840; --contrast-line: #3B5A86;
    --on-contrast: #F1F5F9; --on-contrast-muted: #A9B4C6; --on-contrast-accent: #7DD3FC;
    --contrast-action: #F1F5F9; --on-contrast-action: #0B0F17;
    --primary-hover: #38BDF8; --primary-active: #0284C7;
    --rail: #0E141F; --rail-line: var(--line);
    --sh-dock: 0 1px 0 rgba(255, 255, 255, .07) inset, 0 -12px 28px -12px rgba(0, 0, 0, .8);
    --sh-pop: 0 0 0 1px rgba(148, 163, 184, .34), 0 16px 40px -8px rgba(0, 0, 0, .8);
  }
}
```

Apply the type tokens with the `font` shorthand (`font: var(--type-h2)`) and add `letter-spacing` where the scale names it. Body text and the page ground lose the grid:

```css
body { background: var(--bg); background-image: none; }
.diagram-canvas { background-color: var(--canvas); background-image: radial-gradient(var(--dot) 1px, transparent 1px); background-size: 16px 16px; }
```

## 13. Review checklist

Before a screen is called done, check its screenshots at 1440x900 and 1440x1200, light and dark, English and Vietnamese:

- One focal point; the primary action is visible without scrolling at 1440x900.
- Saturated colour covers about 10 % of the screen; every coloured state has a word and a dot or icon.
- No chips: no rounded box with a border or tint around a status, count, tag, metric or legend entry, in the page, the diagram or the docs site.
- No grid on the page ground; no spec ids, raw expressions, error codes in the body, or raw JSON.
- Cards are sized to their content; 35 to 45 % of the screen is open.
- Vietnamese labels fit without truncation; no clipped diacritics.
- Every amount has separators and a currency; every column of numbers uses tabular figures.
- Text at least 4.5:1, control boundaries and focus rings at least 3:1, in both themes.
