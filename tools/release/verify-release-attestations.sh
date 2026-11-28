#!/usr/bin/env bash
# Release gate (TR-SEC-06, #664, #258): every staged jar and POM has a signed build provenance statement, every
# jar is a subject of the signed CycloneDX SBOM statement, and that statement carries the release bill of
# materials. A missing, unsigned, wrong-workflow, wrong-ref or mismatched statement fails the gate.
#
# Usage:   tools/release/verify-release-attestations.sh ARCHIVE_DIR
#            ARCHIVE_DIR  the release folder: maven-repo/ with the jars and POMs, bom-release.json
#          A flat folder of downloaded Release assets works as well (jars, POMs, bom-release.json).
# Env:     REPO             owner/name                                       (default $GITHUB_REPOSITORY)
#          SIGNER_WORKFLOW  owner/name/.github/workflows/release.yml         (default "$REPO/.github/workflows/release.yml")
#          SOURCE_REF       refs/tags/vX.Y.Z the statements must name        (default refs/tags/$BPM_RELEASE_TAG)
#          SOURCE_DIGEST    commit the statements must name (--source-digest); REQUIRED, 40 hex digits, no default.
#                           Unset or malformed: exit 2 before any statement is read (fail closed). In CI: ${{ github.sha }}
#          VERIFY_METADATA  true (default): SHA256SUMS, BUILD-INFO.json and bom-release.json need a provenance
#                           statement too; false for releases before they were attested (v1.0.0)
#          GH               the GitHub CLI to run                            (default gh; tests put a fake in its place)
#          VERIFY_RETRIES   tries per statement, the attestation API can lag  (default 5)
#          VERIFY_SLEEP     seconds between tries                             (default 10)
# Exit:    0 every statement verified; 1 a statement is missing or invalid; 2 bad usage.
# Dry run: tools/release/test-release-verify.sh runs this script against a fake CLI, on every pull request that
#          changes the release files. To try it on a published Release without a new tag, see docs/RELEASING.md.

set -euo pipefail

[[ $# -eq 1 && -d "$1" ]] || { echo "usage: $0 ARCHIVE_DIR" >&2; exit 2; }
ARCHIVE="$1"
REPO="${REPO:-${GITHUB_REPOSITORY:-}}"
[[ -n "$REPO" ]] || { echo "ERROR: REPO (owner/name) is not set." >&2; exit 2; }
SIGNER_WORKFLOW="${SIGNER_WORKFLOW:-$REPO/.github/workflows/release.yml}"
SOURCE_REF="${SOURCE_REF:-${BPM_RELEASE_TAG:+refs/tags/$BPM_RELEASE_TAG}}"
[[ -n "$SOURCE_REF" ]] || { echo "ERROR: SOURCE_REF (refs/tags/vX.Y.Z) is not set." >&2; exit 2; }
SOURCE_DIGEST="${SOURCE_DIGEST:-}"
[[ -n "$SOURCE_DIGEST" ]] || { echo "ERROR: SOURCE_DIGEST (the commit the statements must name) is not set; the gate does not run without it." >&2; exit 2; }
[[ "$SOURCE_DIGEST" =~ ^[0-9a-f]{40}$ ]] || { echo "ERROR: SOURCE_DIGEST is not a 40-digit lower-case commit hash: '$SOURCE_DIGEST'." >&2; exit 2; }
VERIFY_METADATA="${VERIFY_METADATA:-true}"
GH="${GH:-gh}"
RETRIES="${VERIFY_RETRIES:-5}"
SLEEP="${VERIFY_SLEEP:-10}"
SBOM_TYPE="https://cyclonedx.org/bom"
WORK="$(mktemp -d)"
trap 'status=$?; rm -rf "$WORK"; exit $status' EXIT

failures=0
fail() { echo "ERROR: $*"; failures=$((failures + 1)); }

# verify_json FILE OUT [PREDICATE_TYPE]: runs the CLI with retries; the JSON result goes to OUT.
verify() {
  local file="$1" out="$2" type="${3:-}" try=1
  local args=(attestation verify "$file" --repo "$REPO" --signer-workflow "$SIGNER_WORKFLOW"
              --source-ref "$SOURCE_REF" --format json)
  [[ -z "$type" ]] || args+=(--predicate-type "$type")
  args+=(--source-digest "$SOURCE_DIGEST")
  while true; do
    if "$GH" "${args[@]}" > "$out" 2> "$out.err"; then return 0; fi
    (( try < RETRIES )) || { sed 's/^/    | /' "$out.err"; return 1; }
    try=$((try + 1)); sleep "$SLEEP"
  done
}

sha() { shasum -a 256 "$1" | cut -d' ' -f1; }

# Read line by line: macOS ships bash 3.2, which has no mapfile.
artefacts=(); jars=()
while IFS= read -r line; do artefacts+=("$line"); done < <(find "$ARCHIVE" -type f \( -name '*.jar' -o -name '*.pom' \) | LC_ALL=C sort)
for line in ${artefacts[@]+"${artefacts[@]}"}; do case "$line" in *.jar) jars+=("$line") ;; esac; done
(( ${#jars[@]} > 0 )) || { echo "ERROR: no jar under $ARCHIVE; the gate has nothing to verify."; exit 1; }
BOM="$(find "$ARCHIVE" -maxdepth 1 -type f -name bom-release.json)"
[[ -n "$BOM" ]] || { echo "ERROR: no bom-release.json in $ARCHIVE; a release without an SBOM is refused."; exit 1; }
python3 - "$BOM" <<'PY' || { echo "ERROR: bom-release.json is not a CycloneDX document with components."; exit 1; }
import json, sys
bom = json.load(open(sys.argv[1], encoding="utf-8"))
assert bom.get("bomFormat") == "CycloneDX" and bom.get("specVersion") and bom.get("components"), "not CycloneDX"
PY
# SHA256SUMS and BUILD-INFO.json are the files adopters check the rest against; they get provenance too.
if [[ "$VERIFY_METADATA" == true ]]; then
  for name in SHA256SUMS BUILD-INFO.json bom-release.json; do
    [[ -f "$ARCHIVE/$name" ]] || { echo "ERROR: no $name in $ARCHIVE; the gate cannot verify it."; exit 1; }
    artefacts+=("$ARCHIVE/$name")
  done
fi
echo "Verifying ${#artefacts[@]} artefact(s) (${#jars[@]} jar(s)) of $REPO against $SIGNER_WORKFLOW at $SOURCE_REF, commit $SOURCE_DIGEST."

# 1. Build provenance for every jar and POM.
for f in "${artefacts[@]}"; do
  if verify "$f" "$WORK/prov.json"; then echo "provenance ok   ${f#"$ARCHIVE"/}"
  else fail "no valid build provenance for ${f#"$ARCHIVE"/}"; fi
done

# 2. The SBOM statement of every jar: signed by the same workflow and ref, names the jar's digest, and
#    carries the release bill of materials byte for byte (as parsed JSON).
want_subjects="$WORK/want-subjects.txt"
for f in "${jars[@]}"; do printf '%s  %s\n' "$(sha "$f")" "$(basename "$f")"; done | sort > "$want_subjects"
first=true
for f in "${jars[@]}"; do
  if ! verify "$f" "$WORK/sbom.json" "$SBOM_TYPE"; then fail "no valid SBOM attestation for ${f#"$ARCHIVE"/}"; continue; fi
  if ! python3 - "$WORK/sbom.json" "$f" "$BOM" "$WORK/got-subjects.txt" <<'PY'
import hashlib, json, sys
result, jar, bom, subjects_out = sys.argv[1:5]
statements = json.load(open(result, encoding="utf-8"))
digest = hashlib.sha256(open(jar, "rb").read()).hexdigest()
for item in statements:
    statement = item["verificationResult"]["statement"]
    subjects = {s["digest"]["sha256"]: s["name"] for s in statement.get("subject", [])}
    if digest not in subjects:
        continue
    if statement.get("predicateType") != "https://cyclonedx.org/bom":
        continue
    if statement.get("predicate") != json.load(open(bom, encoding="utf-8")):
        print("the SBOM in the statement differs from bom-release.json", file=sys.stderr)
        sys.exit(1)
    with open(subjects_out, "w", encoding="utf-8") as out:
        for sha, name in sorted(subjects.items(), key=lambda kv: kv[1]):
            out.write(f"{sha}  {name.rsplit('/', 1)[-1]}\n")
    sys.exit(0)
print("no CycloneDX statement names this jar's digest", file=sys.stderr)
sys.exit(1)
PY
  then fail "SBOM statement of ${f#"$ARCHIVE"/} does not match the file or bom-release.json"; continue; fi
  echo "sbom ok         ${f#"$ARCHIVE"/}"
  if [[ "$first" == true ]]; then
    first=false
    # The statement must cover every staged jar, no more and no fewer.
    if ! diff <(sort "$WORK/got-subjects.txt") "$want_subjects" > "$WORK/subjects.diff"; then
      sed 's/^/    | /' "$WORK/subjects.diff"
      fail "the SBOM statement subjects differ from the staged jars (above)"
    fi
  fi
done

if (( failures > 0 )); then
  echo "FAILED: $failures problem(s); the release is not published."
  exit 1
fi
echo "Verified: provenance for ${#artefacts[@]} artefact(s), SBOM attestation for ${#jars[@]} jar(s)."
