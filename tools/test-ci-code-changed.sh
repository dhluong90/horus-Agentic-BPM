#!/usr/bin/env bash
# Tests of tools/ci-code-changed.sh, the docs-only filter of the CI Java jobs.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
fail=0
check() {
  local want="$1"; shift
  local got
  got="$(printf '%s\n' "$@" | "$here/ci-code-changed.sh")"
  if [ "$got" = "$want" ]; then echo "ok   $want  <- $*"; else echo "FAIL want $want got $got  <- $*"; fail=1; fi
}
check false  site/index.html wiki/Home.md docs/adr/0001.md README.md CHANGELOG.md
check true   site/index.html rule-engine/rule-engine-core/src/main/java/A.java
check true   .github/workflows/ci.yml
check true   pom.xml
check true   cockpit/web/src/app.tsx README.md
check true   tools/spec-coverage.py
if [ "$(printf '' | "$here/ci-code-changed.sh")" = true ]; then echo "ok   true  <- (empty list)"; else echo "FAIL empty list"; fail=1; fi
check true   rule-engine/README.md
check true   process-engine/process-engine-bpmn/src/test/resources/xsd/bpmn20/README.md
check true   cockpit/web/NOTES.md docs/a.md

# A rename of code to a documentation path is a change of code (old path deleted).
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
git -C "$tmp" init -q
mkdir -p "$tmp/src" "$tmp/docs"; echo x > "$tmp/src/A.java"
git -C "$tmp" add -A
git -C "$tmp" -c user.name=t -c user.email=t@t commit -q -m one
git -C "$tmp" mv src/A.java docs/A.md
git -C "$tmp" -c user.name=t -c user.email=t@t commit -q -m two
got="$(cd "$tmp" && "$here/ci-code-changed.sh" HEAD^1 HEAD)"
if [ "$got" = true ]; then echo "ok   true  <- rename src/A.java to docs/A.md"; else echo "FAIL rename got $got"; fail=1; fi
exit "$fail"
