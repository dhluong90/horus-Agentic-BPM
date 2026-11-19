#!/usr/bin/env bash
# Vulnerability gate (TR-SEC-06): scan a CycloneDX SBOM with Grype.
#
# Usage:   tools/scan-dependencies.sh            shipped modules, target/bom.json; fails on high or critical
#          tools/scan-dependencies.sh --build    build and test classpath, target/bom-build.json; fails on critical
#          tools/scan-dependencies.sh --canary   scans tools/fixtures/known-vulnerable.cdx.json the same
#                                                way as target/bom.json and passes only if that scan
#                                                exits 1, which proves the gate still fails
#          tools/scan-dependencies.sh FILE       any SBOM; fails on high or critical
#          Build the SBOMs first with `mvn -B verify` (or `mvn -B verify -DskipTests`).
# Needs:   Grype 0.122.1 (Apache 2.0, free vulnerability database, no account or key), on PATH or as
#          GRYPE=/path/to/grype. Without either, the script runs the anchore/grype image pinned by
#          digest with Docker. The first run downloads the database (about 100 MB), so it needs
#          network access; later runs reuse the cache.
# Exit:    0 no finding at the threshold, 1 at least one (for --canary: the canary was not caught),
#          2 SBOM missing, Grype missing, the config lint failed or the scan itself failed.
#
# Settings and reviewed exceptions live in .grype.yaml and are linted first
# (tools/check-dependency-policy.py grype-config). Suppressed findings are always shown.
# The table names each package, version, fixed version and advisory (GHSA-..., CVE-...); a JSON
# report is written to target/grype-<sbom name>.json.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRYPE_IMAGE="anchore/grype:v0.122.1@sha256:661583704a253c0448ed63998b5ea2cd9d5a76ee71eedc4aed439b8ce3e93a1c"

if [[ "${1:-}" == --canary ]]; then
  rc=0
  "${BASH_SOURCE[0]}" "$ROOT/tools/fixtures/known-vulnerable.cdx.json" || rc=$?
  if [[ "$rc" == 1 ]]; then
    echo "Canary caught: the scan exits 1 on the known critical advisory, as it must."
    exit 0
  fi
  echo "Canary NOT caught (exit $rc, expected 1): the vulnerability gate would let a critical finding through." >&2
  exit 1
fi

mode=shipped
case "${1:-}" in
  --build)  mode=build;  SBOM="$ROOT/target/bom-build.json" ;;
  "")       SBOM="$ROOT/target/bom.json" ;;
  *)        SBOM="$1" ;;
esac
fail_on=high
[[ "$mode" == build ]] && fail_on=critical

if [[ ! -f "$SBOM" ]]; then
  echo "No SBOM at $SBOM. Build it first: mvn -B verify -DskipTests" >&2
  exit 2
fi
if ! python3 "$ROOT/tools/check-dependency-policy.py" grype-config "$ROOT/.grype.yaml"; then
  exit 2
fi

SBOM_DIR="$(cd "$(dirname "$SBOM")" && pwd)"
SBOM_FILE="$(basename "$SBOM")"
REPORT_DIR="$ROOT/target"
mkdir -p "$REPORT_DIR"
REPORT_FILE="grype-${SBOM_FILE%.json}.json"

if [[ -n "${GRYPE:-}" ]] || command -v grype >/dev/null 2>&1; then
  cmd=("${GRYPE:-grype}")
  target="sbom:$SBOM"
  config="$ROOT/.grype.yaml"
  report="$REPORT_DIR/$REPORT_FILE"
  label="$("${cmd[@]}" version 2>/dev/null | awk '/^Version:/ {print "grype " $2}')"
elif command -v docker >/dev/null 2>&1; then
  cache="${GRYPE_DB_CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/grype/db}"
  mkdir -p "$cache"
  cmd=(docker run --rm
    -v "$SBOM_DIR:/sbom:ro"
    -v "$ROOT/.grype.yaml:/.grype.yaml:ro"
    -v "$REPORT_DIR:/report"
    -v "$cache:/cache"
    -e GRYPE_DB_CACHE_DIR=/cache
    "$GRYPE_IMAGE")
  target="sbom:/sbom/$SBOM_FILE"
  config=/.grype.yaml
  report="/report/$REPORT_FILE"
  label="$GRYPE_IMAGE"
else
  echo "Grype not found. Install it (https://github.com/anchore/grype#installation) or install Docker." >&2
  exit 2
fi

echo "Scanning $SBOM with $label, failing on $fail_on or above"
# Grype exits 2 when a finding reaches the threshold and 1 on any other error.
status=0
"${cmd[@]}" "$target" --config "$config" --fail-on "$fail_on" --show-suppressed \
  -o table -o "json=$report" || status=$?

summary="$(python3 - "$REPORT_DIR/$REPORT_FILE" 2>/dev/null <<'PY' || true
import json, sys
data = json.load(open(sys.argv[1]))
print(f"{len(data.get('matches', []))} finding(s), {len(data.get('ignoredMatches', []))} suppressed")
PY
)"
if [[ -n "$summary" ]]; then
  echo "$SBOM_FILE: $summary (report: target/$REPORT_FILE)"
  if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    echo "- Vulnerability scan of \`$SBOM_FILE\` (fail on $fail_on): $summary, Grype exit $status" >> "$GITHUB_STEP_SUMMARY"
  fi
fi

case "$status" in
  0) echo "Vulnerability gate passed: no finding at $fail_on or above." ;;
  2) echo "Vulnerability gate FAILED: the table above names each package and advisory at $fail_on or above. Upgrade it, or see wiki/Contributing.md." >&2
     exit 1 ;;
  *) echo "Vulnerability scan did not complete (grype exit $status)." >&2
     exit 2 ;;
esac
