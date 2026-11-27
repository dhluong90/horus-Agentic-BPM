#!/usr/bin/env bash
# Prints "true" when a change touches anything but documentation, otherwise "false". Documentation is
# site/**, wiki/**, docs/** and root-level *.md; markdown under a module directory is code (it can be a
# test resource). An empty list prints "true": no list, no skip.
# Usage: ci-code-changed.sh            paths on stdin, one per line
#        ci-code-changed.sh BASE HEAD  paths from git diff --no-renames, so a rename counts as a delete of
#                                      the old path and an add of the new one (code renamed to docs is code)
set -euo pipefail

if [ "$#" -eq 2 ]; then
  git diff --no-renames --name-only "$1" "$2" | "$0"
  exit 0
fi

code=false
seen=false
while IFS= read -r path; do
  [ -n "$path" ] || continue
  seen=true
  case "$path" in
    site/*|wiki/*|docs/*) ;;
    */*) code=true ;;
    *.md) ;;
    *) code=true ;;
  esac
done
if [ "$seen" = false ]; then code=true; fi
echo "$code"
