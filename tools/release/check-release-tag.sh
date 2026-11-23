#!/usr/bin/env bash
# Release tag guard (PD-087, ADR-0022). Called by tools/release/release-build.sh in step 0 of a tag build;
# also runs on its own, without building anything.
#
# Usage:   tools/release/check-release-tag.sh TAG
# Needs:   Maven 3.9, Python 3.9 and git, from the root of a checkout with origin/main fetched.
#          Assumes the remote "origin" is this repository (as in actions/checkout). Main is read as the
#          remote-tracking ref refs/remotes/origin/main, never a name a tag or branch could shadow.
#          The guard catches mistakes; who may push a release tag is a repository setting.
# Checks:  1. TAG is vX.Y.Z: one project tag per release. A per-module tag (<module>/vX.Y.Z) is refused
#             with the vX.Y.Z form to push instead.
#          2. The root and every module of the reactor (effective POM) are at exactly X.Y.Z, never a
#             -SNAPSHOT, and under the group id io.github.dhluong90.bpm.
#          3. The commit (HEAD) is on main: an ancestor of refs/remotes/origin/main.
#          4. BASELINE_OPEN in tools/check-migration-order.py is False: until 1.0 the V1 baseline of each
#             engine may be edited in place (#479); the release commit closes it.
#          Every check runs and reports before the guard refuses, so one run names every problem.
# Exit:    0 when every check passed, 1 when any failed, 2 on a usage error.

set -euo pipefail

if [[ "$#" -ne 1 || -z "$1" ]]; then
  echo "Usage: tools/release/check-release-tag.sh TAG" >&2
  exit 2
fi
tag="$1"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SETTINGS="$ROOT/tools/release/dry-run-settings.xml"
MVN=(mvn -B -ntp -s "$SETTINGS" -gs "$SETTINGS")
HELP_PLUGIN="org.apache.maven.plugins:maven-help-plugin:3.6.0"
# ADR-0022: the coordinates every release is published under.
RELEASE_GROUP_ID="io.github.dhluong90.bpm"

cd "$ROOT"
work="$(mktemp -d)"
# Keep the exit status: bash 3.2 would otherwise exit with the status of the clean-up.
trap 'status=$?; rm -rf "$work"; exit $status' EXIT
ok=true

# 1 and 2: the tag form, and the version and group id of the root and every module. Maven resolves the
# imported BOMs to write the effective POM; a dropped or throttled download is tried again with -U, which
# also re-asks Central for an artifact an earlier attempt recorded as missing.
pom_ok=false
for attempt in 1 2 3; do
  update=()
  [[ "$attempt" == 1 ]] || update=(-U)
  if "${MVN[@]}" -q ${update[@]+"${update[@]}"} "$HELP_PLUGIN:effective-pom" -Doutput="$work/effective-pom.xml" > "$work/mvn.log" 2>&1; then
    pom_ok=true
    break
  fi
  echo "Maven could not write the effective POM (attempt $attempt of 3)." >&2
  [[ "$attempt" == 3 ]] || sleep $((attempt * 10))
done
if [[ "$pom_ok" == true ]]; then
  python3 "$ROOT/tools/release/release-sbom.py" release-tag "$tag" "$work/effective-pom.xml" \
    --group-id "$RELEASE_GROUP_ID" || ok=false
else
  cat "$work/mvn.log" >&2
  echo "ERROR: tag $tag: Maven could not write the effective POM (above), so no module version or group id was checked." >&2
  # The tag form needs no POM: still name a wrong form in this run.
  python3 "$ROOT/tools/release/release-sbom.py" release-tag "$tag" - --group-id "$RELEASE_GROUP_ID" || true
  ok=false
fi

# 3: the commit is on main.
MAIN_REF="refs/remotes/origin/main"
if ! git rev-parse --verify -q "$MAIN_REF^{commit}" >/dev/null; then
  echo "ERROR: $MAIN_REF is not available; a tag build needs the full history (actions/checkout fetch-depth: 0)."
  ok=false
elif git merge-base --is-ancestor HEAD "$MAIN_REF"; then
  echo "Tag $tag: commit $(git rev-parse --short HEAD) is on main."
else
  echo "ERROR: tag $tag points at commit $(git rev-parse --short HEAD), which is not on main."
  ok=false
fi

# 4: the migration baseline is closed.
if grep -Eq '^BASELINE_OPEN *= *True' "$ROOT/tools/check-migration-order.py"; then
  echo "ERROR: tag $tag: BASELINE_OPEN is still True in tools/check-migration-order.py, so a V1 baseline can still be edited in place; set it to False in the release commit."
  ok=false
else
  echo "Tag $tag: the migration baseline is closed (BASELINE_OPEN is False)."
fi

if [[ "$ok" != true ]]; then
  echo "ERROR: tag $tag: the checks above failed; nothing was built." >&2
  exit 1
fi
echo "Release tag guard passed: $tag."
