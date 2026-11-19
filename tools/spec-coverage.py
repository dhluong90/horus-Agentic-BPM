#!/usr/bin/env python3
"""Spec-to-test gate (TR-QA-01).

Every id in scope must have at least one test whose name starts with that id,
in upper case with hyphens written as underscores:

    RLE-013   ->  RLE_013_transactional_decision_log_rolls_back_with_host
    TR-EMB-01 ->  TR_EMB_01_host_boots_healthy_on_clean_database

Ids in scope:
  * every statement heading "### <ID> ..." in <module>/docs/SPEC.md
    (RLE-*, PRE-*, CKP-*);
  * every requirement id (TR-*) in the first column of the
    "Requirement-level tests" table of <module>/docs/TEST-PLAN.md.

A statement that is deliberately not implemented yet is listed in
<module>/docs/spec-pending.txt with the milestone that delivers it:

    RLE-001  M1

The gate fails when an id in scope has neither a test nor an allow-list entry,
when an allow-list entry names an id the module does not define, and when an
entry's milestone differs from the one in SPEC.md. It warns when an
allow-listed id already has a test, so the entry can be removed.

Test names are read from source, so the gate needs no build:
  * Java: method names and ArchUnit rule fields under src/test/java;
  * TypeScript and JavaScript: titles of it(), test() and describe() calls in
    *.test.* and *.spec.* files (the cockpit, once it has code).

Usage: python3 tools/spec-coverage.py [--root DIR] [--self-test]
Exit status: 0 when every id in scope is covered, 1 otherwise.
Standard library only; Python 3.9 or later.
"""

from __future__ import annotations

import argparse
import os
import re
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

SPEC_HEADING = re.compile(r"^###\s+((?:RLE|PRE|CKP)-\d{3})\b")
SPEC_MILESTONE = re.compile(r"^-\s+\*\*Milestone:\*\*\s+(M\d+)\b")
TR_ID = re.compile(r"`?(TR-[A-Z]+-\d{2})`?")
PENDING_LINE = re.compile(r"^((?:RLE|PRE|CKP)-\d{3}|TR-[A-Z]+-\d{2})\s+(M\d+)\b")

# A test name that starts with an id: RLE_013_..., TR_EMB_01_...
ID_PREFIX = r"(?:(?:RLE|PRE|CKP)_\d{3}|TR_[A-Z]+_\d{2})(?:_\w*)?"
JAVA_TEST_NAME = re.compile(
    r"\b(?:void|ArchRule)\s+(" + ID_PREFIX + r")\s*[(=;]"
)
TS_TEST_NAME = re.compile(
    r"\b(?:it|test|describe)(?:\.\w+)?\s*\(\s*['\"`](" + ID_PREFIX + r")"
)
TS_TEST_FILE = re.compile(r"\.(?:test|spec)\.(?:ts|tsx|js|jsx|mjs)$")
SKIP_DIRS = {".git", "node_modules", "target", "dist", "build", ".idea"}


def to_test_prefix(spec_id: str) -> str:
    return spec_id.replace("-", "_")


@dataclass
class Module:
    name: str
    path: Path
    spec_ids: dict[str, str] = field(default_factory=dict)  # id -> milestone
    requirement_ids: set[str] = field(default_factory=set)
    pending: dict[str, tuple[str, int]] = field(default_factory=dict)  # id -> (milestone, line)

    @property
    def in_scope(self) -> list[str]:
        return sorted(set(self.spec_ids) | self.requirement_ids)


def read_spec(path: Path) -> dict[str, str]:
    ids: dict[str, str] = {}
    current = None
    for line in path.read_text(encoding="utf-8").splitlines():
        heading = SPEC_HEADING.match(line)
        if heading:
            current = heading.group(1)
            ids[current] = ""
            continue
        if line.startswith("#"):
            current = None
            continue
        milestone = SPEC_MILESTONE.match(line)
        if milestone and current and not ids[current]:
            ids[current] = milestone.group(1)
    return ids


def read_requirement_ids(path: Path) -> set[str]:
    """TR-* ids in the first column of the 'Requirement-level tests' table."""
    ids: set[str] = set()
    in_section = False
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("## "):
            in_section = line.strip().lower().startswith("## requirement-level tests")
            continue
        if in_section and line.startswith("|"):
            first_cell = line.split("|")[1]
            ids.update(TR_ID.findall(first_cell))
    return ids


def read_pending(path: Path) -> tuple[dict[str, tuple[str, int]], list[str]]:
    entries: dict[str, tuple[str, int]] = {}
    errors: list[str] = []
    if not path.exists():
        return entries, errors
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        match = PENDING_LINE.match(line)
        if not match:
            errors.append(f"{path}:{number}: cannot read '{raw.strip()}'; expected '<ID> <milestone>', for example 'RLE-001 M1'")
            continue
        spec_id, milestone = match.groups()
        if spec_id in entries:
            errors.append(f"{path}:{number}: {spec_id} is listed twice")
        entries[spec_id] = (milestone, number)
    return entries, errors


def discover_modules(root: Path) -> list[Module]:
    modules = []
    for spec in sorted(root.glob("*/docs/SPEC.md")):
        module_dir = spec.parent.parent
        module = Module(name=module_dir.name, path=module_dir)
        module.spec_ids = read_spec(spec)
        test_plan = module_dir / "docs" / "TEST-PLAN.md"
        if test_plan.exists():
            module.requirement_ids = read_requirement_ids(test_plan)
        modules.append(module)
    return modules


def collect_test_names(root: Path) -> dict[str, list[str]]:
    """Maps each test name to the files that declare it."""
    names: dict[str, list[str]] = {}
    for directory, subdirs, files in os.walk(root):
        subdirs[:] = [d for d in subdirs if d not in SKIP_DIRS]
        relative_dir = Path(directory).relative_to(root).as_posix()
        for file_name in files:
            relative = f"{relative_dir}/{file_name}" if relative_dir != "." else file_name
            if file_name.endswith(".java") and "/src/test/java/" in f"/{relative}":
                pattern = JAVA_TEST_NAME
            elif TS_TEST_FILE.search(file_name):
                pattern = TS_TEST_NAME
            else:
                continue
            text = (Path(directory) / file_name).read_text(encoding="utf-8", errors="replace")
            for name in pattern.findall(text):
                names.setdefault(name, []).append(relative)
    return names


def tests_for(spec_id: str, test_names: dict[str, list[str]]) -> list[str]:
    prefix = to_test_prefix(spec_id)
    return sorted(n for n in test_names if n == prefix or n.startswith(prefix + "_"))


def annotate(level: str, file: str, message: str) -> str:
    if os.environ.get("GITHUB_ACTIONS") == "true":
        return f"::{level} file={file}::{message}"
    return f"{level.upper()}: {message}"


def run(root: Path, out=sys.stdout) -> int:
    modules = discover_modules(root)
    if not modules:
        print(annotate("error", "tools/spec-coverage.py", f"no */docs/SPEC.md under {root}"), file=out)
        return 1
    test_names = collect_test_names(root)
    failures = 0
    warnings = 0

    for module in modules:
        pending_path = module.path / "docs" / "spec-pending.txt"
        pending_rel = pending_path.relative_to(root).as_posix()
        spec_rel = (module.path / "docs" / "SPEC.md").relative_to(root).as_posix()
        module.pending, parse_errors = read_pending(pending_path)
        for error in parse_errors:
            print(annotate("error", pending_rel, error), file=out)
            failures += 1

        covered = pending_count = 0
        for spec_id in module.in_scope:
            tests = tests_for(spec_id, test_names)
            if spec_id in module.pending:
                pending_count += 1
                if tests:
                    warnings += 1
                    print(annotate(
                        "warning", pending_rel,
                        f"{spec_id} is on the allow-list but has a test ({tests[0]}); remove its line from {pending_rel}",
                    ), file=out)
            elif tests:
                covered += 1
            else:
                failures += 1
                print(annotate(
                    "error", spec_rel,
                    f"{spec_id} has no test whose name starts with {to_test_prefix(spec_id)}_ "
                    f"and is not on the allow-list {pending_rel}",
                ), file=out)

        for spec_id, (milestone, line) in sorted(module.pending.items()):
            if spec_id not in module.spec_ids and spec_id not in module.requirement_ids:
                failures += 1
                print(annotate(
                    "error", pending_rel,
                    f"{pending_rel}:{line}: {spec_id} is not a statement in {spec_rel} nor a requirement in the TEST-PLAN; remove the line",
                ), file=out)
                continue
            expected = module.spec_ids.get(spec_id)
            if expected and expected != milestone:
                failures += 1
                print(annotate(
                    "error", pending_rel,
                    f"{pending_rel}:{line}: {spec_id} is listed for {milestone} but {spec_rel} says {expected}",
                ), file=out)

        print(
            f"{module.name}: {len(module.in_scope)} ids in scope "
            f"({len(module.spec_ids)} statements, {len(module.requirement_ids)} requirements), "
            f"{covered} covered by tests, {pending_count} pending",
            file=out,
        )

    if failures:
        print(f"Spec-to-test gate FAILED: {failures} problem(s), {warnings} warning(s). "
              "Name a test after the id (see wiki/Contributing.md) or list the id with its milestone "
              "in the module's docs/spec-pending.txt.", file=out)
        return 1
    print(f"Spec-to-test gate passed: {warnings} warning(s).", file=out)
    return 0


def self_test() -> int:
    """Checks the parser and the matching rules on a throwaway tree."""
    import io

    def write(base: Path, relative: str, text: str) -> None:
        target = base / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")

    spec = (
        "# Spec\n\n### RLE-001 First\n- **Milestone:** M1\n\n"
        "### RLE-002 Second\n- **Milestone:** M2 (cut candidate)\n\n"
        "### RLE-010 Tenth\n- **Milestone:** M1\n"
    )
    plan = (
        "# Plan\n\n## Requirement-level tests\n| Requirement | Tests |\n|---|---|\n"
        "| `TR-EMB-01` | `X.TR_EMB_01_boots` |\n\n## Known gaps\n| `TR-ZZZ-99` | not a requirement row |\n"
    )
    java = (
        "class T {\n"
        "  @Test void RLE_001_first_works() {}\n"
        "  @Test void RLE_0100_not_rle_010() {}\n"
        "  @ArchTest static final ArchRule TR_EMB_01_rule = null;\n"
        "}\n"
    )
    cases = []
    with tempfile.TemporaryDirectory() as tmp:
        base = Path(tmp)
        write(base, "m/docs/SPEC.md", spec)
        write(base, "m/docs/TEST-PLAN.md", plan)
        write(base, "m/src/test/java/T.java", java)
        write(base, "m/docs/spec-pending.txt", "RLE-002 M2  # later\n")
        out = io.StringIO()
        cases.append(("RLE-010 uncovered fails", run(base, out) == 1 and "RLE-010 has no test" in out.getvalue()))
        cases.append(("RLE_0100 does not cover RLE-010", "RLE-010 has no test" in out.getvalue()))
        cases.append(("TR-ZZZ-99 outside the table is ignored", "TR-ZZZ-99" not in out.getvalue()))

        write(base, "m/docs/spec-pending.txt", "RLE-002 M2\nRLE-010 M1\n")
        out = io.StringIO()
        cases.append(("all covered or pending passes", run(base, out) == 0))
        cases.append(("ArchRule field counts", "1 requirements), 2 covered" in out.getvalue()))

        write(base, "m/docs/spec-pending.txt", "RLE-001 M1\nRLE-002 M2\nRLE-010 M1\n")
        out = io.StringIO()
        cases.append(("tested pending id warns", run(base, out) == 0 and "RLE-001 is on the allow-list but has a test" in out.getvalue()))

        write(base, "m/docs/spec-pending.txt", "RLE-002 M3\nRLE-010 M1\nRLE-099 M1\n")
        out = io.StringIO()
        result = run(base, out)
        cases.append(("milestone drift fails", result == 1 and "listed for M3 but" in out.getvalue()))
        cases.append(("unknown pending id fails", "RLE-099 is not a statement" in out.getvalue()))

        write(base, "m/web/src/a.test.tsx", "it('RLE_010_renders', () => {})\n")
        write(base, "m/docs/spec-pending.txt", "RLE-002 M2\n")
        out = io.StringIO()
        cases.append(("TypeScript test title counts", run(base, out) == 0))

    failed = [name for name, ok in cases if not ok]
    for name, ok in cases:
        print(f"{'ok  ' if ok else 'FAIL'} {name}")
    return 1 if failed else 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Spec-to-test gate (TR-QA-01).")
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent,
                        help="repository root (default: the parent of tools/)")
    parser.add_argument("--self-test", action="store_true", help="check the gate itself and exit")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    return run(args.root.resolve())


if __name__ == "__main__":
    sys.exit(main())
