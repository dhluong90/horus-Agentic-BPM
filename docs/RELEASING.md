# Releasing

This page describes the release build. Publishing to Maven Central starts once the namespace is registered (ADR-0022). Until then the build is a dry run, and its output is uploaded to the workflow run.

## One command

From a clean checkout (no change and no untracked file) of the commit or tag to release:

```
tools/release/release-build.sh
```

It needs JDK 21 or later, Maven 3.9, Docker (for the Testcontainers tests), Python 3.9, git, and Node.js at exactly the version in `tools/release/node-version` (22.23.5) with npm. It also needs network access to Maven Central and the npm registry.

The workflow "Release (dry run)" (`.github/workflows/release.yml`) runs the same script on every `vX.Y.Z` tag (one project tag per release, PD-087), on demand, and on pull requests that change the release files. It then scans the release bill of materials for high and critical vulnerabilities. It restores no dependency cache, for Maven or npm.

| Step | What happens | Fails when |
|---|---|---|
| 0 | Preflight; on a tag, the release tag guard (`tools/release/check-release-tag.sh`, see Tags) | Trial mode in CI; a dirty working tree; on a tag, any failed tag check; another Node version |
| 1 | `mvn clean` and `cockpit/web/dist` deleted, so nothing left from another build is packaged; `BUILD-INFO.json` written | |
| 2 | `tools/release/build-cockpit.sh` (meant for the container image too; see "One cockpit build"): both lockfiles checked by `tools/release/lockfile-check.mjs` (Node built-ins only) before anything is installed; `npm ci --ignore-scripts` then `npm audit signatures`, for the release tools (`tools/release/npm`) and then the cockpit, before any of their code runs; `lockfile-lint` on both lockfiles as a second opinion. npm is offline from here on | A lockfile entry is not from `https://registry.npmjs.org/` or lacks a sha512 integrity, or a registry signature does not verify |
| 3 | Same script: cockpit licence check, `tsc -b` and the cockpit's `vite build` through `tools/release/cockpit-build.mjs`, which records the npm packages in the bundle and writes the SHA-256 of every bundle file right after the build (`cockpit-bundle.txt`) | A production package is not under Apache 2.0, MIT or BSD |
| 4 | Publishing guard on the effective POM, then `mvn deploy` into `target/release/maven-repo`: every test, the Maven licence gate and the Maven bill of materials | Any plugin other than `maven-deploy-plugin` bound to `deploy`, a build extension or a publishing plugin; any test or gate |
| 5 | The staged starter jar in `maven-repo` (exactly one) must carry exactly the files recorded in step 3 under `META-INF/horus-cockpit/` | A file is missing, different or stale |
| 6 | After the jar is sealed: npm bill of materials (`cyclonedx-npm` from the tool lockfile, `npm exec --offline`), kept to the packages in the bundle; licences; merge; CycloneDX 1.6 schema validation | A bundled package is not a production dependency; a licence is not allowed; the merged document is not valid |
| 7 | `SHA256SUMS` over `target/release`, printed to the log and to the run's step summary | |

Maven runs with `tools/release/dry-run-settings.xml` as both user and global settings, so `~/.m2/settings.xml` and its credentials are never read. Without cockpit sources the cockpit parts are skipped and the log says "Java-only release".

The release tools (`@cyclonedx/cyclonedx-npm`, `lockfile-lint`, and `ajv` with `ajv-formats` and `ajv-formats-draft2019` for the schema check) are exact-version dev dependencies in `tools/release/npm/package-lock.json`. They are not part of the cockpit's dependency tree, never ship, and are named on the `TR-LIC-01` allow-list of build tools. To update one, change the version in `tools/release/npm/package.json` and run `npm install --package-lock-only --ignore-scripts` there.

## One cockpit build

`tools/release/build-cockpit.sh OUT_DIR` is the only way the shipped cockpit bundle is built. `OUT_DIR` must be outside `cockpit/web`. The script refuses `BPM_RELEASE_TRIAL=true` under CI, and `cockpit-modules.json` records the Node and npm versions and the trial flag of the build. The release build calls it, and the container image (`deploy/docker/Dockerfile`) should call it in its cockpit stage. It is POSIX `sh` and needs only Node and npm, so it runs in the `node:22-alpine` stage. The same sources give the same bytes in both places: the bundle built in `node:22-alpine` and the one built on macOS had identical SHA-256 for all three files.

## Expected output

`target/release/` holds:

| File | Content |
|---|---|
| `maven-repo/` | Every artifact as `mvn deploy` would upload it: the BOM, parents, engine and cockpit starter jars and POMs |
| `BUILD-INFO.json` | Commit, `clean` or `dirty`, tag, `trial`, CI run, and the version, path and SHA-256 of node, npm, java, Maven and Python, plus the SHA-256 of both npm lockfiles. Also copied into `bom-release.json` as `horus:build:*` properties |
| `bom.json`, `bom.xml` | Maven bill of materials of the shipped modules |
| `bom-npm.json` | npm bill of materials of the packages in the cockpit bundle, plus the bundler's runtime helpers (`horus:bundledAs=bundler runtime`) |
| `bom-release.json` | The release bill of materials: both merged, with a new serial number and both source serial numbers in `metadata.properties`. npm packages carry `horus:shippedIn=cockpit-spring-boot-starter` |
| `cockpit-bundle.txt` | SHA-256 of every file of the cockpit bundle, written right after the build |
| `cockpit-modules.json` | The npm packages and bundler runtime modules found in the bundle |
| `SHA256SUMS` | SHA-256 of every file above. Check it with `shasum -a 256 -c SHA256SUMS` from `target/release` |

The workflow uploads the folder as `release-dry-run-success` or `release-dry-run-failure`, and writes the archive's SHA-256 (`artifact-digest`) and `SHA256SUMS` to the run's step summary, which is a record kept apart from the archive.

The last lines of the log are:

```
Cockpit bundle check passed: cockpit-spring-boot-starter-<version>.jar carries the <n> files recorded right after the build (index.html sha256 ...).
Bundle completeness check passed: <n> bundled package(s), all production dependencies; <n> bundler runtime package(s).
npm licence check passed: <n> shipped package(s), all under allowed licences (TR-LIC-01).
target/release/bom-release.json: <n> components, <n> npm package(s) shipped in cockpit-spring-boot-starter
.../bom-release.json: valid CycloneDX 1.6
...
== Done: release build
```

## Tags

A release is one tag for the whole project, `vX.Y.Z`, starting with `v0.1.0` (PD-087). Every module is released at that version, together with `bpm-bom`. To release:

```
git tag -a v0.1.0 -m "HORUS Agentic BPM 0.1.0" <commit on main>
git push origin v0.1.0
```

A tag build (`BPM_RELEASE_TAG`, set by the workflow on tag pushes) runs `tools/release/check-release-tag.sh` and fails unless:

- the tag is `vX.Y.Z`. A per-module tag such as `rule-engine/v0.1.0` is refused with the form to push instead;
- the root and **every** module of the reactor (read from the effective POM) are at exactly `X.Y.Z`, so a `-SNAPSHOT` is never tagged as a release and no module is left behind;
- every module has the group id `io.github.dhluong90.bpm` (ADR-0022), so no release ships under the old `com.horus.bpm` coordinates; the rename is #21;
- the tagged commit is on `main` (`git merge-base --is-ancestor`).

Every check runs and reports before the guard refuses, so one run names every problem. Maven resolves the imported BOMs to write the effective POM; a failed attempt is tried twice more with `-U`. If Maven still cannot write it, the guard refuses and still checks the tag form. The guard builds nothing, so it can be run on its own before pushing a tag:

```
tools/release/check-release-tag.sh v0.1.0
```

On the commit to release it ends with:

```
Tag check passed: v0.1.0; all 12 module(s) are io.github.dhluong90.bpm at version 0.1.0.
Tag v0.1.0: commit <sha> is on main.
Release tag guard passed: v0.1.0.
```

A per-module tag is refused like this:

```
ERROR: tag rule-engine/v0.1.0 is a per-module tag; a release is one project tag of the form vX.Y.Z, here v0.1.0 (PD-087). Delete rule-engine/v0.1.0 and push v0.1.0.
```

The workflow's job "Release tag guard tests" proves each refusal on every pull request that changes the release files. It runs `tools/release/test_release_sbom.py` (unit tests of the check) and `tools/release/test-release-tag.sh`. The second builds a scratch copy of the tree at the release version and group id, commits it as `main`, and runs the guard for six cases: `v0.1.0` on `main` passes; a per-module tag, a module at another version and a commit off `main` are refused; a per-module tag on a commit off `main` names both problems; and a POM that Maven cannot read is refused, with a per-module tag still named.

## Local trial

`BPM_RELEASE_TRIAL=true tools/release/release-build.sh` accepts another Node version and a dirty working tree. `BUILD-INFO.json` and the SBOM then record `"trial": true`, and the log ends with "TRIAL build, not a release". The script refuses trial mode when `CI` or `GITHUB_ACTIONS` is `true`.

## Not yet

- Signing, sources and Javadoc jars, and the upload to Maven Central arrive with the publishing slice.
- Provenance and SBOM attestations on tag runs before publishing: `actions/attest-build-provenance` for the staged jars and POMs, and `actions/attest-sbom` for `bom-release.json`, with `id-token: write` and `attestations: write` granted to that job only.
- Real publishing lives in its own workflow with no `pull_request` trigger, behind a protected environment that holds the Central credentials. Pull requests from branches of this repository receive secrets, so publishing never shares a workflow with that trigger.
- The standalone cockpit archive comes with standalone mode (#11).
