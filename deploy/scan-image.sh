#!/usr/bin/env bash
# Release gates for a container image built from deploy/docker/Dockerfile (docs/DEPLOYING.md).
#
# Usage:   deploy/scan-image.sh IMAGE
#
#   1. No secrets in the layers: no .env, private key or keystore file in any layer or inside any jar
#      (one level of nested jars too), except the exact paths in ALLOWED below; no secret-like variable
#      in the image config; and none of the values of HORUS_DB_ADMIN_PASSWORD, HORUS_DB_PASSWORD and
#      HORUS_DEMO_PASSWORD anywhere in the saved image, including the unpacked contents of every jar.
#      Files a later layer deletes are still checked. Each value comes from the environment, else from
#      its secret file in ./secrets (deploy/create-secrets.sh; HORUS_SECRETS_DIR overrides the folder),
#      else from ./.env when that file exists (deployments made before the secret files).
#   2. Vulnerabilities: Grype scans the image (operating system packages and the Java libraries) with
#      the repository's .grype.yaml and fails on a high or critical finding.
#
# Needs:   docker, and Grype 0.122.1 on PATH or as GRYPE=/path/to/grype. Without either, the script runs
#          the anchore/grype image pinned by digest, the same one tools/scan-dependencies.sh uses.
#          Also unzip, to read the jars.
# Options: SCAN_SECRETS_ONLY=1 runs gate 1 only (deploy/scan-image-canaries.sh uses it).
# Exit:    0 both gates passed, 1 a gate failed, 2 the scan could not run (also: a .jar it cannot read).
#          The Grype JSON report is written to target/grype-image-<image>.json.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRYPE_IMAGE="anchore/grype:v0.122.1@sha256:661583704a253c0448ed63998b5ea2cd9d5a76ee71eedc4aed439b8ce3e93a1c"
IMAGE="${1:?usage: deploy/scan-image.sh IMAGE}"
WORK="$(mktemp -d)"
# Keep the exit status: bash 3.2 would otherwise exit with the status of the clean-up.
trap 'status=$?; rm -rf "$WORK"; exit $status' EXIT

# Key and keystore files: by name, case-insensitive, in the layers and inside jars. Jar signature blocks
# (META-INF/*.RSA, *.DSA, *.EC) hold public certificates and are not matched.
SECRET_NAME='(^|/|!)(\.env(\..*)?|.*\.(pem|key|p8|p12|pfx|jks|keystore|truststore|bcfks)|cacerts|id_rsa|id_dsa|id_ecdsa|id_ed25519)$'
# The only key-like files the runtime image may carry, as exact paths. The Alpine signing keys end in
# .rsa.pub, are public, and do not match SECRET_NAME. Add an entry only with the reason beside it.
ALLOWED=(
  "opt/java/openjdk/lib/security/cacerts"  # the JRE's public CA certificates, no private key
)

secret_value() { # secret_value NAME FILE: the environment, else the secret file, else ./.env if present
  local name="$1" file="${HORUS_SECRETS_DIR:-$ROOT/secrets}/$2"
  if [[ -n "${!name:-}" ]]; then printf '%s' "${!name}"; return; fi
  if [[ -f "$file" ]]; then cat "$file"; return; fi
  if [[ -f "$ROOT/.env" ]]; then sed -n "s/^${name}=//p" "$ROOT/.env" | tail -n 1; fi
}

docker image inspect "$IMAGE" >/dev/null 2>&1 || { echo "Image $IMAGE not found; build it first." >&2; exit 2; }
docker save "$IMAGE" -o "$WORK/image.tar"

# 1. Secrets
command -v unzip >/dev/null 2>&1 || { echo "unzip not found; it is needed to look inside the jars." >&2; exit 2; }
failed=0
mkdir -p "$WORK/fs" "$WORK/jars"
tar -xf "$WORK/image.tar" -C "$WORK/fs"
listing="$WORK/files.txt"
contents="$WORK/contents.bin"
: > "$listing"; : > "$contents"
# Every blob: layer archives (compressed or not) are listed and their file contents collected,
# so a file deleted in a later layer is still seen; config and manifest blobs are taken as they are.
# The jars of each layer are extracted to their own folder, to be read entry by entry below.
layer=0
while IFS= read -r -d '' blob; do
  if members="$(tar -tf "$blob" 2>/dev/null)"; then
    printf '%s\n' "$members" | sed 's|^\./||' >> "$listing"
    tar -xOf "$blob" >> "$contents" 2>/dev/null || true
    if grep -q '\.jar$' <<< "$members"; then
      layer=$((layer + 1)); mkdir -p "$WORK/jars/$layer"
      grep '\.jar$' <<< "$members" > "$WORK/jars/$layer.list"
      tar -xf "$blob" -C "$WORK/jars/$layer" -T "$WORK/jars/$layer.list" 2>/dev/null || true
    fi
  else
    cat "$blob" >> "$contents"
  fi
done < <(find "$WORK/fs" -type f -path '*/blobs/*' -print0)

# read_jar JAR LABEL DEPTH: entry names to the listing as LABEL!entry, entry contents to the value
# search, and the same for each nested jar while DEPTH is 0. A jar unzip cannot read stops the scan.
jars=0
read_jar() {
  local jar="$1" label="$2" depth="$3" entries rc=0
  entries="$(unzip -Z1 "$jar" 2>/dev/null)" || rc=$?
  if (( rc > 1 )); then echo "Cannot read the jar $label (unzip exit $rc)." >&2; exit 2; fi
  jars=$((jars + 1))
  [[ -z "$entries" ]] && return 0
  printf '%s\n' "$entries" | sed "s|^|${label}!|" >> "$listing"
  unzip -p "$jar" >> "$contents" 2>/dev/null || true
  if (( depth == 0 )); then
    local nested n=0
    while IFS= read -r nested; do
      n=$((n + 1))
      unzip -p "$jar" "$nested" > "$WORK/nested-$depth-$n.jar" 2>/dev/null || true
      read_jar "$WORK/nested-$depth-$n.jar" "$label!$nested" 1
      rm -f "$WORK/nested-$depth-$n.jar"
    done < <(grep '\.jar$' <<< "$entries" || true)
  fi
}
while IFS= read -r -d '' jar; do
  [[ -f "$jar" ]] || continue
  rel="${jar#"$WORK/jars/"}"; rel="${rel#*/}"
  read_jar "$jar" "$rel" 0
done < <(find "$WORK/jars" -name '*.jar' -type f -print0 2>/dev/null)

printf '%s\n' "${ALLOWED[@]}" > "$WORK/allowed.txt"
if grep -i -E "$SECRET_NAME" "$listing" | grep -v -x -F -f "$WORK/allowed.txt"; then
  echo "FAIL  secret-like files in the image layers or jars (listed above)" >&2
  failed=1
fi
if docker image inspect "$IMAGE" --format '{{json .Config.Env}}' | grep -i -E '(PASSWORD|SECRET|TOKEN|PRIVATE_KEY)='; then
  echo "FAIL  secret-like variable in the image config" >&2
  failed=1
fi
checked=0
for pair in HORUS_DB_ADMIN_PASSWORD:db_admin_password HORUS_DB_PASSWORD:db_password HORUS_DEMO_PASSWORD:demo_password; do
  name="${pair%%:*}"
  value="$(secret_value "$name" "${pair#*:}")"
  [[ -z "$value" ]] && continue
  checked=$((checked + 1))
  if grep -a -q -F -- "$value" "$contents"; then
    echo "FAIL  the value of $name is inside the image" >&2
    failed=1
  fi
done
(( failed == 0 )) && echo "PASS  no secrets in the image layers, jars or config ($(wc -l < "$listing" | tr -d ' ') files and jar entries checked, $jars jars unpacked, $checked password values searched)"
if [[ "${SCAN_SECRETS_ONLY:-}" == 1 ]]; then exit "$failed"; fi

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
