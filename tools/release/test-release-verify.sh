#!/usr/bin/env bash
# Dry run of the attestation gate of the release workflow (TR-SEC-06, #664): runs
# tools/release/verify-release-attestations.sh against a fake GitHub CLI and fixture archives.
# Nothing is signed, nothing leaves the machine, and no tag is involved.
#
# Usage:   tools/release/test-release-verify.sh
# Needs:   bash 3.2+, Python 3.9, shasum.
# Cases:   1 good archive passes   2 a POM without provenance   3 a jar without an SBOM statement
#          4 a jar missing from the statement's subjects   5 SBOM in the statement differs from bom-release.json
#          6 no bom-release.json   7 bom-release.json is not CycloneDX   8 statements of another tag
#          8b another commit  8c unattested SHA256SUMS  8d no BUILD-INFO.json
#          9 an attestation that appears on the second try passes   10 no jar at all
# Exit:    0 when every case behaves as expected, 1 otherwise.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
GATE="$ROOT/tools/release/verify-release-attestations.sh"
WORK="$(mktemp -d)"
trap 'status=$?; rm -rf "$WORK"; exit $status' EXIT

REPO="example/horus"
REF="refs/tags/v0.1.0"
DIGEST="0123456789abcdef0123456789abcdef01234567"

# The fake CLI: answers `attestation verify FILE ... --format json` from the files in $FAKE_STATE.
#   prov/<sha256>      exists when the file has a provenance statement (the content is ignored)
#   sbom/<sha256>      the CycloneDX statement the fake returns for that file (JSON)
#   ref                the one source ref the fake accepts
#   flaky              when present, the first call per file fails and the file is removed
cat > "$WORK/gh" <<'PY'
#!/usr/bin/env python3
import hashlib, json, os, sys
args = sys.argv[1:]
state = os.environ["FAKE_STATE"]
if args[:2] != ["attestation", "verify"]:
    sys.exit("fake gh: unexpected command")
path = args[2]
opt = lambda name: args[args.index(name) + 1] if name in args else None
digest = hashlib.sha256(open(path, "rb").read()).hexdigest()
if opt("--source-digest") != open(f"{state}/digest").read().strip():
    sys.exit("Error: no attestation matched the source digest")
if opt("--source-ref") != open(f"{state}/ref").read().strip() or opt("--signer-workflow") != open(f"{state}/signer").read().strip():
    sys.exit("Error: no attestation matched the policy")
flaky = f"{state}/flaky"
seen = f"{state}/seen-{digest}-{opt('--predicate-type') is not None}"
if os.path.exists(flaky) and not os.path.exists(seen):
    open(seen, "w").close()
    sys.exit("Error: HTTP 404: Not Found")
if opt("--predicate-type") == "https://cyclonedx.org/bom":
    target = f"{state}/sbom/{digest}"
    if not os.path.exists(target):
        sys.exit("Error: HTTP 404: Not Found")
    print(open(target).read())
else:
    if not os.path.exists(f"{state}/prov/{digest}"):
        sys.exit("Error: HTTP 404: Not Found")
    print("[]")
PY
chmod +x "$WORK/gh"

# fixture DIR: a release folder as the release build stages it, and the fake's state for it.
fixture() {
  local dir="$1" repo="$1/maven-repo/io/example/bpm"
  mkdir -p "$repo/core/0.1.0" "$repo/rest/0.1.0" "$dir.state/prov" "$dir.state/sbom"
  echo "core jar" > "$repo/core/0.1.0/core-0.1.0.jar"
  echo "<project>core</project>" > "$repo/core/0.1.0/core-0.1.0.pom"
  echo "rest jar" > "$repo/rest/0.1.0/rest-0.1.0.jar"
  echo "<project>rest</project>" > "$repo/rest/0.1.0/rest-0.1.0.pom"
  echo "<metadata/>" > "$repo/core/maven-metadata.xml"
  echo '{"bomFormat":"CycloneDX","specVersion":"1.6","components":[{"name":"core"},{"name":"rest"}]}' > "$dir/bom-release.json"
  echo "sums" > "$dir/SHA256SUMS"; echo '{"tag":"v0.1.0"}' > "$dir/BUILD-INFO.json"
  echo "$REF" > "$dir.state/ref"
  echo "$DIGEST" > "$dir.state/digest"
  echo "$REPO/.github/workflows/release.yml" > "$dir.state/signer"
  python3 - "$dir" "$dir.state" <<'PY'
import hashlib, json, os, sys
dir_, state = sys.argv[1:3]
jars, files = [], []
for root, _, names in os.walk(dir_):
    for n in names:
        p = os.path.join(root, n)
        if n.endswith((".jar", ".pom")) or n in ("SHA256SUMS", "BUILD-INFO.json", "bom-release.json"):
            files.append(p)
        if n.endswith(".jar"):
            jars.append(p)
h = lambda p: hashlib.sha256(open(p, "rb").read()).hexdigest()
for p in files:
    open(f"{state}/prov/{h(p)}", "w").close()
bom = json.load(open(f"{dir_}/bom-release.json"))
statement = [{"verificationResult": {"statement": {
    "predicateType": "https://cyclonedx.org/bom",
    "subject": [{"name": os.path.basename(j), "digest": {"sha256": h(j)}} for j in jars],
    "predicate": bom}}}]
for j in jars:
    json.dump(statement, open(f"{state}/sbom/{h(j)}", "w"))
PY
}

failures=0
# expect NAME DIR EXIT [TEXT...]   (env of the caller: GATE_ENV as extra VAR=value words)
expect() {
  local name="$1" dir="$2" want="$3"; shift 3
  local out="$WORK/out.txt" got=0 ok=true text
  env FAKE_STATE="$dir.state" GH="$WORK/gh" REPO="$REPO" SOURCE_REF="$REF" SOURCE_DIGEST="${DIGEST_USED:-$DIGEST}" VERIFY_RETRIES="${RETRIES:-1}" VERIFY_SLEEP=0 \
    bash "$GATE" "$dir" > "$out" 2>&1 || got=$?
  [[ "$got" == "$want" ]] || ok=false
  for text in "$@"; do grep -qF -- "$text" "$out" || { ok=false; echo "  missing: $text"; }; done
  if [[ "$ok" == true ]]; then
    echo "PASS  $name (exit $got)"
    grep -E '^(ERROR|FAILED|Verified)' "$out" | sed 's/^/        /' || true
  else
    echo "FAIL  $name: exit $got, expected $want; output:"
    sed 's/^/  | /' "$out"
    failures=$((failures + 1))
  fi
}
digest_of() { shasum -a 256 "$1" | cut -d' ' -f1; }

fixture "$WORK/good"
expect "1 a good archive passes" "$WORK/good" 0 "Verified: provenance for 7 artefact(s), SBOM attestation for 2 jar(s)."

fixture "$WORK/pom"
rm "$WORK/pom.state/prov/$(digest_of "$WORK/pom/maven-repo/io/example/bpm/rest/0.1.0/rest-0.1.0.pom")"
expect "2 a POM without provenance is refused" "$WORK/pom" 1 "no valid build provenance for maven-repo/io/example/bpm/rest/0.1.0/rest-0.1.0.pom" "the release is not published"

fixture "$WORK/nosbom"
rm "$WORK/nosbom.state/sbom/$(digest_of "$WORK/nosbom/maven-repo/io/example/bpm/core/0.1.0/core-0.1.0.jar")"
expect "3 a jar without an SBOM statement is refused" "$WORK/nosbom" 1 "no valid SBOM attestation for maven-repo/io/example/bpm/core/0.1.0/core-0.1.0.jar"

fixture "$WORK/subjects"
python3 - "$WORK/subjects.state/sbom" <<'PY'
import glob, json, sys
for f in glob.glob(sys.argv[1] + "/*"):
    d = json.load(open(f))
    d[0]["verificationResult"]["statement"]["subject"].pop()
    json.dump(d, open(f, "w"))
PY
expect "4 a jar missing from the statement subjects is refused" "$WORK/subjects" 1 "no CycloneDX statement names this jar's digest" "does not match the file or bom-release.json"

fixture "$WORK/other-bom"
echo '{"bomFormat":"CycloneDX","specVersion":"1.6","components":[{"name":"other"}]}' > "$WORK/other-bom/bom-release.json"
expect "5 an SBOM that differs from bom-release.json is refused" "$WORK/other-bom" 1 "differs from bom-release.json"

fixture "$WORK/nobom"
rm "$WORK/nobom/bom-release.json"
expect "6 no bom-release.json is refused" "$WORK/nobom" 1 "a release without an SBOM is refused"

fixture "$WORK/notcdx"
echo '{"hello":"world"}' > "$WORK/notcdx/bom-release.json"
expect "7 a bom-release.json that is not CycloneDX is refused" "$WORK/notcdx" 1 "not a CycloneDX document"

fixture "$WORK/ref"
echo "refs/tags/v9.9.9" > "$WORK/ref.state/ref"
expect "8 statements of another tag are refused" "$WORK/ref" 1 "no valid build provenance for" "no valid SBOM attestation for"

fixture "$WORK/digest"
DIGEST_USED="ffffffffffffffffffffffffffffffffffffffff" expect "8b statements of another commit are refused" "$WORK/digest" 1 "no valid build provenance for"

fixture "$WORK/sums"
rm "$WORK/sums.state/prov/$(digest_of "$WORK/sums/SHA256SUMS")"
expect "8c an unattested SHA256SUMS is refused" "$WORK/sums" 1 "no valid build provenance for SHA256SUMS"

fixture "$WORK/nobuildinfo"
rm "$WORK/nobuildinfo/BUILD-INFO.json"
expect "8d a missing BUILD-INFO.json is refused" "$WORK/nobuildinfo" 1 "no BUILD-INFO.json"

fixture "$WORK/flaky"
touch "$WORK/flaky.state/flaky"
RETRIES=1 expect "9a an attestation not yet visible fails with one try" "$WORK/flaky" 1 "no valid build provenance for"
fixture "$WORK/flaky2"
touch "$WORK/flaky2.state/flaky"
RETRIES=2 expect "9b the same, visible on the second try, passes" "$WORK/flaky2" 0 "Verified:"

mkdir -p "$WORK/empty" "$WORK/empty.state"
echo '{"bomFormat":"CycloneDX","specVersion":"1.6","components":[{"name":"x"}]}' > "$WORK/empty/bom-release.json"; echo "$DIGEST" > "$WORK/empty.state/digest"
expect "10 an archive with no jar is refused" "$WORK/empty" 1 "no jar under"

if (( failures > 0 )); then echo "$failures case(s) failed."; exit 1; fi
echo "All cases behave as expected."
