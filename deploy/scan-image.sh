#!/usr/bin/env bash
# Release gates for a container image built from deploy/docker/Dockerfile (docs/DEPLOYING.md).
#
# Usage:   deploy/scan-image.sh IMAGE
#
#   1. No secrets in the layers: no .env, private key or keystore file in any layer, no secret-like
#      variable in the image config, and none of the values of HORUS_DB_ADMIN_PASSWORD,
#      HORUS_DB_PASSWORD and HORUS_DEMO_PASSWORD (from the environment or ./.env) anywhere in the saved image.
#   2. Vulnerabilities: Grype scans the image (operating system packages and the Java libraries) with
#      the repository's .grype.yaml and fails on a high or critical finding.
#
# Needs:   docker, and Grype 0.122.1 on PATH or as GRYPE=/path/to/grype. Without either, the script runs
#          the anchore/grype image pinned by digest, the same one tools/scan-dependencies.sh uses.
# Exit:    0 both gates passed, 1 a gate failed, 2 the scan could not run.
#          The Grype JSON report is written to target/grype-image-<image>.json.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRYPE_IMAGE="anchore/grype:v0.122.1@sha256:661583704a253c0448ed63998b5ea2cd9d5a76ee71eedc4aed439b8ce3e93a1c"
IMAGE="${1:?usage: deploy/scan-image.sh IMAGE}"
WORK="$(mktemp -d)"
# Keep the exit status: bash 3.2 would otherwise exit with the status of the clean-up.
trap 'status=$?; rm -rf "$WORK"; exit $status' EXIT

env_value() {
  local name="$1"
  if [[ -n "${!name:-}" ]]; then printf '%s' "${!name}"; return; fi
  [[ -f "$ROOT/.env" ]] && sed -n "s/^${name}=//p" "$ROOT/.env" | tail -n 1
}

docker image inspect "$IMAGE" >/dev/null 2>&1 || { echo "Image $IMAGE not found; build it first." >&2; exit 2; }
docker save "$IMAGE" -o "$WORK/image.tar"

# 1. Secrets
failed=0
mkdir -p "$WORK/fs"
tar -xf "$WORK/image.tar" -C "$WORK/fs"
listing="$WORK/files.txt"
contents="$WORK/contents.bin"
: > "$listing"; : > "$contents"
# Every blob: layer archives (compressed or not) are listed and their file contents collected,
# so a file deleted in a later layer is still seen; config and manifest blobs are taken as they are.
while IFS= read -r -d '' blob; do
  if tar -tf "$blob" >> "$listing" 2>/dev/null; then
    tar -xOf "$blob" >> "$contents" 2>/dev/null || true
  else
    cat "$blob" >> "$contents"
  fi
done < <(find "$WORK/fs" -type f -path '*/blobs/*' -print0)
if grep -E '(^|/)(\.env(\..*)?|.*\.(pem|key|p12|jks|pfx)|id_rsa|id_ed25519)$' "$listing" \
     | grep -v -E '^(usr|opt/java|etc/ssl[^/]*|lib)/'; then
  echo "FAIL  secret-like files in the image layers (listed above)" >&2
  failed=1
fi
if docker image inspect "$IMAGE" --format '{{json .Config.Env}}' | grep -i -E '(PASSWORD|SECRET|TOKEN|PRIVATE_KEY)='; then
  echo "FAIL  secret-like variable in the image config" >&2
  failed=1
fi
for name in HORUS_DB_ADMIN_PASSWORD HORUS_DB_PASSWORD HORUS_DEMO_PASSWORD; do
  value="$(env_value "$name")"
  [[ -z "$value" ]] && continue
  if grep -a -q -F -- "$value" "$contents"; then
    echo "FAIL  the value of $name is inside the image" >&2
    failed=1
  fi
done
(( failed == 0 )) && echo "PASS  no secrets in the image layers or config ($(wc -l < "$listing" | tr -d ' ') files checked)"

# 2. Vulnerabilities
mkdir -p "$ROOT/target"
report_name="grype-image-$(printf '%s' "$IMAGE" | tr -c 'A-Za-z0-9._-' '_').json"
rc=0
if [[ -n "${GRYPE:-}" ]] || command -v grype >/dev/null 2>&1; then
  "${GRYPE:-grype}" "docker-archive:$WORK/image.tar" --config "$ROOT/.grype.yaml" --fail-on high \
    --show-suppressed -o table -o "json=$ROOT/target/$report_name" || rc=$?
else
  cache="${GRYPE_DB_CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/grype/db}"
  mkdir -p "$cache"
  docker run --rm -v "$WORK:/work:ro" -v "$ROOT/.grype.yaml:/.grype.yaml:ro" -v "$ROOT/target:/report" \
    -v "$cache:/cache" -e GRYPE_DB_CACHE_DIR=/cache "$GRYPE_IMAGE" \
    docker-archive:/work/image.tar --config /.grype.yaml --fail-on high --show-suppressed \
    -o table -o "json=/report/$report_name" || rc=$?
fi
# Grype exits 2 when a finding reaches the threshold and 1 on any other error.
case "$rc" in
  0) echo "PASS  no high or critical vulnerability in $IMAGE (report: target/$report_name)" ;;
  2) echo "FAIL  high or critical vulnerability in $IMAGE (table above, report: target/$report_name)" >&2; failed=1 ;;
  *) echo "Grype failed with exit $rc." >&2; exit 2 ;;
esac

exit "$failed"
