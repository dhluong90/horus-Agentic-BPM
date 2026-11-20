#!/usr/bin/env bash
# Tests of the archive check of the github-release job (.github/workflows/release.yml, TR-SEC-06).
#
# Usage:   tools/release/test-release-upload.sh
# Needs:   bash, Python 3.9, shasum.
# How:     the text between "# BEGIN release-archive-check" and "# END release-archive-check" in the
#          workflow is the check that runs before any draft Release exists. This script runs that exact
#          text against fixture archives, with SHA256SUMS written by release-sbom.py manifest as in the
#          release build:
#            1. a good archive                         passes and chooses jars, POMs, bom-release.json,
#                                                      SHA256SUMS and BUILD-INFO.json, nothing else
#            2. a file changed after SHA256SUMS        refused at the checksum step
#            3. a file SHA256SUMS does not list        refused
#            4. "trial": true                          refused
#            5. a commit other than the run's          refused
#            6. a dirty working tree                   refused
#            7. two assets with one file name          refused
#          Then the check command the release notes give adopters runs on a flat folder of downloads.
# Exit:    0 when every case behaves as expected, 1 otherwise.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORKFLOW="$ROOT/.github/workflows/release.yml"
WORK="$(mktemp -d)"
# Keep the exit status: bash 3.2 would otherwise exit with the status of the clean-up.
trap 'status=$?; rm -rf "$WORK"; exit $status' EXIT
TAG="v0.1.0"
COMMIT="0123456789abcdef0123456789abcdef01234567"

# The check, exactly as the workflow runs it (YAML block indentation removed).
python3 - "$WORKFLOW" "$WORK/check.sh" <<'PY'
import sys, textwrap
lines = open(sys.argv[1], encoding="utf-8").read().splitlines()
start = next(i for i, line in enumerate(lines) if line.strip() == "# BEGIN release-archive-check")
end = next(i for i, line in enumerate(lines) if line.strip() == "# END release-archive-check")
open(sys.argv[2], "w", encoding="utf-8").write(textwrap.dedent("\n".join(lines[start:end + 1])) + "\n")
PY

# fixture DIR [TRIAL] [TREE] [COMMIT]: a release folder as the release build stages it.
fixture() {
  local dir="$1" trial="${2:-false}" tree="${3:-clean}" commit="${4:-$COMMIT}"
  local repo="$dir/maven-repo/io/github/dhluong90/bpm"
  mkdir -p "$repo/rule-engine-core/0.1.0" "$repo/bpm-bom/0.1.0"
  echo "core jar" > "$repo/rule-engine-core/0.1.0/rule-engine-core-0.1.0.jar"
  echo "<project/>" > "$repo/rule-engine-core/0.1.0/rule-engine-core-0.1.0.pom"
  echo "da39a3ee" > "$repo/rule-engine-core/0.1.0/rule-engine-core-0.1.0.jar.sha1"
  echo "<project/>" > "$repo/bpm-bom/0.1.0/bpm-bom-0.1.0.pom"
  echo "<metadata/>" > "$repo/rule-engine-core/maven-metadata.xml"
  echo '{"bomFormat":"CycloneDX"}' > "$dir/bom-release.json"
  echo "core-ignored" > "$dir/cockpit-bundle.txt"
  printf '{"commit": "%s", "workingTree": "%s", "tag": "%s", "trial": %s}\n' \
    "$commit" "$tree" "$TAG" "$trial" > "$dir/BUILD-INFO.json"
  python3 "$ROOT/tools/release/release-sbom.py" manifest "$dir" "$dir/SHA256SUMS" --exclude SHA256SUMS > /dev/null
}

failures=0
# expect NAME DIR EXIT [TEXT...]
expect() {
  local name="$1" dir="$2" want="$3"; shift 3
  local out="$WORK/out.txt" got=0 ok=true text
  mkdir -p "$WORK/runner"
  (ARCHIVE="$dir" ASSETS="$WORK/assets.txt" TAG="$TAG" COMMIT="$COMMIT" RUNNER_TEMP="$WORK/runner" \
    bash "$WORK/check.sh") > "$out" 2>&1 || got=$?
  [[ "$got" == "$want" ]] || ok=false
  for text in "$@"; do grep -qF -- "$text" "$out" || { ok=false; echo "  missing: $text"; }; done
  if [[ "$ok" == true ]]; then
    echo "PASS  $name (exit $got)"
    grep -E '^ERROR|match|Assets|BUILD-INFO' "$out" | sed 's/^/        /' || true
  else
    echo "FAIL  $name: exit $got, expected $want; output:"
    sed 's/^/  | /' "$out"
    failures=$((failures + 1))
  fi
}

fixture "$WORK/good"
expect "a good archive passes" "$WORK/good" 0 "SHA256SUMS: 8 file(s) match." "Assets: 6 file(s)."
chosen="$(sed 's#.*/##' "$WORK/assets.txt" | LC_ALL=C sort | tr '\n' ' ')"
expected_assets="BUILD-INFO.json SHA256SUMS bom-release.json bpm-bom-0.1.0.pom rule-engine-core-0.1.0.jar "
expected_assets+="rule-engine-core-0.1.0.pom "
if [[ "$chosen" == "$expected_assets" ]]; then
  echo "PASS  the assets are the jars, the POMs, bom-release.json, SHA256SUMS and BUILD-INFO.json"
else
  echo "FAIL  the assets are: $chosen"; failures=$((failures + 1))
fi

fixture "$WORK/tampered"
echo "changed after SHA256SUMS" >> "$WORK/tampered/maven-repo/io/github/dhluong90/bpm/rule-engine-core/0.1.0/rule-engine-core-0.1.0.jar"
expect "a file changed after SHA256SUMS is refused" "$WORK/tampered" 1 \
  "rule-engine-core-0.1.0.jar: FAILED" "does not match its SHA256SUMS"

fixture "$WORK/extra"
echo "added later" > "$WORK/extra/extra.jar"
expect "a file SHA256SUMS does not list is refused" "$WORK/extra" 1 "SHA256SUMS does not list"

fixture "$WORK/trial" true
expect "a trial build is refused" "$WORK/trial" 1 'a trial build is never released' "no Release is created"

fixture "$WORK/commit" false clean "ffffffffffffffffffffffffffffffffffffffff"
expect "a build of another commit is refused" "$WORK/commit" 1 "names commit 'ffffffff"

fixture "$WORK/dirty" false dirty
expect "a dirty working tree is refused" "$WORK/dirty" 1 '"workingTree": "dirty"'

fixture "$WORK/duplicate"
mkdir -p "$WORK/duplicate/maven-repo/io/github/dhluong90/bpm/other/0.1.0"
cp "$WORK/duplicate/maven-repo/io/github/dhluong90/bpm/rule-engine-core/0.1.0/rule-engine-core-0.1.0.jar" \
  "$WORK/duplicate/maven-repo/io/github/dhluong90/bpm/other/0.1.0/"
python3 "$ROOT/tools/release/release-sbom.py" manifest "$WORK/duplicate" "$WORK/duplicate/SHA256SUMS" --exclude SHA256SUMS > /dev/null
expect "two assets with one file name are refused" "$WORK/duplicate" 1 "share a file name"

# The adopters' check from the release notes, on a flat folder of downloaded assets.
mkdir -p "$WORK/downloads"
while IFS= read -r file; do cp "$file" "$WORK/downloads/"; done < <(
  ARCHIVE="$WORK/good" ASSETS="$WORK/assets.txt" TAG="$TAG" COMMIT="$COMMIT" RUNNER_TEMP="$WORK/runner" \
    bash "$WORK/check.sh" > /dev/null && cat "$WORK/assets.txt")
notes_check="$(cd "$WORK/downloads" && while read -r hash path; do name="${path##*/}"; if [ -f "$name" ]; then printf '%s  %s\n' "$hash" "$name"; fi; done < SHA256SUMS | shasum -a 256 -c - 2>&1 || true)"
if [[ "$(grep -c ': OK$' <<< "$notes_check")" == 5 && "$notes_check" != *FAILED* ]]; then
  echo "PASS  the release notes' check passes on the downloads (5 files OK)"
else
  echo "FAIL  the release notes' check: $notes_check"; failures=$((failures + 1))
fi
echo "changed" >> "$WORK/downloads/bpm-bom-0.1.0.pom"
notes_check="$(cd "$WORK/downloads" && while read -r hash path; do name="${path##*/}"; if [ -f "$name" ]; then printf '%s  %s\n' "$hash" "$name"; fi; done < SHA256SUMS | shasum -a 256 -c - 2>&1 || true)"
if [[ "$notes_check" == *"bpm-bom-0.1.0.pom: FAILED"* ]]; then
  echo "PASS  the release notes' check catches a changed download"
else
  echo "FAIL  the release notes' check missed a changed download: $notes_check"; failures=$((failures + 1))
fi

# The exit status of the check under pipefail, as the workflow's publish step runs it, when the last
# SHA256SUMS line names a file that is not an asset (a loop that ends on a failed `[ -f ]` would fail).
cp "$WORK/good/BUILD-INFO.json" "$WORK/downloads/BUILD-INFO.json"
mkdir -p "$WORK/tail"
cp "$WORK/downloads"/* "$WORK/tail/"
rm "$WORK/tail/bpm-bom-0.1.0.pom"
cp "$WORK/good/maven-repo/io/github/dhluong90/bpm/bpm-bom/0.1.0/bpm-bom-0.1.0.pom" "$WORK/tail/"
{ cat "$WORK/good/SHA256SUMS"; echo "0000000000000000000000000000000000000000000000000000000000000000  maven-repo/not-an-asset.txt"; } > "$WORK/tail/SHA256SUMS"
fixed='while read -r hash path; do name="${path##*/}"; if [ -f "$name" ]; then printf "%s  %s\n" "$hash" "$name"; fi; done < SHA256SUMS | shasum -a 256 -c - > /dev/null'
old='while read -r hash path; do name="${path##*/}"; [ -f "$name" ] && printf "%s  %s\n" "$hash" "$name"; done < SHA256SUMS | shasum -a 256 -c - > /dev/null'
fixed_rc=0; (cd "$WORK/tail" && set -o pipefail && bash -c "set -o pipefail; $fixed") || fixed_rc=$?
old_rc=0; (cd "$WORK/tail" && bash -c "set -o pipefail; $old") || old_rc=$?
if [[ "$fixed_rc" == 0 && "$old_rc" != 0 ]]; then
  echo "PASS  a SHA256SUMS ending with a non-asset: the check exits 0 (the earlier form exited $old_rc)"
else
  echo "FAIL  a SHA256SUMS ending with a non-asset: fixed form exit $fixed_rc, earlier form exit $old_rc"; failures=$((failures + 1))
fi

if (( failures > 0 )); then
  echo "Release upload tests: $failures case(s) failed."
  exit 1
fi
echo "Release upload tests: every case behaved as expected."
