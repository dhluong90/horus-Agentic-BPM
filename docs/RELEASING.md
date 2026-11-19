# Releasing

This page describes the release build. Publishing to Maven Central starts once the namespace is registered (ADR-0022). Until then a tag run publishes a GitHub Release from the checked build output (see GitHub Release).

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

Maven runs with `tools/release/dry-run-settings.xml` as both user and global settings, so `~/.m2/settings.xml` and its credentials are never read. Every Maven call goes through `tools/mvn-retry.sh` (#171). When Maven Central fails to deliver an artifact, the call is run again with `-U`, at most 3 attempts, after 60 s and then 120 s. A failing test, gate or compile error is never retried, and no dependency cache is restored. Without cockpit sources the cockpit parts are skipped and the log says "Java-only release".

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
- the tagged commit is on `main`: an ancestor of `refs/remotes/origin/main` (`git merge-base --is-ancestor`). The guard reads that full ref name, so a tag or branch named `origin/main` cannot stand in for it, and it assumes the remote `origin` is this repository, as `actions/checkout` sets it.

**The guard catches mistakes; it is not access control.** Anyone who can push a tag can run the workflow. Who may create `v*` tags, and whether a published Release can be changed afterwards, are repository settings. For now the repository has no tag ruleset (PD-091).

**Fix a bad release with a new patch release, never by re-tagging (PD-091).** If `v0.1.0` turns out wrong after it is pushed, fix the cause on `main` and release `v0.1.1`. Never delete, move or re-push a release tag: adopters and their caches may already hold the first build, and the same tag must always mean the same bytes.

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

The workflow's job "Release tag guard tests" proves each refusal on every pull request that changes the release files. It runs `tools/release/test_release_sbom.py` (unit tests of the check) and `tools/release/test-release-tag.sh`. The second builds a scratch copy of the tree at the release version and group id, commits it as `main`, and runs the guard for seven cases: `v0.1.0` on `main` passes; a per-module tag, a module at another version and a commit off `main` are refused; a per-module tag on a commit off `main` names both problems; a POM that Maven cannot read is refused, with a per-module tag still named; and a tag named `origin/main` on a commit off `main` does not fake the main check.

## GitHub Release

On a tag run that passed the release build, the job `github-release` publishes the GitHub Release for the tag. It uses the archive `release-dry-run-success` of the same run and rebuilds nothing. It is the only job with a write token (`contents: write`). It has no checkout, no Maven and no npm, and uses one action, `actions/download-artifact`, pinned by commit SHA. Pull request and dispatch runs skip it.

| Step | What happens | Fails when, and what is left |
|---|---|---|
| 1 | Download `release-dry-run-success` of this run | The release build failed: the job does not start |
| 2 | `shasum -a 256 -c SHA256SUMS` over the archive, and every file in it must be listed | A file differs from `SHA256SUMS`, or is not listed: no draft |
| 3 | `BUILD-INFO.json` must say `"trial": false`, `"workingTree": "clean"`, and the tag and commit of this run | Any field differs: no draft |
| 4 | Assets: every jar and POM under `maven-repo` (no `maven-metadata`, no checksum side files), `bom-release.json`, `SHA256SUMS`, `BUILD-INFO.json` | Two assets share a file name, or there is no jar: no draft |
| 5 | `gh release create "$TAG" --verify-tag --draft` with every asset and the release notes | An upload fails: a draft only, never a published Release |
| 6 | The draft's assets are downloaded and checked against the `SHA256SUMS` of the archive checked in step 2 (the draft's own copy must be identical to it); then `gh release edit --draft=false` | An asset is missing, extra or different: the draft stays unpublished |

The release notes link this page and give the check adopters run in the folder of their downloads:

```
while read -r hash path; do name="${path##*/}"; [ -f "$name" ] && printf '%s  %s\n' "$hash" "$name"; done < SHA256SUMS | shasum -a 256 -c -
```

Each downloaded file prints `<name>: OK`. Step 6 runs the same command on the draft's downloads, reading the archive's `SHA256SUMS`.

The text of steps 2 to 4 sits between `# BEGIN release-archive-check` and `# END release-archive-check` in the workflow. `tools/release/test-release-upload.sh` runs that exact text against fixture archives on every pull request that changes the release files. It checks that a good archive passes; that a changed file, an unlisted file, a trial build, another commit, a dirty tree and duplicate asset names are each refused; and that the adopters' check passes on good downloads and catches a changed one.

To recover from a run that left only a draft, delete the draft (`gh release delete v0.1.0`), fix the cause, and re-run the workflow for the tag. Once a Release is published, never re-tag: fix it with the next patch release, `v0.1.1` (PD-091, see Tags).

## Local trial

`BPM_RELEASE_TRIAL=true tools/release/release-build.sh` accepts another Node version and a dirty working tree. `BUILD-INFO.json` and the SBOM then record `"trial": true`, and the log ends with "TRIAL build, not a release". The script refuses trial mode when `CI` or `GITHUB_ACTIONS` is `true`.

## Not yet

- Signing, sources and Javadoc jars, and the upload to Maven Central arrive with the publishing slice. GitHub Releases are published (see GitHub Release).
- Provenance and SBOM attestations on tag runs before publishing: `actions/attest-build-provenance` for the staged jars and POMs, and `actions/attest-sbom` for `bom-release.json`, with `id-token: write` and `attestations: write` granted to that job only.
- Real publishing lives in its own workflow with no `pull_request` trigger, behind a protected environment that holds the Central credentials. Pull requests from branches of this repository receive secrets, so publishing never shares a workflow with that trigger.
- The standalone cockpit archive comes with standalone mode (#11).
