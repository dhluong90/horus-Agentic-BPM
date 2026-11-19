#!/usr/bin/env python3
"""Tests of tools/check-image-pins.py (PD-100, #288) on fixture trees. No network, no Docker.

Run: python3 tools/test_check_image_pins.py   (or -v for each name)
"""

from __future__ import annotations

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

_SPEC = importlib.util.spec_from_file_location("check_image_pins", Path(__file__).with_name("check-image-pins.py"))
pins = importlib.util.module_from_spec(_SPEC)
sys.modules["check_image_pins"] = pins
_SPEC.loader.exec_module(pins)

DIGEST = "sha256:" + "0123456789abcdef" * 4
TEST_DIR = "rule-engine/rule-engine-postgres/src/test/java/io/example"


class ImagePinsTest(unittest.TestCase):

    def tree(self, files: dict[str, str]) -> Path:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        for path, content in files.items():
            (root / path).parent.mkdir(parents=True, exist_ok=True)
            (root / path).write_text(content, encoding="utf-8")
        return root

    def errors(self, files: dict[str, str]) -> list[str]:
        return pins.check(self.tree(files))

    def test_unpinned_postgres_17_in_a_test_fails_naming_file_and_line(self) -> None:
        errors = self.errors({f"{TEST_DIR}/NewIT.java": 'class NewIT {\n'
                              '    static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:17-alpine");\n}\n'})
        self.assertEqual(1, len(errors), errors)
        self.assertIn(f"{TEST_DIR}/NewIT.java:2: image 'postgres:17-alpine' is not pinned by digest", errors[0])

    def test_unpinned_postgres_17_in_a_workflow_variable_fails(self) -> None:
        errors = self.errors({".github/workflows/ci.yml": "env:\n  POSTGRES_17_IMAGE: postgres:17-alpine # 17\n"})
        self.assertEqual([".github/workflows/ci.yml:2: image 'postgres:17-alpine' is not pinned by digest. Write "
                          "<name>@sha256:<digest> and name the tag in a comment (PD-100)."], errors)

    def test_tag_built_from_an_expression_is_not_a_pin(self) -> None:
        errors = self.errors({".github/workflows/ci.yml": "    env:\n      POSTGRES_IMAGE: postgres:${{ matrix.postgres }}-alpine\n"})
        self.assertEqual(1, len(errors), errors)
        self.assertIn("ci.yml:2", errors[0])

    def test_unpinned_pom_property_fails(self) -> None:
        errors = self.errors({"pom.xml": "<properties>\n  <bpm.test.redis.image>redis:8</bpm.test.redis.image>\n</properties>\n"})
        self.assertEqual(1, len(errors), errors)
        self.assertIn("pom.xml:2: image 'redis:8'", errors[0])

    def test_unpinned_default_in_a_tool_script_fails(self) -> None:
        errors = self.errors({"tools/run.sh": 'docker run "${POSTGRES_IMAGE:-postgres:16}"\n'})
        self.assertEqual(1, len(errors), errors)
        self.assertIn("tools/run.sh:1", errors[0])

    def test_digest_pinned_references_pass(self) -> None:
        self.assertEqual([], self.errors({
            f"{TEST_DIR}/OkIT.java": f'var pg = new PostgreSQLContainer("postgres@{DIGEST}");\n',
            ".github/workflows/ci.yml": f"env:\n  POSTGRES_14_IMAGE: postgres@{DIGEST} # 14-alpine\n"
                                        f"    container:\n      image: mcr.microsoft.com/playwright:v1.65.1-noble@{DIGEST}\n",
            "pom.xml": f"<bpm.test.pgbouncer.image>edoburu/pgbouncer@{DIGEST}</bpm.test.pgbouncer.image>\n",
            "tools/run.sh": f'docker run "${{POSTGRES_IMAGE:-postgres@{DIGEST}}}"\n',
        }))

    def test_tag_and_digest_together_fail_where_testcontainers_reads_them(self) -> None:
        errors = self.errors({f"{TEST_DIR}/TagIT.java": f'new GenericContainer<>("edoburu/pgbouncer:v1.26.1-p0@{DIGEST}");\n'})
        self.assertEqual(1, len(errors), errors)
        self.assertIn("Testcontainers 2 refuses", errors[0])
        self.assertIn(f"Write edoburu/pgbouncer@{DIGEST}", errors[0])

    def test_tag_and_digest_together_pass_for_a_workflow_job_container(self) -> None:
        self.assertEqual([], self.errors({".github/workflows/ci.yml": f"    container:\n      image: node:22-alpine@{DIGEST}\n"}))

    def test_property_reads_expressions_and_comments_are_not_references(self) -> None:
        self.assertEqual([], self.errors({
            f"{TEST_DIR}/PropIT.java": '// was new PostgreSQLContainer("postgres:16-alpine")\n'
                                       'var pg = new PostgreSQLContainer(TestImages.postgres());\n'
                                       'String image = "not an image reference";\n',
            "pom.xml": "<bpm.test.postgres.image>${bpm.test.postgres.image}</bpm.test.postgres.image>\n",
            ".github/workflows/ci.yml": "      # image: postgres:16\n      POSTGRES_IMAGE: ${{ env.PINNED }}\n",
        }))

    def test_exempt_line_and_paths_outside_the_scope_are_skipped(self) -> None:
        self.assertEqual([], self.errors({
            ".github/workflows/deploy.yml": "      IMAGE: horus/host:local # pin-exempt: built in this job\n",
            "docker-compose.yml": "    image: postgres:16-alpine\n",
            "examples/host/src/main/java/App.java": 'new PostgreSQLContainer("postgres:16")\n',
            "cockpit/web/node_modules/x/src/test/A.java": 'new PostgreSQLContainer("postgres:16")\n',
        }))

    def test_the_repository_tree_is_pinned(self) -> None:
        self.assertEqual([], pins.check(Path(__file__).resolve().parent.parent))


if __name__ == "__main__":
    unittest.main()
