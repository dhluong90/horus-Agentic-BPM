#!/usr/bin/env bash
# Release tag guard (PD-087, ADR-0022). Called by tools/release/release-build.sh in step 0 of a tag build;
# also runs on its own, without building anything.
#
# Usage:   tools/release/check-release-tag.sh TAG
# Needs:   Maven 3.9, Python 3.9 and git, from the root of a checkout with origin/main fetched.
# Checks:  1. TAG is vX.Y.Z: one project tag per release. A per-module tag (<module>/vX.Y.Z) is refused
#             with the vX.Y.Z form to push instead.
#          2. The root and every module of the reactor (effective POM) are at exactly X.Y.Z, never a
#             -SNAPSHOT, and under the group id io.github.dhluong90.bpm.
#          3. The commit (HEAD) is on origin/main.
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
trap 'rm -rf "$work"' EXIT
ok=true

# 1 and 2: the tag form, and the version and group id of the root and every module.
if "${MVN[@]}" -q "$HELP_PLUGIN:effective-pom" -Doutput="$work/effective-pom.xml" > "$work/mvn.log" 2>&1; then
  python3 "$ROOT/tools/release/release-sbom.py" release-tag "$tag" "$work/effective-pom.xml" \
    --group-id "$RELEASE_GROUP_ID" || ok=false
else
  cat "$work/mvn.log" >&2
  echo "ERROR: tag $tag: Maven could not write the effective POM (above), so no module could be checked." >&2
  ok=false
fi

# 3: the commit is on main.
if ! git rev-parse --verify -q origin/main >/dev/null; then
  echo "ERROR: origin/main is not available; a tag build needs the full history (actions/checkout fetch-depth: 0)."
  ok=false
elif git merge-base --is-ancestor HEAD origin/main; then
  echo "Tag $tag: commit $(git rev-parse --short HEAD) is on main."
else
  echo "ERROR: tag $tag points at commit $(git rev-parse --short HEAD), which is not on main."
  ok=false
fi

if [[ "$ok" != true ]]; then
  echo "ERROR: tag $tag: the checks above failed; nothing was built." >&2
  exit 1
fi
echo "Release tag guard passed: $tag."
