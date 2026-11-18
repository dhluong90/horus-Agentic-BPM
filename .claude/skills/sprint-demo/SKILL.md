---
name: sprint-demo
description: Producing the end-of-sprint demo package that the product owner uses for acceptance - SCRIPT.md, a recorded demo video made with Playwright or a terminal recorder, and REPORT.md with per-feature evidence. Use when closing a sprint, recording a demo, or writing a sprint report.
---
# Sprint demo package: `demos/sprint-<NN>/`

## 1. SCRIPT.md (tech writer)
Step table per feature, in sprint-plan order: step, action (exact clicks or commands), expected result, rule ids proven. Preconditions: demo environment up, seed data loaded, named test users. The product owner must be able to replay it by hand.

## 2. demo.mp4 or demo.webm (frontend engineer or QA)
- Record against the shared demo environment, never a developer machine.
- UI journeys: Playwright with `recordVideo` at 1280×800, one test per feature following SCRIPT.md, with a caption overlay (inject a fixed banner element) that names the feature and rule ids. Concatenate with ffmpeg.
- API-only features: show them through the cockpit or console where possible; otherwise record a terminal session with the exact requests and responses.
- 10 to 20 minutes total. Keep a chapter list with timestamps in REPORT.md.
- Files over 50 MB go through Git LFS (`.gitattributes` is set) or shared storage with a link in REPORT.md.

## 3. REPORT.md (delivery lead)
```
# Sprint NN report
Sprint goal: …   Dates: …   Video: demo.mp4 (chapters below)
| # | Feature | Issue | Status (done / partly / not done) | Evidence (tests, CI, PR) | Video chapter |
Deviations from the plan: …
Decisions needed from the product owner: …
Risks for the next sprint: …
Next sprint scope: …
Retrospective: what slowed us, what changes next sprint.
Acceptance (filled by the product owner): feature → accepted / rejected with reason
```

## Rules
- A feature without a passing acceptance test is not shown as done.
- Rejected features return to the top of the next sprint.
- Anything that breaks the scripted demo is the sprint's top priority.
