#!/usr/bin/env bash
# Apply .github/labels.json to the repository. Idempotent. Requires gh and jq.
set -euo pipefail
REPO="${1:-dhluong90/Banking-Accelerator-Program}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
jq -c '.[]' "$HERE/.github/labels.json" | while read -r row; do
  name=$(jq -r .name <<<"$row"); color=$(jq -r .color <<<"$row"); desc=$(jq -r .description <<<"$row")
  gh label create "$name" --color "$color" --description "$desc" --force -R "$REPO" >/dev/null && echo "ok  $name"
done
