#!/usr/bin/env bash
# Runs Maven, and runs it again when it failed only because Maven Central did not deliver an artifact (#171).
#
# Usage:   tools/mvn-retry.sh MAVEN_ARGUMENTS...      (in CI: tools/mvn-retry.sh -B -ntp verify)
# Why:     the resolver already retries dropped connections, 429 and 5xx, honouring Retry-After
#          (.mvn/maven.config). Maven Central's CDN also, now and then, answers 404 for an artifact that
#          exists (maven-install-plugin 3.1.4, spring-boot-dependencies 4.1.2, surefire-junit-platform
#          3.5.6 on 19 Nov 2026). A 404 is not retried by the resolver, so this script retries the run.
# How:     at most 3 attempts. A failed attempt is retried only when Maven's own [ERROR] lines say an
#          artifact could not be found, transferred or resolved; any other failure (a test, a gate, a
#          compile error) ends the script at once with Maven's exit status. Before a retry the remembered
#          "not found" markers (*.lastUpdated) are removed and the retry runs with -U, after 30 s, then 60 s
#          (MVN_RETRY_WAIT=<seconds> changes the 30).
#          Every retry is announced as a GitHub warning, and a final resolution failure as an error.
# Exit:    Maven's exit status of the last attempt.

set -uo pipefail

if [[ "$#" -eq 0 ]]; then
  echo "Usage: tools/mvn-retry.sh MAVEN_ARGUMENTS..." >&2
  exit 2
fi
RESOLUTION_FAILURE='^\[ERROR\].*(Could not find artifact|Could not transfer artifact|could not be resolved|Non-resolvable (import|parent) POM|Failed to read artifact descriptor)'
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
  if ! grep -qE "$RESOLUTION_FAILURE" "$log"; then
    exit "$status"
  fi
  missing="$(grep -oE 'Could not (find|transfer) artifact [^ ]+' "$log" | sed -E 's/^Could not (find|transfer) artifact //' | sort -u | paste -sd ' ' -)"
  if [[ "$attempt" == 3 ]]; then
    echo "::error title=Maven Central download failed::Maven Central did not deliver ${missing:-an artifact} in 3 attempts (#171)."
    exit "$status"
  fi
  wait_seconds=$((attempt * ${MVN_RETRY_WAIT:-30}))
  echo "::warning title=Maven Central download failed::Attempt ${attempt} of 3: ${missing:-an artifact} not delivered. Retrying with -U in ${wait_seconds} s (#171)."
  find "$repository" -name '*.lastUpdated' -delete 2>/dev/null || true
  sleep "$wait_seconds"
done
