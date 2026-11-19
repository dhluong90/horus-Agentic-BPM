#!/usr/bin/env bash
# Vulnerability gate (TR-SEC-06): scan the CycloneDX SBOM of the shipped modules
# and fail on any dependency with a known vulnerability of high or critical severity.
#
# Usage:   tools/scan-dependencies.sh [sbom]
#          The default SBOM is target/bom.json, written by `mvn -B verify`
#          (or `mvn -B package`) at the repository root.
# Needs:   Grype 0.122 or later (Apache 2.0, free vulnerability database, no account
#          or key), either on PATH or as GRYPE=/path/to/grype. Without either, the
#          script runs the anchore/grype container image with Docker.
#          The first run downloads the vulnerability database (about 100 MB),
#          so it needs network access; later runs reuse the cache.
# Exit:    0 no high or critical finding, 1 at least one, 2 SBOM or tooling missing.
#
# The table lists each finding with the package, its version, the fixed version
# when there is one, and the advisory id (GHSA-..., CVE-...). Settings, including
# the severity threshold and the reviewed exceptions, live in .grype.yaml.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SBOM="${1:-$ROOT/target/bom.json}"
GRYPE_IMAGE="anchore/grype:v0.122.1"

if [[ ! -f "$SBOM" ]]; then
  echo "No SBOM at $SBOM. Build it first: mvn -B verify -DskipTests" >&2
  exit 2
fi

SBOM_DIR="$(cd "$(dirname "$SBOM")" && pwd)"
SBOM_FILE="$(basename "$SBOM")"

if [[ -n "${GRYPE:-}" ]]; then
  cmd=("$GRYPE")
elif command -v grype >/dev/null 2>&1; then
  cmd=(grype)
elif command -v docker >/dev/null 2>&1; then
  cache="${GRYPE_DB_CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/grype/db}"
  mkdir -p "$cache"
  cmd=(docker run --rm
    -v "$SBOM_DIR:/sbom:ro"
    -v "$ROOT/.grype.yaml:/.grype.yaml:ro"
    -v "$cache:/cache"
    -e GRYPE_DB_CACHE_DIR=/cache
    "$GRYPE_IMAGE")
  echo "Scanning $SBOM with $GRYPE_IMAGE"
  exec "${cmd[@]}" "sbom:/sbom/$SBOM_FILE" --config /.grype.yaml
else
  echo "Grype not found. Install it (https://github.com/anchore/grype#installation) or install Docker." >&2
  exit 2
fi

echo "Scanning $SBOM with $("${cmd[@]}" version 2>/dev/null | awk '/^Version:/ {print "grype " $2}')"
exec "${cmd[@]}" "sbom:$SBOM" --config "$ROOT/.grype.yaml"
