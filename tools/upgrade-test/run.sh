#!/usr/bin/env bash
# Upgrade test from a released version to the checkout (#658).
#
#   tools/upgrade-test/run.sh [OLD_REF]      OLD_REF defaults to v1.0.0 (a tag or a commit of this repository)
#
# Builds the example host examples/rule-and-process from OLD_REF in a temporary worktree and from the checkout,
# then runs tools/upgrade-test/upgrade_test.py, which seeds the old build's database and starts the new build on it.
# Needs git with the tag fetched (git fetch --tags), Docker, Maven, Java 21 or later and python3.
# It removes the worktree it made. Logs of both hosts stay in $UPGRADE_TEST_LOGS (default: a temporary folder, printed).
set -euo pipefail
cd "$(dirname "$0")/../.."

OLD_REF="${1:-v1.0.0}"
git rev-parse --verify --quiet "$OLD_REF^{commit}" >/dev/null || { echo "upgrade-test: $OLD_REF is not in this clone; run git fetch --tags" >&2; exit 2; }

WORK="$(cd "$(mktemp -d)" && pwd -P)"
LOGS="${UPGRADE_TEST_LOGS:-$WORK/logs}"
cleanup() {
  git worktree remove --force "$WORK/old" >/dev/null 2>&1 || true
  [ -z "${UPGRADE_TEST_KEEP:-}" ] && rm -rf "$WORK/old" || true
}
trap cleanup EXIT

build() { # build <source folder>
  (cd "$1" && mvn -B -ntp -q -pl examples/rule-and-process -am package -DskipTests -Dcockpit.bundle.skip=true)
}
jar() { ls "$1"/examples/rule-and-process/target/example-rule-and-process-*.jar | grep -v -- '-plain' | head -n 1; }

echo "upgrade-test: building $OLD_REF"
git worktree add --detach "$WORK/old" "$OLD_REF" >/dev/null
build "$WORK/old"
OLD_JAR="$(jar "$WORK/old")"
echo "upgrade-test: building the checkout ($(git rev-parse --short HEAD))"
build "$PWD"
NEW_JAR="$(jar "$PWD")"
cp "$OLD_JAR" "$WORK/old.jar"

echo "upgrade-test: logs in $LOGS"
python3 tools/upgrade-test/upgrade_test.py --old-jar "$WORK/old.jar" --new-jar "$NEW_JAR" --logs "$LOGS"
