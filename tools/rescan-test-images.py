#!/usr/bin/env python3
"""Rescans the container images the tests pin by digest (#293, PD-100).

Usage:   python3 tools/rescan-test-images.py OUT_DIR [IMAGE ...]
         With no IMAGE, scans every image `tools/check-image-pins.py --list` prints: the PostgreSQL and
         PgBouncer images, the Testcontainers reaper and the Playwright image of CI.
Why:     a digest never picks up base-image fixes, so a pinned image only gets older. Test images never
         ship, so a finding here is a reason to move the pin to a fixed digest on purpose, not a failed
         release: the weekly Deploy run opens an issue per image instead of failing.
What:    Grype scans each image from its registry (linux/amd64, the platform CI runs the tests on) with
         the repository's .grype.yaml. For each image with a high or critical finding, OUT_DIR gets
         <slug>.title (the issue title) and <slug>.md (the issue body: the findings and how to move the pin).
         OUT_DIR/summary.md holds one line per image, for the step summary. Grype JSON reports go to
         OUT_DIR/grype-<slug>.json.
Needs:   Grype 0.122.1 on PATH or as GRYPE=/path/to/grype; without either, the anchore/grype image pinned
         by digest in tools/scan-dependencies.sh, with Docker.
Exit:    0 every image was scanned (with or without findings), 2 a scan could not run.
"""

from __future__ import annotations

import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PLATFORM = "linux/amd64"
SEVERITIES = ("Critical", "High")
TITLE_PREFIX = "Platform: Update Pinned Test Image "
# GitHub limits an issue body to 65536 characters; the full list is in the Grype JSON report.
MAX_ROWS = 150


def pinned_images() -> list[str]:
    spec = importlib.util.spec_from_file_location("check_image_pins", ROOT / "tools/check-image-pins.py")
    pins = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(pins)
    return pins.pinned(ROOT)


def short_name(image: str) -> str:
    """mcr.microsoft.com/playwright@sha256:1507cbfc... -> playwright@1507cbfc"""
    name, digest = image.split("@sha256:", 1)
    return f"{name.rsplit('/', 1)[-1]}@{digest[:8]}"


def slug(image: str) -> str:
    return re.sub(r"[^A-Za-z0-9._-]", "_", short_name(image))


def issue_title(image: str) -> str:
    return TITLE_PREFIX + short_name(image)


def findings(report: dict) -> list[dict]:
    """The high and critical matches of a Grype JSON report that .grype.yaml does not suppress."""
    rows = []
    for match in report.get("matches", []):
        vulnerability = match.get("vulnerability", {})
        if vulnerability.get("severity") not in SEVERITIES:
            continue
        artifact = match.get("artifact", {})
        fixed = vulnerability.get("fix", {}).get("versions") or []
        rows.append({
            "severity": vulnerability["severity"],
            "id": vulnerability.get("id", "?"),
            "package": artifact.get("name", "?"),
            "version": artifact.get("version", "?"),
            "type": artifact.get("type", "?"),
            "fixed": ", ".join(fixed) if fixed else "no fix yet",
        })
    unique = {tuple(sorted(row.items())): row for row in rows}.values()
    return sorted(unique, key=lambda row: (SEVERITIES.index(row["severity"]), row["package"], row["id"]))


def issue_body(image: str, rows: list[dict], run_url: str) -> str:
    critical = sum(1 for row in rows if row["severity"] == "Critical")
    lines = [
        "### Summary",
        "",
        f"- **What we're building:** a move of the pinned test image `{short_name(image)}` to a digest "
        "without these known vulnerabilities.",
        "- **Why it matters:** a pinned digest never picks up base-image fixes; tests keep running an image "
        "that only gets older, even though it never ships.",
        "- **What the demo shows:** the next weekly rescan lists this image with no high or critical finding.",
        "",
        "### Findings",
        "",
        f"The weekly rescan found {len(rows)} high or critical finding(s), {critical} critical, in "
        f"`{image}` ({PLATFORM}). Run: {run_url or 'local run'}",
        "",
        "| Severity | Advisory | Package | Version | Type | Fixed in |",
        "|---|---|---|---|---|---|",
    ]
    lines += [f"| {r['severity']} | {r['id']} | `{r['package']}` | {r['version']} | {r['type']} | {r['fixed']} |"
              for r in rows[:MAX_ROWS]]
    if len(rows) > MAX_ROWS:
        lines += ["", f"{len(rows) - MAX_ROWS} more in the `rescan-test-images` artifact of the run."]
    lines += [
        "",
        "### How to fix",
        "",
        "Resolve the digest of the same tag, or of a newer patch tag, from the registry, check it against a "
        "second source, and replace digest and comment together wherever `python3 tools/check-image-pins.py "
        "--list` finds the old one (see \"Test container images\" in `wiki/Contributing.md`). Then run the "
        "Deploy workflow by hand to rescan.",
        "",
        "### Technical reference",
        "",
        "- Raised by the \"Pinned test images rescan\" job of `.github/workflows/deploy.yml` (#293, PD-100).",
    ]
    return "\n".join(lines) + "\n"


def grype_command(image: str, out_dir: Path, report: str) -> list[str]:
    args = [f"registry:{image}", "--platform", PLATFORM, "--show-suppressed", "-o", "table"]
    grype = os.environ.get("GRYPE") or shutil.which("grype")
    if grype:
        return [grype, *args, "--config", str(ROOT / ".grype.yaml"), "-o", f"json={out_dir / report}"]
    if not shutil.which("docker"):
        raise RuntimeError("Grype not found (PATH or GRYPE) and Docker not found.")
    scanner = re.search(r'GRYPE_IMAGE="([^"]+)"', (ROOT / "tools/scan-dependencies.sh").read_text(encoding="utf-8"))[1]
    cache = Path(os.environ.get("GRYPE_DB_CACHE_DIR") or Path.home() / ".cache/grype/db")
    cache.mkdir(parents=True, exist_ok=True)
    return ["docker", "run", "--rm", "-v", f"{ROOT / '.grype.yaml'}:/.grype.yaml:ro", "-v", f"{out_dir}:/report",
            "-v", f"{cache}:/cache", "-e", "GRYPE_DB_CACHE_DIR=/cache", scanner,
            *args, "--config", "/.grype.yaml", "-o", f"json=/report/{report}"]


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__, file=sys.stderr)
        return 2
    out_dir = Path(argv[1]).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    images = argv[2:] or pinned_images()
    run_url = ""
    if os.environ.get("GITHUB_RUN_ID"):
        run_url = (f"{os.environ.get('GITHUB_SERVER_URL', 'https://github.com')}/"
                   f"{os.environ.get('GITHUB_REPOSITORY', '')}/actions/runs/{os.environ['GITHUB_RUN_ID']}")
    summary = ["| Test image | High or critical findings |", "|---|---|"]
    for image in images:
        report = f"grype-{slug(image)}.json"
        print(f"== {image}", flush=True)
        try:
            status = subprocess.run(grype_command(image, out_dir, report)).returncode
        except RuntimeError as error:
            print(f"rescan-test-images: {error}", file=sys.stderr)
            return 2
        # .grype.yaml sets fail-on-severity, so Grype exits 2 on a finding; 1 or anything else is an error.
        if status not in (0, 2) or not (out_dir / report).is_file():
            print(f"rescan-test-images: Grype could not scan {image} (exit {status}).", file=sys.stderr)
            return 2
        rows = findings(json.loads((out_dir / report).read_text(encoding="utf-8")))
        summary.append(f"| `{image}` | {len(rows) or 'none'} |")
        if rows:
            (out_dir / f"{slug(image)}.title").write_text(issue_title(image) + "\n", encoding="utf-8")
            (out_dir / f"{slug(image)}.md").write_text(issue_body(image, rows, run_url), encoding="utf-8")
            print(f"FINDING  {len(rows)} high or critical in {image}: {issue_title(image)}")
        else:
            print(f"PASS  no high or critical vulnerability in {image}")
    (out_dir / "summary.md").write_text("\n".join(summary) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
