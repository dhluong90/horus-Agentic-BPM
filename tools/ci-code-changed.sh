#!/usr/bin/env bash
# Reads changed file paths on stdin, one per line. Prints "true" when at least one path is not documentation
# (site/**, wiki/**, docs/**, any *.md), otherwise "false". An empty list prints "true": no list, no skip.
set -euo pipefail

code=false
seen=false
while IFS= read -r path; do
  [ -n "$path" ] || continue
  seen=true
  case "$path" in
    site/*|wiki/*|docs/*|*.md) ;;
    *) code=true ;;
  esac
done
if [ "$seen" = false ]; then code=true; fi
echo "$code"
