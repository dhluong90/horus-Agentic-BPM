# Releasing

This page describes the release build. Publishing to Maven Central starts once the namespace is registered (ADR-0022); until then the build is a dry run, and its output is uploaded to the workflow run.

## One command

From a clean checkout of the commit or tag to release:

```
tools/release/release-build.sh
```

It needs JDK 21 or later, Maven 3.9, Docker (for the Testcontainers tests), Python 3.9, and Node.js at exactly the version in `tools/release/node-version` (22.23.5) with npm. The workflow "Release (dry run)" (`.github/workflows/release.yml`) runs the same script on every `<module>/vX.Y.Z` tag, on demand, and on pull requests that change the release files. It then scans the release bill of materials for high and critical vulnerabilities.

| Step | What happens | Fails when |
|---|---|---|
| 1 | `cockpit/web/dist` is deleted, so a bundle left from another branch is never packaged | |
| 2 | `npm ci`, the licence check of the cockpit's production packages, `npm run build` | A shipped npm package is not under Apache 2.0, MIT or BSD; the build fails |
| 3 | npm bill of materials of the production packages (`@cyclonedx/cyclonedx-npm`, pinned in the script) | |
| 4 | `mvn deploy` into `target/release/maven-repo`: every test, the Maven licence gate and the Maven bill of materials | Any test or gate |
| 5 | The cockpit starter jar must carry exactly the fresh bundle under `META-INF/horus-cockpit/`; npm licences are checked again from the bill of materials; the two bills of materials are merged | A file is missing, different or stale; a licence is not allowed |

Without cockpit sources the first three steps are skipped and the log says "Java-only release".

## Expected output

`target/release/` holds:

| File | Content |
|---|---|
| `maven-repo/` | Every artifact as `mvn deploy` would upload it: the BOM, parents, engine and cockpit starter jars and POMs |
| `bom.json`, `bom.xml` | Maven bill of materials of the shipped modules |
| `bom-npm.json` | npm bill of materials of the cockpit's production packages |
| `bom-release.json` | The release bill of materials: both merged. npm packages carry `horus:shippedIn=cockpit-spring-boot-starter` |
| `cockpit-bundle.txt` | SHA-256 of every file of the packaged cockpit bundle |

The last lines of the log are:

```
Cockpit bundle check passed: cockpit-spring-boot-starter-<version>.jar carries the <n> files of the fresh build (index.html sha256 ...).
npm licence check passed: <n> shipped package(s), all under allowed licences (TR-LIC-01).
target/release/bom-release.json: <n> components, <n> npm package(s) shipped in cockpit-spring-boot-starter
== Done: release build
```

A local trial with another Node version runs with `BPM_RELEASE_ALLOW_ANY_NODE=true`; the log then ends with "TRIAL build ... not a release".

## Not yet

Signing, sources and Javadoc jars, and the upload to Maven Central arrive with the publishing slice. The standalone cockpit archive comes with standalone mode (#11).
