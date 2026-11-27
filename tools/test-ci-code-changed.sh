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
check false  site/index.html wiki/Home.md docs/adr/0001.md README.md rule-engine/README.md
check true   site/index.html rule-engine/rule-engine-core/src/main/java/A.java
check true   .github/workflows/ci.yml
check true   pom.xml
check true   cockpit/web/src/app.tsx README.md
check true   tools/spec-coverage.py
if [ "$(printf '' | "$here/ci-code-changed.sh")" = true ]; then echo "ok   true  <- (empty list)"; else echo "FAIL empty list"; fail=1; fi
exit "$fail"
