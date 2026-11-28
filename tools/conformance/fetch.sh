#!/usr/bin/env bash
# Fetches the pinned conformance suites as build-time test data (#203, #204).
#
# Usage:   tools/conformance/fetch.sh [tck|miwg|all] [DEST]     (default: all, target/conformance/suites)
# Result:  DEST/dmn-tck and DEST/bpmn-miwg, unpacked from the GitHub archive of the pinned commit.
# Checks:  the archive's SHA-256 must equal the pin in tools/conformance/pins.env; a mismatch stops the run and
#          nothing is unpacked. Nothing fetched here is committed (target/ is ignored) or shipped.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=pins.env
. "$here/pins.env"
what="${1:-all}"
dest="${2:-$here/../../target/conformance/suites}"
mkdir -p "$dest"
dest="$(cd "$dest" && pwd)"

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

fetch() { # name repo commit sha
  local name="$1" repo="$2" commit="$3" want="$4" archive="$dest/$1.tar.gz"
  curl -fsSL --retry 3 --retry-delay 5 -o "$archive" "https://github.com/$repo/archive/$commit.tar.gz"
  local got; got="$(sha256 "$archive")"
  if [ "$got" != "$want" ]; then
    echo "::error title=Conformance suite checksum::$name archive of $commit has SHA-256 $got, pinned $want" >&2
    rm -f "$archive"
    exit 1
  fi
  rm -rf "${dest:?}/${name:?}"; mkdir -p "${dest:?}/${name:?}"
  tar -xzf "$archive" -C "${dest:?}/${name:?}" --strip-components=1
  rm -f "$archive"
  echo "$name: $repo@$commit, SHA-256 verified, unpacked to $dest/$name"
}

case "$what" in tck|all) fetch dmn-tck dmn-tck/tck "$DMN_TCK_COMMIT" "$DMN_TCK_SHA256";; esac
case "$what" in miwg|all) fetch bpmn-miwg bpmn-miwg/bpmn-miwg-test-suite "$MIWG_COMMIT" "$MIWG_SHA256";; esac
