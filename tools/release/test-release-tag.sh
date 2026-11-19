#!/usr/bin/env bash
# Dry-run tests of the release tag guard, tools/release/check-release-tag.sh (PD-087). Nothing is built.
#
# Usage:   tools/release/test-release-tag.sh
# Needs:   what check-release-tag.sh needs (Maven 3.9, Python 3.9, git), plus tar.
# How:     a throwaway copy of the tracked files is set to the release version (the root version without
#          -SNAPSHOT) and the release group id, committed in a scratch git repository, and that commit is
#          made origin/main. The guard then runs against it once per case:
#            1. vX.Y.Z on main                         passes
#            2. a per-module tag <module>/vX.Y.Z       refused, naming the vX.Y.Z form
#            3. one module at another version          refused, naming the module and its version
#            4. a commit off main                      refused
#            5. a per-module tag on a commit off main  both problems named in one run
#            6. a POM Maven cannot read                refused, and a per-module tag is still named
#          The checkout itself is never changed.
# Exit:    0 when every case behaves as expected, 1 otherwise.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RELEASE_GROUP_ID="io.github.dhluong90.bpm"
WORK="$(mktemp -d)"
# Keep the exit status: bash 3.2 would otherwise exit with the status of the clean-up.
trap 'status=$?; rm -rf "$WORK"; exit $status' EXIT
repo="$WORK/repo"
mkdir -p "$repo"

# The tracked files as they are in the working tree, so the test covers uncommitted changes too.
(cd "$ROOT" && git ls-files -z | tar --null -T - -cf -) | tar -xf - -C "$repo"

# rewrite-poms.py OLD_GROUP NEW_GROUP OLD_VERSION NEW_VERSION POM...: the release coordinates.
cat > "$WORK/rewrite-poms.py" <<'PY'
import sys
old_group, new_group, old_version, new_version, *paths = sys.argv[1:]
for path in paths:
    text = open(path, encoding="utf-8").read()
    text = text.replace(f"<groupId>{old_group}</groupId>", f"<groupId>{new_group}</groupId>")
    text = text.replace(f"<version>{old_version}</version>", f"<version>{new_version}</version>")
    open(path, "w", encoding="utf-8").write(text)
PY
cat > "$WORK/coordinates.py" <<'PY'
import sys, xml.etree.ElementTree as ET
ns = "{http://maven.apache.org/POM/4.0.0}"
root = ET.parse(sys.argv[1]).getroot()
print(root.findtext(f"{ns}groupId").strip(), root.findtext(f"{ns}version").strip())
PY
read -r group version < <(python3 "$WORK/coordinates.py" "$repo/pom.xml")
release="${version%-SNAPSHOT}"
echo "Scratch copy: $group:$version becomes $RELEASE_GROUP_ID:$release in every pom.xml."
poms=()
while IFS= read -r -d '' pom; do poms+=("$pom"); done < <(find "$repo" -name pom.xml -not -path '*/node_modules/*' -print0)
python3 "$WORK/rewrite-poms.py" "$group" "$RELEASE_GROUP_ID" "$version" "$release" "${poms[@]}"

git_() { git -C "$repo" -c user.name=release-test -c user.email=release-test@example.invalid "$@"; }
git_ init -q -b main
git_ add -A
git_ commit -q -m "release candidate $release"
git_ update-ref refs/remotes/origin/main HEAD

failures=0
# expect NAME TAG EXIT [TEXT...]: the guard's exit status and every TEXT in its output.
expect() {
  local name="$1" tag="$2" want="$3"; shift 3
  local out="$WORK/out.txt" got=0 ok=true text
  (cd "$repo" && tools/release/check-release-tag.sh "$tag") > "$out" 2>&1 || got=$?
  [[ "$got" == "$want" ]] || ok=false
  for text in "$@"; do grep -qF -- "$text" "$out" || { ok=false; echo "  missing: $text"; }; done
  if [[ "$ok" == true ]]; then
    echo "PASS  $name (exit $got)"
    grep -E '^ERROR|passed|is on main' "$out" | sed 's/^/        /' || true
  else
    echo "FAIL  $name: exit $got, expected $want; output:"
    sed 's/^/  | /' "$out"
    failures=$((failures + 1))
  fi
}

expect "v$release on main passes" "v$release" 0 \
  "Tag check passed: v$release" "is on main." "Release tag guard passed: v$release."

expect "per-module tag rule-engine/v$release is refused" "rule-engine/v$release" 1 \
  "tag rule-engine/v$release is a per-module tag" "push v$release"

# One module at another version, committed on main, so only the version check fails.
sed -i.bak "s#<version>$release</version>#<version>9.9.9</version>#" "$repo/bpm-bom/pom.xml"
rm "$repo/bpm-bom/pom.xml.bak"
git_ commit -q -am "bpm-bom at another version"
git_ update-ref refs/remotes/origin/main HEAD
expect "a module at another version is refused" "v$release" 1 \
  "module bpm-bom is at version 9.9.9, but the release is $release"
git_ reset -q --hard HEAD~1
git_ update-ref refs/remotes/origin/main HEAD

# A commit that main does not contain.
git_ checkout -q -b side
git_ commit -q --allow-empty -m "not on main"
expect "a commit off main is refused" "v$release" 1 "which is not on main"

expect "every problem is named in one run" "rule-engine/v$release" 1 \
  "is a per-module tag" "which is not on main"

# A POM Maven cannot read: the guard retries, then still names the wrong tag form.
git_ checkout -q main
printf '<project>\n' > "$repo/bpm-bom/pom.xml"
git_ commit -q -am "unreadable POM"
git_ update-ref refs/remotes/origin/main HEAD
expect "an unreadable POM is refused and the tag form is still checked" "rule-engine/v$release" 1 \
  "so no module version or group id was checked" "is a per-module tag"

if (( failures > 0 )); then
  echo "Release tag guard tests: $failures case(s) failed."
  exit 1
fi
echo "Release tag guard tests: all 6 cases behaved as expected."
