#!/usr/bin/env bash
# Creates the password files that docker-compose.yml mounts as secrets (docs/DEPLOYING.md).
# Run once from the repository root, before the first "docker compose up":
#
#   deploy/create-secrets.sh
#
# Writes random values to secrets/db_admin_password, secrets/db_password, secrets/db_owner_password, secrets/demo_password and
# secrets/grafana_admin_password (read only by the optional observability profile).
# git and the image build context ignore secrets/. An existing file is kept, never overwritten: PostgreSQL
# keeps the database passwords from its first start, so a new value would lock the host out.
# The values are never printed. secrets/ is readable by you only (0700); the files inside are 0644 so the
# non-root users of the containers can read their bind mount.
#
# Exit 0 when all five files exist afterwards, 1 otherwise.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIR="${HORUS_SECRETS_DIR:-$ROOT/secrets}"

command -v openssl >/dev/null 2>&1 || { echo "openssl not found; install it or write the files by hand." >&2; exit 1; }
mkdir -p "$DIR"
chmod 700 "$DIR"

for name in db_admin_password db_password db_owner_password demo_password grafana_admin_password; do
  file="$DIR/$name"
  if [[ -s "$file" ]]; then
    echo "kept     $file"
    continue
  fi
  case "$name" in
    demo_password) value="$(openssl rand -hex 12)" ;;
    *)             value="$(openssl rand -hex 16)" ;;
  esac
  # printf is a builtin: the value never appears on a command line. No trailing newline.
  (umask 022; printf '%s' "$value" > "$file")
  chmod 644 "$file"
  echo "created  $file"
done
