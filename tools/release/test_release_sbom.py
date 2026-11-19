#!/usr/bin/env python3
"""Unit tests of the release tag check in release-sbom.py (PD-087, ADR-0022).

Run: python3 tools/release/test_release_sbom.py -v
The end-to-end dry run of the guard, with git and Maven, is tools/release/test-release-tag.sh.
"""

from __future__ import annotations

import importlib.util
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("release_sbom", HERE / "release-sbom.py")
release_sbom = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release_sbom)

GROUP = "io.github.dhluong90.bpm"
REACTOR = [("bpm-parent", GROUP, "0.1.0"), ("bpm-bom", GROUP, "0.1.0"), ("rule-engine-core", GROUP, "0.1.0")]


class ReleaseTagTest(unittest.TestCase):

    def errors(self, tag, projects=REACTOR):
        return release_sbom.release_tag_errors(tag, projects, GROUP)

    def test_project_tag_with_every_module_at_its_version_passes(self):
        self.assertEqual([], self.errors("v0.1.0"))

    def test_per_module_tag_is_refused_and_names_the_project_tag(self):
        errors = self.errors("rule-engine/v0.1.0")
        self.assertEqual(1, len(errors))
        self.assertIn("is a per-module tag", errors[0])
        self.assertIn("push v0.1.0", errors[0])

    def test_other_forms_are_refused(self):
        for tag in ("0.1.0", "v0.1", "v0.1.0-rc1", "release-0.1.0", "V0.1.0"):
            with self.subTest(tag=tag):
                errors = self.errors(tag)
                self.assertEqual(1, len(errors))
                self.assertIn("is not of the form vX.Y.Z", errors[0])

    def test_a_module_at_another_version_is_refused_by_name(self):
        projects = REACTOR + [("cockpit-spring-boot-starter", GROUP, "0.1.1")]
        self.assertEqual(["module cockpit-spring-boot-starter is at version 0.1.1, but the release is 0.1.0; "
                          "every module is released at the tag's version (PD-087)."], self.errors("v0.1.0", projects))

    def test_a_snapshot_is_never_released(self):
        projects = [("bpm-parent", GROUP, "0.1.0-SNAPSHOT")]
        self.assertIn("(a SNAPSHOT is never released)", self.errors("v0.1.0", projects)[0])

    def test_every_module_needs_the_release_group_id(self):
        projects = REACTOR + [("bpm-host-metadata", "org.example.bpm", "0.1.0")]
        errors = self.errors("v0.1.0", projects)
        self.assertEqual(1, len(errors))
        self.assertIn("module bpm-host-metadata has group id org.example.bpm", errors[0])

    def test_every_problem_is_reported_at_once(self):
        projects = [("bpm-parent", "org.example.bpm", "0.1.0-SNAPSHOT"), ("bpm-bom", GROUP, "0.2.0")]
        errors = self.errors("rule-engine/v0.1.0", projects)
        self.assertEqual(4, len(errors), errors)

    def test_without_an_effective_pom_the_tag_form_is_still_checked(self):
        self.assertEqual(1, release_sbom.release_tag("rule-engine/v0.1.0", None, GROUP))
        self.assertEqual(0, release_sbom.release_tag("v0.1.0", None, GROUP))

    def test_reads_every_project_of_a_reactor_effective_pom(self):
        pom = """<?xml version="1.0"?>
<projects>
  <project xmlns="http://maven.apache.org/POM/4.0.0">
    <groupId>io.github.dhluong90.bpm</groupId><artifactId>bpm-parent</artifactId><version>0.1.0</version>
  </project>
  <project xmlns="http://maven.apache.org/POM/4.0.0">
    <groupId>io.github.dhluong90.bpm</groupId><artifactId>bpm-bom</artifactId><version>0.1.1</version>
  </project>
</projects>"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "effective-pom.xml"
            path.write_text(pom, encoding="utf-8")
            self.assertEqual([("bpm-parent", GROUP, "0.1.0"), ("bpm-bom", GROUP, "0.1.1")],
                             release_sbom.reactor_projects(path))
            self.assertEqual(1, release_sbom.release_tag("v0.1.0", path, GROUP))


if __name__ == "__main__":
    unittest.main()
