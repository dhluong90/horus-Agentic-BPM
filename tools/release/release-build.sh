#!/usr/bin/env bash
# Release build, dry run (TR-LIC-01, TR-SEC-06, TR-UI-06). Nothing is published.
#
# Usage:   tools/release/release-build.sh
#          Run from a clean checkout of the commit or tag to release.
# Needs:   JDK 21 or later, Maven 3.9, Docker (Testcontainers tests), Python 3.9, and Node.js at exactly
#          the version in tools/release/node-version with npm. BPM_RELEASE_ALLOW_ANY_NODE=true accepts
#          another Node version for a local trial; the output then says it is not a release build.
# Output:  target/release/
#            maven-repo/          every published artifact, as `mvn deploy` would upload it (staging)
#            bom.json             Maven SBOM of the shipped modules (runtime and provided scopes)
#            bom-npm.json         npm SBOM of the cockpit's production packages (CycloneDX)
#            bom-release.json     the two merged: the release bill of materials
#            cockpit-bundle.txt   sha256 of every file of the cockpit bundle packaged in the starter
# Exit:    0 when every step passed; non-zero on the first failure, which is named.
#
# Steps:
#   1. Delete cockpit/web/dist, so a bundle left from another branch can never be packaged.
#   2. npm ci, licence check of the production packages, npm run build: the bundle from these sources.
#   3. npm SBOM of the production packages with @cyclonedx/cyclonedx-npm (pinned below).
#   4. mvn deploy into target/release/maven-repo, with every test, the licence gate and the Maven SBOM.
#   5. The starter jar must carry exactly the fresh bundle; the npm licences are checked again from the
#      SBOM; the two SBOMs are merged.
# Without cockpit sources (cockpit/web/package.json absent) steps 1 to 3 are skipped and the release is
# Java only; the log says so.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="$ROOT/target/release"
WEB="$ROOT/cockpit/web"
CYCLONEDX_NPM="@cyclonedx/cyclonedx-npm@7.0.1"
NODE_PINNED="$(tr -d '[:space:]' < "$ROOT/tools/release/node-version")"
release_label="release build"

step() { printf '\n== %s\n' "$*"; }

cd "$ROOT"
rm -rf "$OUT"
mkdir -p "$OUT"

cockpit=false
if [[ -f "$WEB/package.json" ]]; then
  cockpit=true
  node_actual="$(node --version 2>/dev/null || echo none)"
  if [[ "$node_actual" != "v$NODE_PINNED" ]]; then
    if [[ "${BPM_RELEASE_ALLOW_ANY_NODE:-}" == "true" ]]; then
      release_label="TRIAL build with Node $node_actual (pinned: v$NODE_PINNED), not a release"
      echo "WARNING: $release_label"
    else
      echo "Node $node_actual found; releases build the cockpit with Node v$NODE_PINNED (tools/release/node-version)." >&2
      exit 1
    fi
  fi

  step "1. Remove any cockpit bundle left in the working tree"
  if [[ -f "$WEB/dist/index.html" ]]; then
    echo "Removing $WEB/dist (index.html sha256 $(shasum -a 256 "$WEB/dist/index.html" | cut -c1-16)...)"
  fi
  rm -rf "$WEB/dist"

  step "2. Build the cockpit bundle from these sources with Node $(node --version)"
  (cd "$WEB" && npm ci --no-audit --no-fund && npm run licences && npm run build)

  step "3. npm SBOM of the shipped (production) packages"
  (cd "$WEB" && npx --yes "$CYCLONEDX_NPM" --omit dev --flatten-components --mc-type library --spec-version 1.6 --output-format JSON \
     --output-reproducible --output-file "$OUT/bom-npm.json")
else
  echo "No cockpit sources (cockpit/web/package.json): Java-only release, no npm SBOM."
fi

step "4. Build, test and stage every artifact (mvn deploy into target/release/maven-repo)"
mvn -B -ntp deploy -DaltDeploymentRepository="release-staging::file://$OUT/maven-repo"
cp target/bom.json target/bom.xml "$OUT/"

step "5. Check the packaged bundle and the npm licences; merge the bills of materials"
if [[ "$cockpit" == true ]]; then
  starter_jar="$(ls "$ROOT"/cockpit/cockpit-spring-boot-starter/target/cockpit-spring-boot-starter-*.jar \
    | grep -v -E -- '-(sources|javadoc)\.jar$' | head -1)"
  python3 "$ROOT/tools/release/release-sbom.py" cockpit-jar "$WEB/dist" "$starter_jar"
  (cd "$WEB/dist" && find . -type f ! -name cockpit-hosts.json | LC_ALL=C sort | xargs shasum -a 256) > "$OUT/cockpit-bundle.txt"
  python3 "$ROOT/tools/release/release-sbom.py" npm-licences "$OUT/bom-npm.json"
  python3 "$ROOT/tools/release/release-sbom.py" merge "$OUT/bom.json" "$OUT/bom-npm.json" "$OUT/bom-release.json"
else
  cp "$OUT/bom.json" "$OUT/bom-release.json"
fi

step "Done: $release_label"
echo "Staged artifacts:"
(cd "$OUT/maven-repo" && find . -name '*.jar' -o -name '*.pom' | grep -v -E 'maven-metadata' | LC_ALL=C sort)
echo "Release bill of materials: $OUT/bom-release.json"
echo "Scan it with: tools/scan-dependencies.sh $OUT/bom-release.json"
