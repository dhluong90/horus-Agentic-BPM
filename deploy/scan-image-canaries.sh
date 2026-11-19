#!/usr/bin/env bash
# Proves that the secrets gate of deploy/scan-image.sh catches what it claims to (docs/DEPLOYING.md).
#
# Usage:   deploy/scan-image-canaries.sh IMAGE
#
# Builds two throwaway images on top of IMAGE and runs the secrets gate (SCAN_SECRETS_ONLY=1) on each:
#   1. a key file planted at /usr/local/share/canary/canary.pem must fail the gate;
#   2. a random value inside a deflated entry of a jar planted in /app/BOOT-INF/lib must fail the gate,
#      with the value passed as HORUS_DEMO_PASSWORD; the script first checks that the value is not
#      readable in the raw jar bytes, which is what a scan without unpacking would see;
#   3. IMAGE itself, with the same value, must pass.
# Needs:   docker, zip, unzip, openssl.
# Exit:    0 all three behaved as expected, 1 otherwise.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="${1:?usage: deploy/scan-image-canaries.sh IMAGE}"
WORK="$(mktemp -d)"
TAG="horus-bpm-scan-canary-$$"
trap 'status=$?; rm -rf "$WORK"; docker image rm -f "$TAG:pem" "$TAG:jar" >/dev/null 2>&1 || true; exit $status' EXIT

pass() { printf 'PASS  %s\n' "$*"; }
fail() { printf 'FAIL  %s\n' "$*" >&2; exit 1; }

docker image inspect "$IMAGE" >/dev/null 2>&1 || fail "image $IMAGE not found; build it first"
value="canary-$(openssl rand -hex 16)"
[[ -n "${GITHUB_ACTIONS:-}" ]] && echo "::add-mask::$value"

# gate IMAGE LOG: the secrets gate, its output in LOG, its exit status echoed
gate() {
  local rc=0
  HORUS_DEMO_PASSWORD="$value" SCAN_SECRETS_ONLY=1 "$ROOT/deploy/scan-image.sh" "$1" > "$2" 2>&1 || rc=$?
  echo "$rc"
}

# 1. A key file under /usr/local/share
mkdir -p "$WORK/pem"
printf '%s\n' '-----BEGIN PRIVATE KEY-----' 'canary, not a real key' '-----END PRIVATE KEY-----' > "$WORK/pem/canary.pem"
docker build -q -t "$TAG:pem" -f - "$WORK/pem" >/dev/null <<DOCKERFILE
FROM $IMAGE
COPY canary.pem /usr/local/share/canary/canary.pem
DOCKERFILE
rc="$(gate "$TAG:pem" "$WORK/pem.log")"
[[ "$rc" == 1 ]] && grep -q -F 'usr/local/share/canary/canary.pem' "$WORK/pem.log" \
  || { cat "$WORK/pem.log"; fail "canary 1: a .pem under /usr/local/share passed the gate (exit $rc)"; }
pass "canary 1: a .pem under /usr/local/share fails the gate: $(grep -F 'canary.pem' "$WORK/pem.log")"

# 2. A value inside a deflated jar entry in BOOT-INF/lib
mkdir -p "$WORK/jar/src/config" "$WORK/jar/ctx"
for i in $(seq 1 50); do printf 'canary.line%s=padding so that the entry is deflated, not stored\n' "$i"; done \
  > "$WORK/jar/src/config/application.properties"
printf 'spring.datasource.password=%s\n' "$value" >> "$WORK/jar/src/config/application.properties"
(cd "$WORK/jar/src" && zip -q -9 -r "$WORK/jar/ctx/canary-0.0.1.jar" config)
unzip -v "$WORK/jar/ctx/canary-0.0.1.jar" | grep -q 'Defl' || fail "canary 2: the jar entry was stored, not deflated"
if grep -a -q -F -- "$value" "$WORK/jar/ctx/canary-0.0.1.jar"; then
  fail "canary 2: the value is readable in the raw jar bytes, so this canary proves nothing"
fi
docker build -q -t "$TAG:jar" -f - "$WORK/jar/ctx" >/dev/null <<DOCKERFILE
FROM $IMAGE
COPY canary-0.0.1.jar /app/BOOT-INF/lib/canary-0.0.1.jar
DOCKERFILE
rc="$(gate "$TAG:jar" "$WORK/jar.log")"
[[ "$rc" == 1 ]] && grep -q -F 'the value of HORUS_DEMO_PASSWORD is inside the image' "$WORK/jar.log" \
  || { cat "$WORK/jar.log"; fail "canary 2: a value inside a jar in BOOT-INF/lib passed the gate (exit $rc)"; }
pass "canary 2: a value inside a deflated jar entry in BOOT-INF/lib fails the gate (not visible in the raw bytes)"

# 3. The image itself
rc="$(gate "$IMAGE" "$WORK/clean.log")"
[[ "$rc" == 0 ]] || { cat "$WORK/clean.log"; fail "the image $IMAGE fails the secrets gate (exit $rc)"; }
pass "the image $IMAGE passes: $(grep -F 'PASS' "$WORK/clean.log")"
