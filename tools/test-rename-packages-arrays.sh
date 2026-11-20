#!/usr/bin/env bash
# Checks that tools/rename-packages.sh expands possibly-empty arrays in the form bash 3.2 accepts (#260).
# Bash 3.2, the macOS default, treats an empty array as unset under `set -u`.
#
# Usage:   tools/test-rename-packages-arrays.sh
# Cases:   1. static: no bare "${name[@]}" expansion of an array that can be empty
#          2. build_classpath with MAVEN_REPO_LOCAL unset: no -Dmaven.repo.local is passed
#          3. build_classpath with MAVEN_REPO_LOCAL set: -Dmaven.repo.local=<value> is passed
#          Cases 2 and 3 run under /bin/bash (3.2 on macOS) with a stub mvn on PATH.
# Exit:    0 when every case passes, 1 otherwise.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$ROOT/tools/rename-packages.sh"
WORK="$(mktemp -d)"
trap 'status=$?; rm -rf "$WORK"; exit $status' EXIT
failures=0
not_in_args() { ! grep -q -- "$1" "$WORK/args"; }
check() { # name, command...; the command's status is the result
  local name="$1"; shift
  if "$@"; then echo "ok:   $name"; else echo "FAIL: $name"; failures=$((failures + 1)); fi
}

# 1. Arrays that start empty and are filled conditionally must use the guarded form.
bare=$(grep -nE '(^|[^+])"\$\{(repo_args|HISTORY_EXCLUDES)\[@\]\}"' "$SCRIPT" || true)
check "no bare expansion of a possibly-empty array" test -z "$bare"
[ -z "$bare" ] || echo "$bare"

# Stub mvn records its arguments.
mkdir -p "$WORK/bin"
printf '#!/bin/sh\necho "$@" > "$STUB_ARGS"\n' > "$WORK/bin/mvn"
chmod +x "$WORK/bin/mvn"

# Only the function is loaded: the script body needs a git checkout and dispatches on its arguments.
sed -n '/^build_classpath() {/,/^}/p' "$SCRIPT" > "$WORK/fn.sh"
run_fn() { # extra env assignments are passed as arguments to env
  env -u MAVEN_REPO_LOCAL PATH="$WORK/bin:$PATH" STUB_ARGS="$WORK/args" "$@" /bin/bash -c \
    'set -euo pipefail; . "$1"; build_classpath /tmp/tree "$2"' _ "$WORK/fn.sh" "$WORK/cp.txt"
}

check "build_classpath without MAVEN_REPO_LOCAL" run_fn
check "no -Dmaven.repo.local when unset" not_in_args maven.repo.local

check "build_classpath with MAVEN_REPO_LOCAL" run_fn MAVEN_REPO_LOCAL=/tmp/m2
check "-Dmaven.repo.local passed when set" grep -q -- -Dmaven.repo.local=/tmp/m2 "$WORK/args"

[ "$failures" -eq 0 ] || { echo "$failures check(s) failed"; exit 1; }
echo "all checks passed"
