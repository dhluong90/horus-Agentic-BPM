#!/bin/sh
# Runs the visual regression spec (#587) where its images were drawn: Linux, in the Playwright image CI uses.
#   npm run e2e:visual                  compare the cockpit screens with e2e/visual-baselines
#   npm run e2e:visual:update           redraw the images after a reviewed visual change
# On Linux (CI, or a Linux machine with the browsers installed) it runs directly. On any other system it starts the same
# image, pinned in .github/workflows/ci.yml, through Docker, with Linux node_modules kept in a Docker volume so the ones on this
# machine are not touched.
set -eu
here=$(cd "$(dirname "$0")/.." && pwd)
root=$(cd "$here/../.." && pwd)
if [ "$(uname -s)" = Linux ]; then
  COCKPIT_VISUAL=1 exec npx playwright test "$@"
fi
image=$(sed -nE 's#^ +image: (mcr\.microsoft\.com/playwright:[^ ]+)$#\1#p' "$root/.github/workflows/ci.yml" | head -1)
[ -n "$image" ] || { echo "No Playwright image found in .github/workflows/ci.yml" >&2; exit 1; }
exec docker run --rm --ipc=host \
  -v "$root":/work \
  -v horus-cockpit-linux-modules:/work/cockpit/web/node_modules \
  -w /work/cockpit/web \
  -e COCKPIT_VISUAL=1 -e CI="${CI:-}" \
  "$image" sh -c 'npm ci --silent && npx playwright test "$@"' sh "$@"
