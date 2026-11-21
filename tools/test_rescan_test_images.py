#!/usr/bin/env python3
"""Tests of tools/rescan-test-images.py (#293) on Grype report fixtures. No network, no Docker.

Run: python3 tools/test_rescan_test_images.py   (or -v for each name)
"""

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

_SPEC = importlib.util.spec_from_file_location("rescan_test_images", Path(__file__).with_name("rescan-test-images.py"))
rescan = importlib.util.module_from_spec(_SPEC)
sys.modules["rescan_test_images"] = rescan
_SPEC.loader.exec_module(rescan)

PLAYWRIGHT = "mcr.microsoft.com/playwright@sha256:1507cbfc550a63a6b7396791ca4f00df17beb56342d2447b3f106c306f525579"
PGBOUNCER = "edoburu/pgbouncer@sha256:488422e38cff912895d0d65d8963fcd306e6ac589a724398b6eb8e294e94d624"


def match(severity: str, cve: str, package: str, fixed: list[str] | None = None) -> dict:
    return {"vulnerability": {"id": cve, "severity": severity, "fix": {"versions": fixed or []}},
            "artifact": {"name": package, "version": "1.0-r0", "type": "apk"}}


class RescanTestImagesTest(unittest.TestCase):

    def test_titles_fit_the_issue_title_limit_and_name_the_image(self) -> None:
        for image in (PLAYWRIGHT, PGBOUNCER, "testcontainers/ryuk@sha256:" + "7c1a8a9a" * 8):
            with self.subTest(image=image):
                title = rescan.issue_title(image)
                self.assertLessEqual(len(title), 60, title)
        self.assertEqual("Platform: Update Pinned Test Image playwright@1507cbfc", rescan.issue_title(PLAYWRIGHT))

    def test_only_high_and_critical_findings_count_critical_first_duplicates_once(self) -> None:
        report = {"matches": [match("Medium", "CVE-1", "zlib"), match("High", "CVE-2", "libpq", ["18.7-r0"]),
                              match("Critical", "CVE-3", "openssl"), match("High", "CVE-2", "libpq", ["18.7-r0"]),
                              match("Low", "CVE-4", "busybox")],
                  "ignoredMatches": [match("Critical", "CVE-5", "suppressed")]}
        rows = rescan.findings(report)
        self.assertEqual([("Critical", "CVE-3"), ("High", "CVE-2")], [(r["severity"], r["id"]) for r in rows])
        self.assertEqual("no fix yet", rows[0]["fixed"])
        self.assertEqual("18.7-r0", rows[1]["fixed"])

    def test_a_clean_report_has_no_findings(self) -> None:
        self.assertEqual([], rescan.findings({"matches": [match("Medium", "CVE-1", "zlib")]}))

    def test_issue_body_opens_with_the_summary_and_names_image_and_run(self) -> None:
        rows = rescan.findings({"matches": [match("High", "CVE-2", "libpq", ["18.7-r0"])]})
        body = rescan.issue_body(PGBOUNCER, rows, "https://github.com/o/r/actions/runs/1")
        self.assertTrue(body.startswith("### Summary\n\n- **What we're building:**"), body)
        self.assertIn(f"`{PGBOUNCER}` (linux/amd64)", body)
        self.assertIn("| High | CVE-2 | `libpq` | 1.0-r0 | apk | 18.7-r0 |", body)
        self.assertIn("https://github.com/o/r/actions/runs/1", body)

    def test_a_long_list_is_cut_to_fit_an_issue(self) -> None:
        rows = rescan.findings({"matches": [match("High", f"CVE-{n}", "libpq") for n in range(400)]})
        body = rescan.issue_body(PGBOUNCER, rows, "")
        self.assertIn(f"{400 - rescan.MAX_ROWS} more in the `rescan-test-images` artifact", body)
        self.assertLess(len(body), 65536)


if __name__ == "__main__":
    unittest.main()
