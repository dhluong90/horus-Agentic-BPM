#!/usr/bin/env bash
# Runs Maven, and runs it again when it failed only because Maven Central did not deliver an artifact (#171).
#
# Usage:   tools/mvn-retry.sh MAVEN_ARGUMENTS...      (in CI: tools/mvn-retry.sh -B -ntp verify)
# Why:     the resolver already retries dropped connections, 429 and 5xx, honouring Retry-After
#          (.mvn/maven.config). Maven Central's CDN also, now and then, answers 404 for an artifact that
#          exists (maven-install-plugin 3.1.4, spring-boot-dependencies 4.1.2, surefire-junit-platform
#          3.5.6 on 19 Nov 2026). A 404 is not retried by the resolver, so this script retries the run.
# How:     at most 3 attempts. A failed attempt is retried only when one of Maven's own failure summary
#          lines reports a resolution failure:
#            [ERROR] Failed to execute goal ... (Could not resolve dependencies | The following artifacts
#                    could not be resolved | Could not transfer artifact)
#            [ERROR] Plugin <coordinates> or one of its dependencies could not be resolved
#            [ERROR] ... Non-resolvable import POM | Non-resolvable parent POM
#          It is never retried when Surefire or Failsafe report a failing or erroring test, whatever else
#          the log says, so a flaky test is never hidden by a rerun. Any other failure (a test, a gate, a
#          compile error) ends the script at once with Maven's exit status. Before a retry the remembered
#          "not found" markers (*.lastUpdated) are removed and the retry runs with -U, after 60 s, then 120 s
#          (MVN_RETRY_WAIT=<seconds> changes the 60).
#          Every retry is announced as a GitHub warning, and a final resolution failure as an error.
# Exit:    Maven's exit status of the last attempt.

set -uo pipefail

if [[ "$#" -eq 0 ]]; then
  echo "Usage: tools/mvn-retry.sh MAVEN_ARGUMENTS..." >&2
  exit 2
fi
if [[ ! "${MVN_RETRY_WAIT:-60}" =~ ^[0-9]+$ ]]; then
  echo "MVN_RETRY_WAIT must be a whole number of seconds, not '${MVN_RETRY_WAIT}'." >&2
  exit 2
fi
retry_wait="${MVN_RETRY_WAIT:-60}"
# Maven's own summary lines for a resolution failure; test output is never matched.
RESOLUTION_FAILURE='^\[ERROR\] (Failed to execute goal .*(Could not resolve dependencies|The following artifacts could not be resolved|Could not transfer artifact)|Plugin [^ ]+ or one of its dependencies could not be resolved|[[:space:]]*Non-resolvable (import|parent) POM)'
# Surefire and Failsafe reporting a failing or erroring test.
TEST_FAILURE='^\[ERROR\] (Tests run: [0-9]+, (Failures: [1-9]|Failures: [0-9]+, Errors: [1-9])|Failures: *$|Errors: *$|Failed to execute goal .*(maven-surefire-plugin|maven-failsafe-plugin).*(There are test failures|There was a timeout|There are test errors))'
log="$(mktemp)"
# Keep the exit status: bash 3.2 would otherwise exit with the status of the clean-up.
trap 'status=$?; rm -f "$log"; exit $status' EXIT
repository="${HOME}/.m2/repository"

for attempt in 1 2 3; do
  update=()
  [[ "$attempt" == 1 ]] || update=(-U)
  mvn ${update[@]+"${update[@]}"} "$@" 2>&1 | tee "$log"
  status="${PIPESTATUS[0]}"
  [[ "$status" == 0 ]] && exit 0
  if grep -qE "$TEST_FAILURE" "$log" || ! grep -qE "$RESOLUTION_FAILURE" "$log"; then
    exit "$status"
  fi
  missing="$(grep -E '^\[ERROR\]' "$log" | grep -oE 'Could not (find|transfer) artifact [^ ]+' | sed -E 's/^Could not (find|transfer) artifact //' | sort -u | paste -sd ' ' -)"
  if [[ "$attempt" == 3 ]]; then
    echo "::error title=Maven Central download failed::Maven Central did not deliver ${missing:-an artifact} in 3 attempts (#171)."
    exit "$status"
  fi
  wait_seconds=$((attempt * retry_wait))
  echo "::warning title=Maven Central download failed::Attempt ${attempt} of 3: ${missing:-an artifact} not delivered. Retrying with -U in ${wait_seconds} s (#171)."
  find "$repository" -name '*.lastUpdated' -delete 2>/dev/null || true
  sleep "$wait_seconds"
done
