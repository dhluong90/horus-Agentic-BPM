#!/usr/bin/env bash
# Release build, dry run (TR-LIC-01, TR-SEC-06, TR-UI-06). Nothing is published.
#
# Usage:   tools/release/release-build.sh
#          Run from a clean checkout (no change and no untracked file) of the commit or tag to release.
# Needs:   JDK 21 or later, Maven 3.9, Docker (Testcontainers tests), Python 3.9, git, and Node.js at
#          exactly the version in tools/release/node-version with npm, and network access to Maven
#          Central and the npm registry.
# Env:     BPM_RELEASE_TAG=vX.Y.Z            a tag build, one project tag (PD-087): the root and every
#                                            module at exactly X.Y.Z (never a SNAPSHOT), the group id
#                                            io.github.dhluong90.bpm (ADR-0022), the commit on
#                                            origin/main (tools/release/check-release-tag.sh)
#          BPM_RELEASE_TRIAL=true            local trial: accepts another Node version and a dirty
#                                            working tree. BUILD-INFO.json and the SBOM record
#                                            "trial": true. Refused when CI or GITHUB_ACTIONS is true.
# Output:  target/release/
#            maven-repo/          every artifact, as `mvn deploy` would upload it (staging)
#            BUILD-INFO.json      commit, clean or dirty, tag, trial flag, toolchain versions and hashes
#            bom.json, bom.xml    Maven SBOM of the shipped modules (runtime and provided scopes)
#            bom-npm.json         npm SBOM of the packages in the cockpit bundle (CycloneDX)
#            bom-release.json     the two merged: the release bill of materials
#            cockpit-bundle.txt   SHA-256 of every cockpit bundle file, written right after the build
#            cockpit-modules.json the npm packages and bundler runtime modules in the cockpit bundle
#            SHA256SUMS           SHA-256 of every file above; also printed to the log and the step summary
# Exit:    0 when every step passed; non-zero on the first failure, which is named.
#
# Steps:
#   0. Preflight: trial mode refused in CI; clean working tree; tag version and branch; Node version.
#   1. mvn clean and delete cockpit/web/dist, so nothing left from another build can be packaged.
#      BUILD-INFO.json is written.
#   2-3. tools/release/build-cockpit.sh, shared with the container image: release tools and cockpit
#      dependencies from their lockfiles with no install scripts; lockfile hosts and integrity; registry
#      signatures; npm offline from then on; licence check, tsc -b and vite build, recording the packages
#      in the bundle and the SHA-256 of every bundle file right after the build.
#   4. Publishing guard, then mvn deploy into target/release/maven-repo with every test and gate.
#   5. The staged starter jar must carry exactly the bundle recorded in step 3.
#   6. npm SBOM, after the jar is sealed: every bundled package must be a production dependency with an
#      allowed licence; the SBOMs are merged and validated against CycloneDX 1.6.
#   7. SHA256SUMS over target/release.
# Without cockpit sources (cockpit/web/package.json absent) the cockpit parts of steps 2, 3, 5 and 6 are
# skipped and the release is Java only; the log says so.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT="$ROOT/target/release"
WEB="$ROOT/cockpit/web"
TOOLS="$ROOT/tools/release/npm"
SBOM_PY="$ROOT/tools/release/release-sbom.py"
SETTINGS="$ROOT/tools/release/dry-run-settings.xml"
NODE_PINNED="$(tr -d '[:space:]' < "$ROOT/tools/release/node-version")"
# Every Maven call goes through tools/mvn-retry.sh (#171): a run that Maven Central failed to deliver an
# artifact to is retried with -U, at most 3 attempts; a failing test, gate or compile error never is.
# No dependency cache is restored either way.
MVN=("$ROOT/tools/mvn-retry.sh" -B -ntp -s "$SETTINGS" -gs "$SETTINGS")
HELP_PLUGIN="org.apache.maven.plugins:maven-help-plugin:3.6.0"
NPM_CI=(npm ci --ignore-scripts --no-audit --no-fund)
LOCKFILE_LINT=(lockfile-lint --type npm --allowed-hosts npm --validate-https --validate-integrity
  --validate-package-names --empty-hostname false)

step() { printf '\n== %s\n' "$*"; }
die() { echo "ERROR: $*" >&2; exit 1; }
# The value is the last line: a retry announces itself on the lines before it.
evaluate() { "${MVN[@]}" -q "$HELP_PLUGIN:evaluate" -Dexpression="$1" -DforceStdout "${@:2}" | tail -n 1; }

cd "$ROOT"
cockpit=false
[[ -f "$WEB/package.json" ]] && cockpit=true
trial=false
label="release build"

step "0. Preflight"
if [[ "${BPM_RELEASE_TRIAL:-}" == "true" ]]; then
  if [[ "${CI:-}" == "true" || "${GITHUB_ACTIONS:-}" == "true" ]]; then
    die "BPM_RELEASE_TRIAL=true is for local trials only and is refused in CI (CI or GITHUB_ACTIONS is true)."
  fi
  trial=true
  label="TRIAL build, not a release"
fi

changes="$(git status --porcelain)"
if [[ -n "$changes" ]]; then
  if [[ "$trial" == true ]]; then
    echo "WARNING: working tree is not clean; recorded as dirty in BUILD-INFO.json (trial)."
  else
    printf '%s\n' "$changes" | head -20 >&2
    die "the working tree is not clean (above). Release from a clean checkout, or set BPM_RELEASE_TRIAL=true locally."
  fi
fi

tag="${BPM_RELEASE_TAG:-}"
if [[ -n "$tag" ]]; then
  # One project tag vX.Y.Z (PD-087): every module at X.Y.Z under the release group id, commit on main.
  # Every check reports before the guard refuses.
  "$ROOT/tools/release/check-release-tag.sh" "$tag" || die "tag $tag refused by the release tag guard (above)."
fi

node_actual="$(node --version 2>/dev/null || echo none)"
if [[ "$node_actual" != "v$NODE_PINNED" ]]; then
  [[ "$trial" == true ]] || die "Node $node_actual found; releases build with Node v$NODE_PINNED (tools/release/node-version)."
  echo "WARNING: Node $node_actual instead of v$NODE_PINNED (trial)."
fi
echo "Trial: $trial. Cockpit sources: $cockpit. Node $node_actual, npm $(npm --version)."

step "1. Clean: mvn clean, remove any cockpit bundle left in the working tree"
"${MVN[@]}" -q clean
if [[ -f "$WEB/dist/index.html" ]]; then
  echo "Removing $WEB/dist (index.html sha256 $(shasum -a 256 "$WEB/dist/index.html" | cut -c1-16)...)"
fi
rm -rf "$WEB/dist" "$OUT"
mkdir -p "$OUT"
python3 "$SBOM_PY" build-info "$OUT/BUILD-INFO.json" --trial "$trial" --tag "$tag"

if [[ "$cockpit" == true ]]; then
  step "2-3. Cockpit bundle: tools/release/build-cockpit.sh (the same script the container image uses)"
  # Lockfile installs with no install scripts, lockfile-lint, npm audit signatures, then licences,
  # tsc -b and vite build with the bundle record and its SHA-256 written right after the build.
  "$ROOT/tools/release/build-cockpit.sh" "$OUT"
else
  step "2-3. No cockpit sources (cockpit/web/package.json): Java-only release, no npm SBOM"
  # The same order as build-cockpit.sh: lockfile checked before install, signatures before any exec.
  node "$ROOT/tools/release/lockfile-check.mjs" "$TOOLS/package-lock.json"
  (cd "$TOOLS" && "${NPM_CI[@]}" && npm audit signatures)
  (cd "$TOOLS" && npm exec --offline -- "${LOCKFILE_LINT[@]}" --path "$TOOLS/package-lock.json")
fi
# Nothing more is fetched from the npm registry in this build.
export npm_config_offline=true

step "4. Publishing guard, then build, test and stage every artifact (mvn deploy into target/release/maven-repo)"
"${MVN[@]}" -q "$HELP_PLUGIN:effective-pom" -Doutput="$ROOT/target/release-effective-pom.xml"
python3 "$SBOM_PY" deploy-plugins "$ROOT/target/release-effective-pom.xml"
"${MVN[@]}" deploy -DaltDeploymentRepository="release-staging::file://$OUT/maven-repo"
cp target/bom.json target/bom.xml "$OUT/"

if [[ "$cockpit" == true ]]; then
  step "5. The staged cockpit starter jar carries exactly the bundle recorded after the build"
  group="$(evaluate project.groupId -N)"
  version="$(evaluate project.version -N)"
  staged="$OUT/maven-repo/${group//.//}/cockpit-spring-boot-starter/$version"
  jars=()
  while IFS= read -r -d '' jar; do jars+=("$jar"); done < <(find "$staged" -maxdepth 1 -type f \
    -name 'cockpit-spring-boot-starter-*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' ! -name '*-tests.jar' -print0)
  [[ "${#jars[@]}" == 1 ]] || die "expected exactly one staged cockpit starter jar in $staged, found ${#jars[@]}."
  python3 "$SBOM_PY" cockpit-jar "$OUT/cockpit-bundle.txt" "${jars[0]}"

  step "6. npm SBOM of the bundle, licences, merged and validated release SBOM"
  (cd "$TOOLS" && npm exec --offline -- cyclonedx-npm --omit dev --flatten-components --mc-type library \
     --spec-version 1.6 --output-format JSON --output-reproducible --output-file "$OUT/bom-npm-declared.json" \
     -- "$WEB/package.json")
  python3 "$SBOM_PY" bundle-sbom "$OUT/cockpit-modules.json" "$OUT/bom-npm-declared.json" "$OUT/bom-npm.json"
  rm "$OUT/bom-npm-declared.json"
  python3 "$SBOM_PY" npm-licences "$OUT/bom-npm.json"
  python3 "$SBOM_PY" merge "$OUT/bom.json" "$OUT/bom-npm.json" "$OUT/bom-release.json" --build-info "$OUT/BUILD-INFO.json"
else
  step "6. Release SBOM (Java only), validated"
  python3 "$SBOM_PY" merge "$OUT/bom.json" - "$OUT/bom-release.json" --build-info "$OUT/BUILD-INFO.json"
fi
(cd "$TOOLS" && node ../validate-sbom.mjs "$OUT/bom-release.json")

step "7. SHA256SUMS over target/release"
python3 "$SBOM_PY" manifest "$OUT" "$OUT/SHA256SUMS" --exclude SHA256SUMS
cat "$OUT/SHA256SUMS"
if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  {
    echo "### Release dry run: SHA256SUMS of target/release"
    echo
    echo "Commit \`$(git rev-parse HEAD)\`, trial \`$trial\`${tag:+, tag \`$tag\`}."
    echo
    echo '```'
    cat "$OUT/SHA256SUMS"
    echo '```'
  } >> "$GITHUB_STEP_SUMMARY"
fi

step "Done: $label"
echo "Staged artifacts:"
(cd "$OUT/maven-repo" && find . -name '*.jar' -o -name '*.pom' | grep -v -E 'maven-metadata' | LC_ALL=C sort)
echo "Release bill of materials: $OUT/bom-release.json"
echo "Scan it with: tools/scan-dependencies.sh $OUT/bom-release.json"
