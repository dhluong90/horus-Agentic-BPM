#!/usr/bin/env python3
"""Tests of tools/check-test-oracle.py (TR-LIC-01, TR-SEC-06, PD-098, #285). No network, no Maven.

Run: python3 tools/test_check_test_oracle.py   (or -v for each name)
The trees below are the real output of `mvn dependency:tree -Dscope=test -pl rule-engine/rule-engine-core`.
"""

from __future__ import annotations

import importlib.util
import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

HERE = Path(__file__).resolve().parent
_SPEC = importlib.util.spec_from_file_location("check_test_oracle", HERE / "check-test-oracle.py")
oracle = importlib.util.module_from_spec(_SPEC)
sys.modules["check_test_oracle"] = oracle
_SPEC.loader.exec_module(oracle)

PINNED_TREE = """\
io.github.dhluong90.bpm:rule-engine-core:jar:0.2.0-SNAPSHOT
+- tools.jackson.core:jackson-databind:jar:3.1.7:compile
|  +- com.fasterxml.jackson.core:jackson-annotations:jar:2.21:compile
|  \\- tools.jackson.core:jackson-core:jar:3.1.7:compile
+- org.assertj:assertj-core:jar:3.27.7:test
|  \\- net.bytebuddy:byte-buddy:jar:1.18.15:test
\\- org.camunda.feel:feel-engine:jar:1.22.1:test
   +- org.scala-lang:scala-library:jar:2.13.18:test
   +- com.lihaoyi:fastparse_2.13:jar:3.1.1:test
   |  +- com.lihaoyi:sourcecode_2.13:jar:0.4.0:test
   |  \\- com.lihaoyi:geny_2.13:jar:1.1.0:test
   +- org.slf4j:slf4j-api:jar:2.0.20:test
   +- com.fasterxml.uuid:java-uuid-generator:jar:5.2.0:test
   \\- com.fasterxml.jackson.module:jackson-module-scala_2.13:jar:2.21.7:test
      +- com.fasterxml.jackson.core:jackson-core:jar:2.21.7:test
      +- com.fasterxml.jackson.core:jackson-databind:jar:2.21.7:test
      \\- com.thoughtworks.paranamer:paranamer:jar:2.8.3:test
"""


class TestOracleCheckTest(unittest.TestCase):

    def run_check(self, command: str, content: str, allow_list: Path | None = None) -> tuple[int, str]:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        target = Path(tmp.name) / "input"
        target.write_text(content, encoding="utf-8")
        argv = ["check-test-oracle.py", command, str(target)]
        if allow_list is not None:
            argv += ["--allow-list", str(allow_list)]
        out = io.StringIO()
        with redirect_stdout(out):
            status = oracle.main(argv)
        return status, out.getvalue()

    def test_the_pinned_tree_passes_and_lists_the_eleven_artifacts(self) -> None:
        status, out = self.run_check("tree", PINNED_TREE)
        self.assertEqual(0, status, out)
        self.assertEqual(11, out.count("PASS  "), out)
        self.assertIn("PASS  com.fasterxml.jackson.module:jackson-module-scala_2.13:2.21.7 (Apache-2.0)", out)
        self.assertIn("11 artifacts checked", out)

    def test_the_file_lists_exactly_the_resolved_tree_of_pd_098(self) -> None:
        listed = oracle.read_allow_list(oracle.ALLOW_LIST)
        self.assertEqual(oracle.oracle_subtree(PINNED_TREE), {key: version for key, (version, _) in listed.items()})
        self.assertNotIn("com.fasterxml.jackson.core:jackson-annotations", listed)

    def test_jackson_module_scala_at_another_version_fails_naming_both_versions(self) -> None:
        tree = PINNED_TREE.replace("jackson-module-scala_2.13:jar:2.21.7", "jackson-module-scala_2.13:jar:2.22.0")
        status, out = self.run_check("tree", tree)
        self.assertEqual(1, status, out)
        self.assertIn("FAIL  com.fasterxml.jackson.module:jackson-module-scala_2.13 is listed at 2.21.7 but resolved at 2.22.0", out)

    def test_an_unlisted_artifact_in_the_oracle_subtree_fails_naming_it(self) -> None:
        tree = PINNED_TREE.replace("   +- org.slf4j:slf4j-api:jar:2.0.20:test\n",
                                   "   +- org.slf4j:slf4j-api:jar:2.0.20:test\n"
                                   "   +- org.scala-lang:scala-reflect:jar:2.13.18:test\n")
        status, out = self.run_check("tree", tree)
        self.assertEqual(1, status, out)
        self.assertIn("FAIL  org.scala-lang:scala-reflect:2.13.18 is in the org.camunda.feel:feel-engine test subtree "
                      "but not in the allow-list file", out)

    def test_a_listed_artifact_no_longer_resolved_fails_naming_it(self) -> None:
        tree = PINNED_TREE.replace("   +- com.fasterxml.uuid:java-uuid-generator:jar:5.2.0:test\n", "")
        status, out = self.run_check("tree", tree)
        self.assertEqual(1, status, out)
        self.assertIn("FAIL  com.fasterxml.uuid:java-uuid-generator:5.2.0 is listed but no longer resolved", out)

    def test_artifacts_outside_the_oracle_subtree_are_not_checked(self) -> None:
        tree = PINNED_TREE.replace("org.assertj:assertj-core:jar:3.27.7", "org.assertj:assertj-core:jar:9.9.9")
        self.assertEqual(0, self.run_check("tree", tree)[0])

    def test_maven_log_prefixes_are_read(self) -> None:
        tree = "".join(f"[INFO] {line}\n" for line in PINNED_TREE.splitlines())
        self.assertEqual(0, self.run_check("tree", tree)[0])

    def test_a_tree_without_the_oracle_fails(self) -> None:
        tree = PINNED_TREE.split("\\- org.camunda.feel")[0]
        status, out = self.run_check("tree", tree)
        self.assertEqual(1, status, out)
        self.assertIn("org.camunda.feel:feel-engine is not in the resolved test tree", out)

    def test_a_licence_outside_the_policy_fails(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        bad = Path(tmp.name) / "allow.txt"
        bad.write_text(oracle.ALLOW_LIST.read_text(encoding="utf-8").replace(
            "paranamer:2.8.3 | BSD-3-Clause", "paranamer:2.8.3 | LGPL-2.1"), encoding="utf-8")
        status, out = self.run_check("tree", PINNED_TREE, bad)
        self.assertEqual(1, status, out)
        self.assertIn("com.thoughtworks.paranamer:paranamer:2.8.3 is listed under LGPL-2.1", out)

    def test_a_malformed_line_is_a_usage_error(self) -> None:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        bad = Path(tmp.name) / "allow.txt"
        bad.write_text("org.camunda.feel:feel-engine | Apache-2.0 | no version\n", encoding="utf-8")
        self.assertEqual(2, self.run_check("tree", PINNED_TREE, bad)[0])

    def test_a_release_sbom_without_the_oracle_passes(self) -> None:
        bom = json.loads((HERE / "fixtures/bom-with-test-oracle.cdx.json").read_text(encoding="utf-8"))
        bom["components"] = [c for c in bom["components"] if c["group"] != "org.scala-lang"]
        status, out = self.run_check("sbom", json.dumps(bom))
        self.assertEqual(0, status, out)

    def test_the_sbom_fixture_with_one_oracle_artifact_fails(self) -> None:
        status, out = self.run_check("sbom", (HERE / "fixtures/bom-with-test-oracle.cdx.json").read_text(encoding="utf-8"))
        self.assertEqual(1, status, out)
        self.assertIn("FAIL  org.scala-lang:scala-library:2.13.18 is in the bill of materials", out)
        self.assertNotIn("jackson-annotations", out)

    def test_an_oracle_artifact_at_another_version_or_nested_still_fails(self) -> None:
        bom = {"components": [{"group": "io.github.dhluong90.bpm", "name": "x", "version": "1",
                               "components": [{"group": "org.camunda.feel", "name": "feel-engine", "version": "1.23.0"}]}]}
        status, out = self.run_check("sbom", json.dumps(bom))
        self.assertEqual(1, status, out)
        self.assertIn("org.camunda.feel:feel-engine:1.23.0", out)


if __name__ == "__main__":
    unittest.main()
