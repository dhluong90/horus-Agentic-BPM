# Support and versioning policy

What you can rely on when you adopt a release: how version numbers work, what counts as a breaking change, how long a deprecated element stays, which versions get fixes, and how to report a vulnerability.

This page states the technical rules. Support hours, response times, service levels and commercial terms are not in it: they are **to be decided by the maintainers** and are listed at the [end](#not-decided-yet).

## At a glance

| Question | Answer |
|---|---|
| What is the version? | One version for the whole project, `MAJOR.MINOR.PATCH`, one tag `vX.Y.Z` per release. Every module and `bpm-bom` carry it |
| When can a change break me? | Only in a major release (2.0 is the next one) |
| How long does a deprecated element stay? | At least one minor release and at least six months, and is removed only in a major release |
| Which versions get fixes? | The latest minor release. Versions before 1.0.0 get none |
| How do I report a vulnerability? | Privately, as [`SECURITY.md`](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/SECURITY.md) says. Never in a public issue |
| Is there a long-term support line? | No. Not decided: see [Not decided yet](#not-decided-yet) |

## Semantic versioning

From 1.0.0 the project follows [semantic versioning](https://semver.org/).

| Release | Example | What it promises |
|---|---|---|
| Patch | 1.0.1 | Fixes only. Binary and source compatible. No frozen element changes |
| Minor | 1.1.0 | Additive. Binary compatible. New features are off or keep today's behaviour by default |
| Major | 2.0.0 | May break. Every removal is listed in its release notes |

Release 0.x had no users and is not supported. 1.0.0 is the first release with a compatibility promise, and the release notes list the database migrations of every release ([[Release notes|Release-Notes]]).

## What counts as breaking

The promise covers what a host builds on:

- Java: the `api` and `spi` packages of every published module.
- REST: every operation in the published OpenAPI files, under `/api/bpm/rules/v1` and `/api/bpm/processes/v1`, and `/api/bpm/me` and `/api/bpm/meta`.
- Error `code` values and their HTTP status, and the Problem Details body.
- Events: every type, version and payload field.
- Definition formats: the `rule-set.v1` and `process-definition.v1` schemas, and the BPMN and DMN elements imported.
- Canonical hashes of stored versions.
- Every `horus.bpm.*` property: name, type, default and meaning.
- Metric names and tag keys listed in the wiki.

**A minor release may only add:** new operations, optional parameters and fields, response and event fields, event types, error codes for new behaviour, properties whose default keeps today's behaviour, and new SPI methods as `default` methods.

**These are breaking, so they happen only in a major release:**

- Removing or renaming a frozen element.
- Adding a required parameter or field, or making an optional one required.
- Narrowing accepted values; changing a type, a status code or the error code of an existing case; changing what a field means.
- Refusing a definition document the previous release accepted.
- Changing a canonical hash.
- Adding an abstract SPI method, or a value to a closed enum or sealed result type.
- **Changing a default so that an unchanged deployment behaves differently.**

A breaking REST change ships under a new path version (`/v2`) beside `/v1`.

**Not covered, and may change in any release:** `internal` packages; database tables, columns and indexes (read engine data through Java or REST, never SQL); the wording of `title`, `detail` and `message` (the `code` is the contract); log lines and cockpit screens; and anything marked Preview (`x-horus-preview: true` in OpenAPI, or stated in the Javadoc).

**Write clients as tolerant readers:** ignore unknown JSON fields, treat an unknown error `code` by its HTTP status, ignore unknown event types, and give every enum a default branch. A client that does this runs unchanged on every 1.x release.

The CI compares each pull request with the last release and fails the build on a break that the version number does not allow.

## Deprecation

A deprecated element:

1. Names its replacement and is marked on every surface: `@Deprecated(since = "1.2", forRemoval = true)` in Java, `deprecated: true` in OpenAPI with `Deprecation` and `Sunset` headers on every response, a start-up warning for a property, and so on.
2. Keeps working. A deprecated property is still read; a deprecated event is still published beside its replacement; a deprecated error code is still returned for its case.
3. Stays **at least one minor release and at least six months** after the release that deprecates it.
4. Is removed only in a major release. The release notes of the deprecating release give the earliest version that may remove it, and the notes of the major release list every removal.

Example: 1.2.0 deprecates a property on 1 March 2027. It is read in 1.3.0 and every later 1.x release. The earliest removal is 2.0.0, and not before 1 September 2027.

## Supported versions

- **Supported:** the latest minor release (for example 1.1.x once 1.1.0 is out) receives fixes as patch releases.
- **Not supported:** every 0.x release.
- **An older 1.x minor** is not named as supported. Whether it receives security fixes, and for how long, is to be decided by the maintainers.
- **Long-term support (LTS):** there is no LTS line.

A host upgrades by changing the version of `bpm-bom`. Run the checks in the [[Operations runbook|Operations-Runbook]], "Upgrade to a later release", on a copy of production data first.

## Security fixes

- A fix ships as a **patch release** of the supported version, with a security note in the changelog and the release notes.
- A fix may break compatibility only when no compatible fix exists. The advisory and the release notes then name the changed element, why no compatible fix was possible, and what a host must do.
- Dependencies are scanned on every change, and the build fails on a known high or critical vulnerability in anything that ships. The latest release is scanned again every Monday. A finding is fixed by a patch release with the fixed dependency or base image, never by moving a tag.
- Every release carries a bill of materials and signed build provenance, so you can check what you run. See the [[Operations runbook|Operations-Runbook]], "Verify a release artifact".
- Do not wait for the next release if a fix is marked as security: upgrade to the patch release as soon as you have tested it.

## Reporting a vulnerability

**Do not open a public issue.** Follow [`SECURITY.md`](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/SECURITY.md): it gives the address and the details to send (the affected module and version, and steps to reproduce).

Fix times and disclosure timelines are to be decided by the maintainers.

## Waivers and exceptions

A waiver lets the build pass although a scanner reports a finding that has no fix yet. It is a temporary, written decision, never a default.

| Rule | Detail |
|---|---|
| Reason | Every waiver has a written reason: why the finding cannot be fixed now, why it cannot be reached or used, and what will end the waiver |
| Expiry | **30 days at most** from the day it is written. A longer need is a new waiver, written again with a new reason |
| Owner and issue | Every waiver names an owner and an issue |
| Scope: test images | A waiver in `tools/test-image-waivers.json` is only for an image that is never shipped and holds no real data, such as one started by a test. The weekly rescan still scans and lists a waived image, and files an issue again once the waiver has expired |
| Scope: dependencies (CVE exception) | An entry in `.grype.yaml` names one advisory, one package and one version, and is allowed only when no fixed version exists. The reason ends with `expires YYYY-MM-DD` |
| Shipped artifacts | A waiver never covers an image or dependency that ships in a release |
| After expiry | The check fails the build. Fix the finding, or write a new waiver with a new reason |

The tools check that a dated entry has not expired and that a dependency exception ends with an expiry date. The 30-day limit and the quality of the reason are checked in review.

## Not decided yet

The maintainers decide these. Until they are written here, nothing on this page promises them.

- Support hours, response and fix times, and any service level agreement.
- Whether an older minor release receives security fixes, and for how long.
- A long-term support line.
- Commercial support terms and pricing. See [[Licensing and commercial use|Licensing-and-Commercial-Use]].
