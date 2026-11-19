#!/usr/bin/env python3
"""Tests of tools/check-migration-order.py (#296) on fixture git repositories.

Each test builds a throwaway repository with a main branch and a change branch, then runs the check
as CI runs it. No network.
Run: python3 tools/test_check_migration_order.py   (or -v for each name)
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

_SPEC = importlib.util.spec_from_file_location("check_migration_order", Path(__file__).with_name("check-migration-order.py"))
check = importlib.util.module_from_spec(_SPEC)
sys.modules["check_migration_order"] = check
_SPEC.loader.exec_module(check)

RULE = "rule-engine/rule-engine-postgres/src/main/resources/io/github/dhluong90/bpm/rules/postgres/migration"
PROCESS = "process-engine/process-engine-postgres/src/main/resources/io/github/dhluong90/bpm/process/postgres/migration"
EXAMPLE = "examples/rule-engine-only/src/main/resources/db/migration"
REAL_RETIRED = Path(__file__).with_name("migration-retired-versions.txt")


class Repo:
    """A throwaway repository: main with the given migrations, then a branch named change."""

    def __init__(self, main_files: dict[str, str]) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.email", "test@example.invalid")
        self.git("config", "user.name", "test")
        self.git("config", "commit.gpgsign", "false")
        self.commit(main_files or {"README.md": "fixture\n"}, "main")
        self.git("checkout", "-q", "-b", "change")

    def git(self, *args: str) -> str:
        return subprocess.run(["git", *args], cwd=self.root, check=True, capture_output=True, text=True).stdout

    def commit(self, files: dict[str, str | None], message: str) -> None:
        for path, content in files.items():
            target = self.root / path
            if content is None:
                self.git("rm", "-q", path)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")
            self.git("add", path)
        self.git("commit", "-q", "--allow-empty", "-m", message)

    def on_main(self, files: dict[str, str | None]) -> None:
        self.git("checkout", "-q", "main")
        self.commit(files, "main moves on")
        self.git("checkout", "-q", "change")

    def run(self, *extra: str) -> tuple[int, str]:
        out = io.StringIO()
        cwd = os.getcwd()
        os.chdir(self.root)
        try:
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
                status = check.main(["--base", "main", "--head", "HEAD", "--retired", str(REAL_RETIRED), *extra])
        finally:
            os.chdir(cwd)
        return status, out.getvalue()

    def close(self) -> None:
        self._tmp.cleanup()


def rule(version: int, name: str = "x") -> str:
    return f"{RULE}/V{version}__{name}.sql"


def process(version: int, name: str = "x") -> str:
    return f"{PROCESS}/V{version}__{name}.sql"


MAIN_WITH_RULE_V10 = {**{rule(n, f"m{n}"): f"-- rule {n}\n" for n in (1, 2, 3, 4, 5, 6, 10)},
                      process(1, "base"): "-- process 1\n", process(2, "claim"): "-- process 2\n",
                      f"{EXAMPLE}/V1__host.sql": "-- host 1\n"}


class MigrationOrderTest(unittest.TestCase):

    def repo(self, main_files: dict[str, str] = MAIN_WITH_RULE_V10) -> Repo:
        repo = Repo(dict(main_files))
        self.addCleanup(repo.close)
        return repo

    def test_out_of_order_rule_migration_fails_naming_file_highest_and_next_free(self) -> None:
        repo = self.repo()
        repo.commit({f"{RULE}/V9__x.sql": "-- nine\n"}, "add V9")
        status, out = repo.run()
        self.assertEqual(1, status, out)
        self.assertIn("V9__x.sql", out)
        self.assertIn("V10", out)
        self.assertIn("V11", out)

    def test_lower_unretired_number_fails_as_out_of_order(self) -> None:
        repo = self.repo()
        repo.commit({f"{RULE}/V3_1__x.sql": "-- three point one\n"}, "add V3.1")
        status, out = repo.run()
        self.assertEqual(1, status, out)
        self.assertIn("V3_1__x.sql", out)
        self.assertIn("at or below the highest rule-engine version on main, V10", out)
        self.assertIn("next free number: V11", out)

    def test_next_number_above_main_passes(self) -> None:
        repo = self.repo()
        repo.commit({rule(11): "-- eleven\n"}, "add V11")
        status, out = repo.run()
        self.assertEqual(0, status, out)
        self.assertIn("added migrations in order", out)

    def test_two_added_process_migrations_with_one_version_fail_naming_both(self) -> None:
        repo = self.repo()
        repo.commit({process(3, "a"): "-- a\n", process(3, "b"): "-- b\n"}, "two V3")
        status, out = repo.run()
        self.assertEqual(1, status, out)
        self.assertIn("V3__a.sql", out)
        self.assertIn("V3__b.sql", out)
        self.assertIn("more than one migration", out)

    def test_added_migration_repeating_a_version_on_main_fails_naming_both(self) -> None:
        repo = self.repo()
        repo.commit({process(2, "other"): "-- other\n"}, "repeat V2")
        status, out = repo.run()
        self.assertEqual(1, status, out)
        self.assertIn("V2__claim.sql", out)
        self.assertIn("V2__other.sql", out)

    def test_retired_number_fails_whatever_the_highest_on_main(self) -> None:
        low_main = {rule(n, f"m{n}"): f"-- rule {n}\n" for n in (1, 2, 3, 4, 5)}
        repo = self.repo(low_main)
        repo.commit({rule(8): "-- eight\n"}, "add V8")
        status, out = repo.run()
        self.assertEqual(1, status, out)
        self.assertIn("V8__x.sql", out)
        self.assertIn("V8 is a retired rule-engine version", out)
        self.assertIn("next free number: V6", out)

    def test_next_free_number_skips_retired_numbers(self) -> None:
        main_v6 = {rule(n, f"m{n}"): f"-- rule {n}\n" for n in range(1, 7)}
        repo = self.repo(main_v6)
        repo.commit({f"{RULE}/V5_1__late.sql": "-- late\n"}, "add V5.1")
        status, out = repo.run()
        self.assertEqual(1, status, out)
        self.assertIn("next free number: V10", out)

    def test_edited_migration_on_main_fails_naming_the_file(self) -> None:
        repo = self.repo()
        repo.commit({rule(4, "m4"): "-- rule 4, edited\n"}, "edit V4")
        status, out = repo.run()
        self.assertEqual(1, status, out)
        self.assertIn("V4__m4.sql: V4 is on main and this change edits it", out)

    def test_deleted_migration_on_main_fails(self) -> None:
        repo = self.repo()
        repo.commit({process(2, "claim"): None}, "drop V2")
        status, out = repo.run()
        self.assertEqual(1, status, out)
        self.assertIn("V2__claim.sql: V2 is on main and this change deletes it", out)

    def test_migration_moved_with_identical_bytes_and_name_passes(self) -> None:
        repo = self.repo()
        moved = rule(2, "m2").replace("io/github/dhluong90", "io/github/other")
        repo.commit({rule(2, "m2"): None, moved: "-- rule 2\n"}, "move package")
        status, out = repo.run()
        self.assertEqual(0, status, out)

    def test_change_without_engine_migrations_passes_with_no_work(self) -> None:
        repo = self.repo()
        repo.commit({"README.md": "docs only\n"}, "docs")
        status, out = repo.run()
        self.assertEqual(0, status, out)
        self.assertIn("no engine migration added or changed", out)

    def test_example_host_migrations_are_not_checked(self) -> None:
        repo = self.repo()
        repo.commit({f"{EXAMPLE}/V1__again.sql": "-- host dup\n", f"{EXAMPLE}/V1__host.sql": "-- host edited\n"}, "host")
        status, out = repo.run()
        self.assertEqual(0, status, out)

    def test_branch_behind_main_is_not_blamed_for_migrations_it_has_not_merged(self) -> None:
        repo = self.repo()
        repo.commit({process(3, "mine"): "-- mine\n"}, "add process V3")
        repo.on_main({rule(11, "other"): "-- other branch\n"})
        status, out = repo.run()
        self.assertEqual(0, status, out)

    def test_migration_that_merged_on_main_after_the_branch_started_makes_it_out_of_order(self) -> None:
        repo = self.repo()
        repo.commit({rule(11, "mine"): "-- mine\n"}, "add V11")
        repo.on_main({rule(11, "theirs"): "-- theirs\n", rule(12, "theirs"): "-- theirs\n"})
        status, out = repo.run()
        self.assertEqual(1, status, out)
        self.assertIn("V11__mine.sql", out)
        self.assertIn("V11__theirs.sql", out)

    def test_push_to_main_compares_with_the_commit_before_the_push(self) -> None:
        repo = self.repo()
        repo.git("checkout", "-q", "main")
        repo.commit({rule(12, "first"): "-- first\n"}, "merge one")
        repo.commit({rule(11, "late"): "-- late\n"}, "merge two")
        status, out = repo.run("--base", "HEAD~1")
        self.assertEqual(1, status, out)
        self.assertIn("V11__late.sql", out)
        self.assertIn("highest rule-engine version on main, V12", out)

    def test_retired_list_is_valid_and_holds_rule_engine_v7_to_v9_and_process_engine_v5(self) -> None:
        retired = check.read_retired(REAL_RETIRED)
        self.assertEqual({(7,), (8,), (9,)}, set(retired["rule-engine"]))
        self.assertEqual({(5,)}, set(retired["process-engine"]))

    def test_retired_process_engine_v5_fails_and_next_free_number_skips_it(self) -> None:
        repo = self.repo()
        repo.commit({process(5, "join"): "-- join\n"}, "add process V5")
        status, out = repo.run()
        self.assertEqual(1, status, out)
        self.assertIn("V5 is a retired process-engine version", out)
        self.assertIn("highest process-engine version on main: V2; next free number: V3", out)

    def test_malformed_retired_line_is_a_usage_error(self) -> None:
        with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as bad:
            bad.write("rule-engine seven no version\n")
        self.addCleanup(os.unlink, bad.name)
        repo = self.repo()
        status, out = repo.run("--retired", bad.name)
        self.assertEqual(2, status, out)


if __name__ == "__main__":
    unittest.main()
