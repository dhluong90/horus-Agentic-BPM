#!/usr/bin/env bash
# Render wiki/diagrams/*.mmd to wiki/images/*.svg with the shared theme. Requires Node 18+ (uses npx @mermaid-js/mermaid-cli).
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
MMDC="${MMDC:-npx -y @mermaid-js/mermaid-cli}"
for f in "$HERE"/wiki/diagrams/*.mmd; do
  name=$(basename "$f" .mmd)
  $MMDC -i "$f" -o "$HERE/wiki/images/$name.svg" -c "$HERE/wiki/diagrams/theme.json" -b transparent -q
  echo "rendered $name.svg"
done
