#!/bin/sh
# The one way to build the cockpit bundle that ships (TR-SEC-06, TR-LIC-01, TR-UI-06). Used by the
# release build (tools/release/release-build.sh) and by the container image (deploy/docker/Dockerfile),
# so both package the same bytes from the same checks.
#
# Usage:   tools/release/build-cockpit.sh OUT_DIR
#          OUT_DIR must not be inside cockpit/web (dist/ is deleted and rebuilt there).
# Needs:   Node.js at exactly the version in tools/release/node-version, with npm, and network access to
#          the npm registry. POSIX sh only (runs in node:22-alpine). BPM_RELEASE_TRIAL=true accepts
#          another Node version for a local trial; it is refused when CI or GITHUB_ACTIONS is true.
# Output:  cockpit/web/dist/            the bundle (deleted first, then built from these sources)
#          OUT_DIR/cockpit-bundle.txt   SHA-256 of every bundle file, written right after the build
#          OUT_DIR/cockpit-modules.json the npm packages and bundler runtime modules in the bundle, and the
#                                       Node and npm versions and trial flag of the build
# Exit:    0 when every step passed; non-zero on the first failure.
#
# Steps:
#   1. Both lockfiles checked by lockfile-check.mjs (Node built-ins only), before any npm package is
#      installed: every entry from https://registry.npmjs.org/ with a sha512 integrity.
#   2. Release tools (tools/release/npm) from their lockfile with npm ci --ignore-scripts, then
#      npm audit signatures on that tree before any of its code runs. The same for the cockpit.
#      lockfile-lint on both lockfiles as a second opinion. npm is offline from here on.
#   3. Licence check of the production packages, tsc -b, and the cockpit's own vite build through
#      cockpit-build.mjs, which records the bundle's packages and hashes every file.

set -eu

if [ "$#" -ne 1 ]; then
  echo "Usage: tools/release/build-cockpit.sh OUT_DIR" >&2
  exit 2
fi
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WEB="$ROOT/cockpit/web"
TOOLS="$ROOT/tools/release/npm"
mkdir -p "$1"
OUT="$(cd "$1" && pwd -P)"
WEB_REAL="$(cd "$WEB" && pwd -P)"
case "$OUT/" in
  "$WEB_REAL"/*)
    echo "ERROR: OUT_DIR $OUT is inside cockpit/web; the record would be deleted or packaged with the bundle." >&2
    exit 1
    ;;
esac
NODE_PINNED="$(tr -d '[:space:]' < "$ROOT/tools/release/node-version")"

trial=false
if [ "${BPM_RELEASE_TRIAL:-}" = "true" ]; then
  if [ "${CI:-}" = "true" ] || [ "${GITHUB_ACTIONS:-}" = "true" ]; then
    echo "ERROR: BPM_RELEASE_TRIAL=true is for local trials only and is refused in CI (CI or GITHUB_ACTIONS is true)." >&2
    exit 1
  fi
  trial=true
fi
node_actual="$(node --version)"
if [ "$node_actual" != "v$NODE_PINNED" ]; then
  if [ "$trial" != true ]; then
    echo "ERROR: Node $node_actual found; the cockpit bundle is built with Node v$NODE_PINNED (tools/release/node-version)." >&2
    exit 1
  fi
  echo "WARNING: Node $node_actual instead of v$NODE_PINNED (trial; recorded in cockpit-modules.json)."
fi

echo "-- cockpit 1/3: lockfile hosts and integrity, before anything is installed"
node "$ROOT/tools/release/lockfile-check.mjs" "$TOOLS/package-lock.json" "$WEB/package-lock.json"

echo "-- cockpit 2/3: dependencies from the lockfiles, no install scripts, registry signatures (Node $node_actual, npm $(npm --version))"
(cd "$TOOLS" && npm ci --ignore-scripts --no-audit --no-fund && npm audit signatures)
(cd "$WEB" && npm ci --ignore-scripts --no-audit --no-fund && npm audit signatures)
for lockfile in "$TOOLS/package-lock.json" "$WEB/package-lock.json"; do
  (cd "$TOOLS" && npm exec --offline -- lockfile-lint --type npm --allowed-hosts npm --validate-https \
    --validate-integrity --validate-package-names --empty-hostname false --path "$lockfile")
done
# Nothing more is fetched from the npm registry.
npm_config_offline=true
export npm_config_offline

echo "-- cockpit 3/3: licences, tsc -b, vite build with the bundle record"
rm -rf "$WEB/dist"
cd "$WEB"
npm run licences
npm exec -- tsc -b
BPM_BUILD_NPM_VERSION="$(npm --version)" BPM_BUILD_TRIAL="$trial" \
  node "$ROOT/tools/release/cockpit-build.mjs" "$OUT/cockpit-modules.json" "$OUT/cockpit-bundle.txt"
