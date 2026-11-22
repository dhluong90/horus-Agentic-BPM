# Upgrading without downtime

Adjacent minor releases of the engines upgrade while the service keeps running. For a few minutes, or as long as your rollout takes, replicas of release N and release N+1 share one database schema, and clients written for N talk to N+1. This page says what the engines promise and what you do.

## What is promised

- **An older replica starts against a newer schema.** The migrator ignores applied migrations its build does not contain, and `aheadMigrations()` on `ProcessEngineSchemaMigrator` and `RuleEngineSchemaMigrator` says how many. It applies nothing, and the Rule Engine's health stays up.
- **A migration only expands.** New tables, new nullable or defaulted columns, new indexes, wider constraints. A release never drops, renames or retypes what the release before it uses. Removals ship one minor release later, after no replica needs the old shape. A build test lints every migration after the previous release.
- **A value is read before it is written.** A new status or job kind is written only after one minor release that can read it. A job kind an older Process Engine replica does not know is handed back untouched (`DUE`, no attempt used, log `process.job.deferred`), never failed. A newer replica claims it.
- **A response only grows.** Within `v1` a release adds members to responses, optional members to requests, problem codes and event members. Ignore a response member you do not know. The API contract tests keep every response schema open to new members.

## What you do

1. Back up, then start one replica of the new release. It runs the migrations (`migrate-on-start`), or run them as a separate step as the [deployment guide](../docs/DEPLOYING.md) describes.
2. Roll the other replicas. Replicas of the old release keep serving; watch for `process.job.deferred` in the logs, which is a job the old replicas hand back.
3. Wait for every replica to run the new release before you use a feature the release notes mark as "after the rollout".

## 0.2.0 to 0.3.0

0.3.0 is the first release that carries this promise, so 0.2.0 replicas do not defer jobs of an unknown kind: they fail them, and they cannot read the statuses 0.3.0 added. Until every replica runs 0.3.0, do not:

- start a process that calls another (`callProcess`), which writes `CALL_RESULT` jobs and `WAITING_CHILD` tokens;
- resolve an incident with a proposal, or execute a migration plan of running instances;
- discard a draft rule set version.

From 0.3.0 on, the rules above protect the next upgrade.

See also [[Process Engine: API|Process-Engine-API]] for the contract and [[Common error codes|Common-Error-Codes]].
