#!/usr/bin/env python3
"""Reports a newer Temurin 21 JRE than the one deploy/docker/Dockerfile pins (docs/DEPLOYING.md).

Usage:   deploy/check-jre-release.py
         JRE_RELEASE=jdk-21.0.12+7 deploy/check-jre-release.py    # check against another release

Reads JRE_RELEASE from the environment or, when unset, from "ARG JRE_RELEASE=" in the Dockerfile. Asks
the Adoptium API for the latest generally available Temurin 21 JRE for Alpine Linux on x64 and aarch64,
the two architectures the image is built for, and compares by major, minor, security, patch and build.
It also says whether the official eclipse-temurin image carries the pinned release for amd64 and arm64,
so switching back to it can be evaluated; that line never changes the exit status.

Never edits the Dockerfile: a version bump is a reviewed pull request (docs/DEPLOYING.md, Updating the JRE).

Exit:    0 the pinned release is the latest for both architectures, 1 a newer release exists,
         2 the check could not run (no network, an unexpected answer, no JRE_RELEASE).
"""

import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DOCKERFILE = ROOT / "deploy" / "docker" / "Dockerfile"
ARCHITECTURES = {"x64": "amd64", "aarch64": "arm64"}  # Adoptium name -> Docker name
LATEST = ("https://api.adoptium.net/v3/assets/latest/21/hotspot"
          "?architecture={arch}&image_type=jre&os=alpine-linux&vendor=eclipse")
OFFICIAL_TAG = "https://hub.docker.com/v2/repositories/library/eclipse-temurin/tags/{tag}"
RELEASE = re.compile(r"^jdk-(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:\.(\d+))?\+(\d+)$")


def parse_release(name):
    """jdk-21.0.13.1+2 -> (21, 0, 13, 1, 2); missing parts are 0."""
    match = RELEASE.match(name)
    if not match:
        raise ValueError(f"not a Temurin release name: {name!r} (expected e.g. jdk-21.0.13.1+2)")
    return tuple(int(part or 0) for part in match.groups())


def pinned_release():
    if os.environ.get("JRE_RELEASE"):
        return os.environ["JRE_RELEASE"], "JRE_RELEASE in the environment"
    match = re.search(r"^ARG JRE_RELEASE=(\S+)$", DOCKERFILE.read_text(encoding="utf-8"), re.MULTILINE)
    if not match:
        raise ValueError(f"no 'ARG JRE_RELEASE=' in {DOCKERFILE}")
    return match.group(1), str(DOCKERFILE.relative_to(ROOT))


def get_json(url):
    request = urllib.request.Request(url, headers={"Accept": "application/json",
                                                   "User-Agent": "horus-bpm-jre-check"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def latest(arch):
    """The latest GA release for one architecture: (version tuple, release name, archive, sha256)."""
    assets = get_json(LATEST.format(arch=arch))
    if not assets:
        raise ValueError(f"the Adoptium API lists no Temurin 21 JRE for alpine-linux {arch}")
    asset = assets[0]
    v = asset["version"]
    version = (v["major"], v["minor"], v["security"], v.get("patch") or 0, v["build"])
    package = asset["binary"]["package"]
    return version, asset["release_name"], package["link"], package["checksum"]


def official_image(release):
    """Docker architectures of eclipse-temurin:<file version>-jre-alpine, or None when the tag is absent."""
    tag = release.removeprefix("jdk-").replace("+", "_") + "-jre-alpine"
    try:
        data = get_json(OFFICIAL_TAG.format(tag=tag))
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return tag, None
        raise
    return tag, sorted({image["architecture"] for image in data.get("images", [])})


def main():
    lines = []

    def say(text=""):
        print(text)
        lines.append(text)

    try:
        release, source = pinned_release()
        pinned = parse_release(release)
        found = {arch: latest(arch) for arch in ARCHITECTURES}
    except (OSError, ValueError, KeyError, urllib.error.URLError) as error:
        print(f"The JRE check could not run: {error}", file=sys.stderr)
        return 2

    say(f"Pinned: {release} ({source})")
    newer = {arch: entry for arch, entry in found.items() if entry[0] > pinned}
    for arch, (version, name, _, _) in found.items():
        state = "NEWER" if arch in newer else "same" if version == pinned else "older"
        say(f"Latest Temurin 21 JRE for alpine-linux {arch}: {name} ({state})")

    try:
        tag, archs = official_image(release)
        if archs is None:
            say(f"Official image eclipse-temurin:{tag}: not published yet")
        else:
            both = all(docker in archs for docker in ARCHITECTURES.values())
            say(f"Official image eclipse-temurin:{tag}: {', '.join(archs)}"
                + ("; it carries the pinned JRE for amd64 and arm64, so switching back can be evaluated"
                   if both else ""))
    except (OSError, ValueError, KeyError, urllib.error.URLError) as error:
        say(f"Official image: not checked ({error})")

    if newer:
        say()
        say("A newer Temurin 21 JRE is published. Update JRE_RELEASE, JRE_FILE_VERSION and both SHA-256 values in "
            "deploy/docker/Dockerfile as docs/DEPLOYING.md (Updating the JRE) describes; the build checks the GPG "
            "signature. Archives and the SHA-256 the API lists, to compare with the .sha256.txt files:")
        for arch, (_, name, link, checksum) in newer.items():
            say(f"  {arch}: {name} {link} sha256 {checksum}")

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as out:
            out.write("### Temurin JRE update check\n\n```\n" + "\n".join(lines) + "\n```\n")
    return 1 if newer else 0


if __name__ == "__main__":
    sys.exit(main())
