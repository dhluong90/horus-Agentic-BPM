#!/usr/bin/env python3
"""Spec-to-test gate (TR-QA-01).

Every id in scope must have at least one running test whose name starts with
that id, in upper case with hyphens written as underscores:

    RLE-013   ->  RLE_013_transactional_decision_log_rolls_back_with_host
    TR-EMB-01 ->  TR_EMB_01_host_boots_healthy_on_clean_database

Ids in scope, per module (a folder with docs/SPEC.md):
  * every statement heading "## <ID> ...", "### <ID> ..." or deeper in
    docs/SPEC.md (RLE-*, PRE-*, CKP-*, three or more digits);
  * every requirement id (TR-*) in the first column of the
    "Requirement-level tests" table of docs/TEST-PLAN.md.

A statement that is deliberately not implemented yet is listed in
<module>/docs/spec-pending.txt with the milestone that delivers it:

    RLE-001  M1

The gate fails when
  * an id in scope has neither a running test nor an allow-list entry;
  * an allow-list entry names an id the module does not define, is listed
    twice, cannot be read, or has a milestone different from SPEC.md;
  * an allow-list entry's milestone is at or before the current milestone
    (tools/spec-coverage.ini), so no statement outlives its milestone;
  * SPEC.md has a malformed id heading, a duplicate statement, or a statement
    without a "- **Milestone:**" line;
  * the TEST-PLAN "Specification coverage" table lists other ids than SPEC.md.
It warns when an allow-listed id already has a test, so the entry can be removed.

What counts as a test (read from source, so no build is needed; comments and
string contents are ignored):
  * Java under src/test/java: a method or ArchUnit field annotated @Test,
    @ParameterizedTest, @RepeatedTest, @TestFactory, @TestTemplate, @Property,
    @Example or @ArchTest, whatever its return type, unless the member or an
    enclosing class carries @Disabled or another @Disabled... annotation;
  * TypeScript and JavaScript test files (*.test.* and *.spec.*, .ts .tsx .js
    .jsx .mjs .cjs .mts .cts, plus the testMatch patterns of any
    playwright.config.*): the title of an it() or test() call, including
    .each, .only and .concurrent forms; a describe() title when its block holds
    at least one counted test. skip, fixme, todo and fail forms, x-prefixed
    calls and everything inside a skipped describe do not count.

Each module's ids are matched only against tests under the module's own test
roots: the module folder plus the folders listed for it in tools/spec-coverage.ini.

Usage:  python3 tools/spec-coverage.py [--root DIR] [--config FILE]
Tests:  python3 tools/test_spec_coverage.py
Exit status: 0 when every id in scope is covered, 1 otherwise.
Standard library only; Python 3.9 or later.
"""

from __future__ import annotations

import argparse
import configparser
import os
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

# ---------------------------------------------------------------------------
# Ids

STATEMENT_ID = r"(?:RLE|PRE|CKP)-\d{3,}"
REQUIREMENT_ID = r"TR-[A-Z]+-\d{2,}"

HEADING = re.compile(r"^(#{1,6})\s+(\S+)")
ID_LIKE_HEADING_TOKEN = re.compile(r"^`?(?:RLE|PRE|CKP)-")
STATEMENT_TOKEN = re.compile(rf"^({STATEMENT_ID})(?![\w-])")
SPEC_MILESTONE = re.compile(r"^-\s+\*\*Milestone:\*\*\s+(M\d+)\b")
TR_ID = re.compile(rf"(?<![\w-])({REQUIREMENT_ID})(?![\w-])")
TABLE_STATEMENT_ID = re.compile(rf"^\|\s*`?({STATEMENT_ID})(?![\w-])")
PENDING_LINE = re.compile(rf"^({STATEMENT_ID}|{REQUIREMENT_ID})\s+(M\d+)$")
MILESTONE = re.compile(r"^M(\d+)$")

# A test name that starts with an id: RLE_013_..., TR_EMB_01_...
ID_PREFIX = re.compile(r"^(?:(?:RLE|PRE|CKP)_\d{3,}|TR_[A-Z]+_\d{2,})(?:_\w*)?$")
TITLE_PREFIX = re.compile(r"^((?:RLE|PRE|CKP)_\d{3,}|TR_[A-Z]+_\d{2,})(?![0-9A-Za-z])(\w*)")


def to_test_prefix(spec_id: str) -> str:
    return spec_id.replace("-", "_")


def milestone_number(value: str) -> Optional[int]:
    match = MILESTONE.match(value or "")
    return int(match.group(1)) if match else None


# ---------------------------------------------------------------------------
# Source masking: comments become spaces, string contents become '_'.
# Offsets and line breaks are kept, so masked and original text line up.

def mask_source(text: str, template_literals: bool) -> str:
    out = list(text)
    i, n = 0, len(text)

    def blank(start: int, end: int, fill: str) -> None:
        for k in range(start, min(end, n)):
            if out[k] != "\n":
                out[k] = fill

    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if c == "/" and nxt == "/":
            end = text.find("\n", i)
            end = n if end < 0 else end
            blank(i, end, " ")
            i = end
        elif c == "/" and nxt == "*":
            end = text.find("*/", i + 2)
            end = n if end < 0 else end + 2
            blank(i, end, " ")
            i = end
        elif not template_literals and text.startswith('"""', i):
            end = text.find('"""', i + 3)
            end = n if end < 0 else end + 3
            blank(i + 3, end - 3, "_")
            i = end
        elif c in "\"'" or (template_literals and c == "`"):
            j = i + 1
            while j < n and text[j] != c:
                if text[j] == "\\":
                    j += 1
                elif text[j] == "\n" and c != "`":
                    break
                j += 1
            blank(i + 1, j, "_")
            i = j + 1
        else:
            i += 1
    return "".join(out)


def matching_close(masked: str, open_index: int) -> int:
    """Index of the bracket closing the one at open_index, or len(masked)."""
    pairs = {"(": ")", "{": "}", "[": "]"}
    stack = [pairs[masked[open_index]]]
    for k in range(open_index + 1, len(masked)):
        ch = masked[k]
        if ch in pairs:
            stack.append(pairs[ch])
        elif stack and ch == stack[-1]:
            stack.pop()
            if not stack:
                return k
    return len(masked)


# ---------------------------------------------------------------------------
# Java

JAVA_TEST_ANNOTATIONS = {
    "Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate",
    "Property", "Example", "ArchTest",
}
JAVA_MEMBER_NAME = re.compile(r"(?<![\w.$])([A-Z][A-Z]*_\w+)\s*(?=[(=;])")
JAVA_CLASS = re.compile(r"(?<![\w.$])(?:class|interface|enum|record)\s+\w+[^{;]*\{")
ANNOTATION = re.compile(r"@\s*([\w.]+)")


def member_header(masked: str, name_start: int) -> str:
    """Text of the declaration before a member name: annotations, modifiers, type."""
    depth = 0
    k = name_start - 1
    while k >= 0:
        ch = masked[k]
        if ch in ")]>":
            depth += 1
        elif ch in "([<":
            if depth == 0:
                break
            depth -= 1
        elif ch in ";{}" and depth == 0:
            break
        k -= 1
    return masked[k + 1:name_start]


def annotation_names(header: str) -> Set[str]:
    return {name.rsplit(".", 1)[-1] for name in ANNOTATION.findall(header)}


def is_disabled(names: Set[str]) -> bool:
    return any(name.startswith("Disabled") for name in names)


def java_test_names(text: str) -> List[str]:
    masked = mask_source(text, template_literals=False)
    disabled_ranges = []
    for match in JAVA_CLASS.finditer(masked):
        brace = match.end() - 1
        if is_disabled(annotation_names(member_header(masked, match.start()))):
            disabled_ranges.append((brace, matching_close(masked, brace)))
    names = []
    for match in JAVA_MEMBER_NAME.finditer(masked):
        name = match.group(1)
        if not ID_PREFIX.match(name):
            continue
        found = annotation_names(member_header(masked, match.start(1)))
        if not (found & JAVA_TEST_ANNOTATIONS) or is_disabled(found):
            continue
        if any(start < match.start() < end for start, end in disabled_ranges):
            continue
        names.append(name)
    return names


# ---------------------------------------------------------------------------
# TypeScript and JavaScript (Vitest, Jest, Playwright)

TS_CALL = re.compile(r"(?<![\w.$])(x?(?:it|test|describe)|fit|fdescribe)((?:\s*\.\s*\w+)*)\s*(?=[(`])")
TS_REJECTED_MODIFIERS = {"skip", "fixme", "todo", "fail"}
TS_IGNORED_MODIFIERS = {"step", "use", "extend", "beforeEach", "afterEach", "beforeAll",
                        "afterAll", "info", "setTimeout", "slow", "configure", "expect"}
TS_EXTENSION = r"\.(?:ts|tsx|js|jsx|mjs|cjs|mts|cts)$"
TS_TEST_FILE = re.compile(r"\.(?:test|spec)" + TS_EXTENSION)
PLAYWRIGHT_CONFIG = re.compile(r"^playwright\.config" + TS_EXTENSION)


@dataclass
class TsCall:
    kind: str          # "test" or "describe"
    rejected: bool
    title: str
    start: int
    body_end: int


def skip_spaces(masked: str, pos: int) -> int:
    while pos < len(masked) and masked[pos].isspace():
        pos += 1
    return pos


def ts_calls(text: str, masked: str) -> List[TsCall]:
    calls = []
    for match in TS_CALL.finditer(masked):
        callee = match.group(1)
        modifiers = [m.strip() for m in match.group(2).split(".") if m.strip()]
        if any(m in TS_IGNORED_MODIFIERS for m in modifiers):
            continue
        kind = "describe" if "describe" in callee or "describe" in modifiers else "test"
        rejected = callee.startswith("x") or any(m in TS_REJECTED_MODIFIERS for m in modifiers)
        pos = match.end()
        if "each" in modifiers:  # it.each(table)(title, fn) or test.each`table`(title, fn)
            if masked[pos] == "(":
                pos = matching_close(masked, pos) + 1
            elif masked[pos] == "`":
                pos = masked.find("`", pos + 1) + 1
            pos = skip_spaces(masked, pos)
        if pos >= len(masked) or masked[pos] != "(":
            continue
        open_paren = pos
        pos = skip_spaces(masked, pos + 1)
        if pos >= len(masked) or masked[pos] not in "'\"`":
            continue
        end = masked.find(masked[pos], pos + 1)
        title = text[pos + 1:end if end > 0 else len(text)]
        calls.append(TsCall(kind, rejected, title, match.start(), matching_close(masked, open_paren)))
    return calls


def title_id(title: str) -> Optional[str]:
    match = TITLE_PREFIX.match(title)
    if not match or (match.group(2) and not match.group(2).startswith("_")):
        return None
    return match.group(1) + match.group(2)


def ts_test_names(text: str) -> List[str]:
    masked = mask_source(text, template_literals=True)
    calls = ts_calls(text, masked)
    skipped = [(c.start, c.body_end) for c in calls if c.kind == "describe" and c.rejected]

    def inside_skipped(call: TsCall) -> bool:
        return any(start < call.start < end for start, end in skipped)

    counted = [c for c in calls if c.kind == "test" and not c.rejected and not inside_skipped(c)]
    names = [title_id(c.title) for c in counted]
    for call in calls:
        if call.kind != "describe" or call.rejected or inside_skipped(call):
            continue
        if any(call.start < t.start < call.body_end for t in counted):
            names.append(title_id(call.title))
    return [n for n in names if n]


# ---------------------------------------------------------------------------
# Playwright testMatch

def glob_to_regex(pattern: str) -> str:
    out, i = [], 0
    while i < len(pattern):
        c = pattern[i]
        if pattern.startswith("**/", i):
            out.append(r"(?:.*/)?")
            i += 3
            continue
        if pattern.startswith("**", i):
            out.append(r".*")
            i += 2
            continue
        if c in "@?+*" and i + 1 < len(pattern) and pattern[i + 1] == "(":
            close = pattern.find(")", i)
            alternatives = "|".join(glob_to_regex(p) for p in pattern[i + 2:close].split("|"))
            out.append(f"(?:{alternatives})" + {"@": "", "?": "?", "+": "+", "*": "*"}[c])
            i = close + 1
            continue
        if c == "*":
            out.append(r"[^/]*")
        elif c == "?":
            out.append(r"[^/]")
        elif c == "{":
            close = pattern.find("}", i)
            out.append("(?:" + "|".join(glob_to_regex(p) for p in pattern[i + 1:close].split(",")) + ")")
            i = close + 1
            continue
        elif c == "[":
            close = pattern.find("]", i)
            out.append(pattern[i:close + 1])
            i = close + 1
            continue
        else:
            out.append(re.escape(c))
        i += 1
    return "".join(out)


@dataclass
class TestMatcher:
    base: Path
    globs: List[str]
    regexes: List[str]

    def matches(self, path: Path) -> bool:
        try:
            relative = path.relative_to(self.base).as_posix()
        except ValueError:
            return False
        for pattern in self.globs:
            pattern = pattern[2:] if pattern.startswith("./") else pattern
            if re.fullmatch(r"(?:.*/)?" + glob_to_regex(pattern), relative):
                return True
        return any(re.search(r, "/" + relative) for r in self.regexes)


def playwright_matcher(config: Path) -> TestMatcher:
    """Reads string, regular expression and array values of testMatch in a Playwright config."""
    text = config.read_text(encoding="utf-8", errors="replace")
    masked = mask_source(text, template_literals=True)
    globs, regexes = [], []
    for match in re.finditer(r"\btestMatch\s*:\s*", masked):
        pos = match.end()
        if masked[pos] == "[":
            end = matching_close(masked, pos)
        else:
            stop = re.search(r"[,}\n]", masked[pos:])
            end = pos + (stop.start() if stop else len(masked) - pos)
        segment_masked, segment = masked[pos:end + 1], text[pos:end + 1]
        for lit in re.finditer(r"(['\"`])(_*)\1", segment_masked):
            globs.append(segment[lit.start() + 1:lit.end() - 1])
        for lit in re.finditer(r"(?<![\w)\]])/((?:\\/|[^/\n])+)/[a-z]*", segment_masked):
            regexes.append(segment[lit.start(1):lit.end(1)])
    return TestMatcher(config.parent, globs, regexes)


# ---------------------------------------------------------------------------
# Repository model

@dataclass
class Config:
    current_milestone: str = "M0"
    extra_roots: Dict[str, List[str]] = field(default_factory=dict)


def read_config(path: Optional[Path]) -> Tuple[Config, List[str]]:
    config, errors = Config(), []
    if path is None or not path.exists():
        return config, errors
    parser = configparser.ConfigParser()
    parser.read(path, encoding="utf-8")
    config.current_milestone = parser.get("gate", "current-milestone", fallback="M0").strip()
    if milestone_number(config.current_milestone) is None:
        errors.append(f"{path.name}: current-milestone '{config.current_milestone}' is not M<number>")
    if parser.has_section("test-roots"):
        for module, roots in parser.items("test-roots"):
            config.extra_roots[module] = roots.split()
    return config, errors


@dataclass
class Module:
    name: str
    path: Path
    spec_ids: Dict[str, str] = field(default_factory=dict)       # id -> milestone ("" if missing)
    requirement_ids: Set[str] = field(default_factory=set)
    partial_requirements: Set[str] = field(default_factory=set)
    plan_statement_ids: Optional[Set[str]] = None
    pending: Dict[str, Tuple[str, int]] = field(default_factory=dict)
    errors: List[str] = field(default_factory=list)

    @property
    def in_scope(self) -> List[str]:
        return sorted(set(self.spec_ids) | self.requirement_ids)


def markdown_lines(path: Path):
    """Lines outside fenced code blocks, with line numbers."""
    fenced = False
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if line.lstrip().startswith(("```", "~~~")):
            fenced = not fenced
            continue
        if not fenced:
            yield number, line


def read_spec(path: Path, rel: str, module: Module) -> None:
    current = None
    for number, line in markdown_lines(path):
        heading = HEADING.match(line)
        if heading:
            current = None
            token = heading.group(2)
            if not ID_LIKE_HEADING_TOKEN.match(token):
                continue
            statement = STATEMENT_TOKEN.match(token)
            if not statement or len(heading.group(1)) < 2:
                module.errors.append(f"{rel}:{number}: heading '{line.strip()}' starts like a statement id but is "
                                     "not '## <ID>' or deeper with three or more digits, for example '### RLE-001'")
                continue
            current = statement.group(1)
            if current in module.spec_ids:
                module.errors.append(f"{rel}:{number}: {current} is defined twice")
                current = None
                continue
            module.spec_ids[current] = ""
            continue
        milestone = SPEC_MILESTONE.match(line)
        if milestone and current and not module.spec_ids[current]:
            module.spec_ids[current] = milestone.group(1)
    for spec_id, milestone in sorted(module.spec_ids.items()):
        if not milestone:
            module.errors.append(f"{rel}: {spec_id} has no '- **Milestone:** M<n>' line")


def read_test_plan(path: Path, module: Module) -> None:
    section = None
    coverage_column = None
    for _, line in markdown_lines(path):
        if line.startswith("## "):
            title = line[3:].strip().lower()
            section = ("requirements" if title.startswith("requirement-level tests")
                       else "statements" if title.startswith("specification coverage") else None)
            coverage_column = None
            if section == "statements" and module.plan_statement_ids is None:
                module.plan_statement_ids = set()
            continue
        if not line.startswith("|"):
            continue
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        if section == "requirements":
            lowered = [c.lower() for c in cells]
            if coverage_column is None and "coverage" in lowered:
                coverage_column = lowered.index("coverage")
                continue
            ids = TR_ID.findall(cells[0])
            module.requirement_ids.update(ids)
            if coverage_column is not None and coverage_column < len(cells) and "partial" in lowered[coverage_column]:
                module.partial_requirements.update(ids)
        elif section == "statements":
            match = TABLE_STATEMENT_ID.match(line)
            if match:
                module.plan_statement_ids.add(match.group(1))


def read_pending(path: Path, rel: str, module: Module) -> None:
    if not path.exists():
        return
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        match = PENDING_LINE.match(line)
        if not match:
            module.errors.append(f"{rel}:{number}: cannot read '{raw.strip()}'; expected '<ID> <milestone>', "
                                 "for example 'RLE-001 M1'")
            continue
        spec_id, milestone = match.groups()
        if spec_id in module.pending:
            module.errors.append(f"{rel}:{number}: {spec_id} is listed twice")
        module.pending[spec_id] = (milestone, number)


def discover_modules(root: Path) -> List[Module]:
    modules = []
    for spec in sorted(root.glob("*/docs/SPEC.md")):
        module_dir = spec.parent.parent
        module = Module(name=module_dir.name, path=module_dir)
        read_spec(spec, spec.relative_to(root).as_posix(), module)
        test_plan = module_dir / "docs" / "TEST-PLAN.md"
        if test_plan.exists():
            read_test_plan(test_plan, module)
        modules.append(module)
    return modules


SKIP_DIRS = {".git", "node_modules", "target", "dist", "build", ".idea", ".venv", "coverage"}


def walk_files(base: Path):
    for directory, subdirs, files in os.walk(base):
        subdirs[:] = [d for d in subdirs if d not in SKIP_DIRS]
        for file_name in files:
            yield Path(directory) / file_name


def collect_test_names(root: Path, roots: List[Path]) -> Dict[str, List[str]]:
    """Maps each counted test name under the given roots to the files that declare it."""
    files = sorted({f for base in roots if base.exists() for f in walk_files(base)})
    matchers = [playwright_matcher(f) for f in files if PLAYWRIGHT_CONFIG.search(f.name)]
    names: Dict[str, List[str]] = {}
    for path in files:
        relative = path.relative_to(root).as_posix()
        if path.suffix == ".java" and "/src/test/java/" in "/" + relative:
            reader = java_test_names
        elif TS_TEST_FILE.search(path.name) or (
                re.search(TS_EXTENSION, path.name) and any(m.matches(path) for m in matchers)):
            reader = ts_test_names
        else:
            continue
        for name in reader(path.read_text(encoding="utf-8", errors="replace")):
            names.setdefault(name, []).append(relative)
    return names


def tests_for(spec_id: str, test_names: Dict[str, List[str]]) -> List[str]:
    prefix = to_test_prefix(spec_id)
    return sorted(n for n in test_names if n == prefix or n.startswith(prefix + "_"))


# ---------------------------------------------------------------------------
# Gate

@dataclass
class ModuleResult:
    name: str
    in_scope: int
    statements: int
    requirements: int
    with_tests: int
    partial: List[str]
    pending: int


@dataclass
class Result:
    config: Config = field(default_factory=Config)
    errors: List[str] = field(default_factory=list)
    warnings: List[str] = field(default_factory=list)
    modules: Dict[str, ModuleResult] = field(default_factory=dict)
    test_names: Dict[str, Dict[str, List[str]]] = field(default_factory=dict)

    @property
    def passed(self) -> bool:
        return not self.errors


def check(root: Path, config_path: Optional[Path] = None) -> Result:
    result = Result()
    config, config_errors = read_config(config_path)
    result.config = config
    result.errors.extend(config_errors)
    current = milestone_number(config.current_milestone)
    modules = discover_modules(root)
    if not modules:
        result.errors.append(f"no */docs/SPEC.md under {root}")
        return result

    for module in modules:
        spec_rel = (module.path / "docs" / "SPEC.md").relative_to(root).as_posix()
        plan_rel = (module.path / "docs" / "TEST-PLAN.md").relative_to(root).as_posix()
        pending_rel = (module.path / "docs" / "spec-pending.txt").relative_to(root).as_posix()
        read_pending(module.path / "docs" / "spec-pending.txt", pending_rel, module)
        result.errors.extend(module.errors)

        if module.plan_statement_ids is not None and module.plan_statement_ids != set(module.spec_ids):
            missing = sorted(set(module.spec_ids) - module.plan_statement_ids)
            extra = sorted(module.plan_statement_ids - set(module.spec_ids))
            result.errors.append(f"{plan_rel}: the Specification coverage table and {spec_rel} differ"
                                 + (f"; missing from the table: {', '.join(missing)}" if missing else "")
                                 + (f"; not in SPEC.md: {', '.join(extra)}" if extra else ""))

        roots = [module.path] + [root / r for r in config.extra_roots.get(module.name, [])]
        roots_text = ", ".join(r.relative_to(root).as_posix() for r in roots)
        test_names = collect_test_names(root, roots)
        result.test_names[module.name] = test_names
        with_tests = pending_count = 0
        for spec_id in module.in_scope:
            tests = tests_for(spec_id, test_names)
            if spec_id in module.pending:
                pending_count += 1
                if tests:
                    result.warnings.append(f"{pending_rel}: {spec_id} is on the allow-list but has a test "
                                           f"({tests[0]}); remove its line")
            elif tests:
                with_tests += 1
            else:
                source = spec_rel if spec_id in module.spec_ids else plan_rel
                result.errors.append(f"{source}: {spec_id} has no running test whose name starts with "
                                     f"{to_test_prefix(spec_id)}_ under {roots_text} and is not on the allow-list "
                                     f"{pending_rel} (disabled, skipped, todo and commented-out tests do not count)")

        for spec_id, (milestone, line) in sorted(module.pending.items()):
            where = f"{pending_rel}:{line}"
            if spec_id not in module.spec_ids and spec_id not in module.requirement_ids:
                result.errors.append(f"{where}: {spec_id} is not a statement in {spec_rel} nor a requirement "
                                     "in the TEST-PLAN; remove the line")
                continue
            expected = module.spec_ids.get(spec_id)
            if expected and expected != milestone:
                result.errors.append(f"{where}: {spec_id} is listed for {milestone} but {spec_rel} says {expected}")
            number = milestone_number(milestone)
            if current is not None and number is not None and number <= current:
                result.errors.append(f"{where}: {spec_id} is pending for {milestone}, but the current milestone is "
                                     f"{config.current_milestone}; add its test, or move the statement to a later "
                                     "milestone in SPEC.md")

        result.modules[module.name] = ModuleResult(
            module.name, len(module.in_scope), len(module.spec_ids), len(module.requirement_ids),
            with_tests, sorted(module.partial_requirements - set(module.pending)), pending_count)
    return result


def annotate(level: str, message: str) -> str:
    if os.environ.get("GITHUB_ACTIONS") != "true":
        return f"{level.upper()}: {message}"
    file = re.match(r"^([\w./-]+\.(?:md|txt|ini))(?::(\d+))?:", message)
    if not file:
        return f"::{level}::{message}"
    line = f",line={file.group(2)}" if file.group(2) else ""
    return f"::{level} file={file.group(1)}{line}::{message}"


def report(result: Result, out=sys.stdout) -> None:
    for message in result.errors:
        print(annotate("error", message), file=out)
    for message in result.warnings:
        print(annotate("warning", message), file=out)
    print(f"Current milestone: {result.config.current_milestone}", file=out)
    for m in result.modules.values():
        partial = f" ({len(m.partial)} partial per the TEST-PLAN: {', '.join(m.partial)})" if m.partial else ""
        print(f"{m.name}: {m.in_scope} ids in scope ({m.statements} statements, {m.requirements} requirements), "
              f"{m.with_tests} with at least one named test{partial}, {m.pending} pending", file=out)
    if result.errors:
        print(f"Spec-to-test gate FAILED: {len(result.errors)} problem(s), {len(result.warnings)} warning(s). "
              "Name a running test after the id (see wiki/Contributing.md) or list the id with its milestone "
              "in the module's docs/spec-pending.txt.", file=out)
    else:
        print(f"Spec-to-test gate passed: {len(result.warnings)} warning(s).", file=out)


def main(argv: Optional[List[str]] = None) -> int:
    default_root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description="Spec-to-test gate (TR-QA-01).")
    parser.add_argument("--root", type=Path, default=default_root,
                        help="repository root (default: the parent of tools/)")
    parser.add_argument("--config", type=Path, default=None,
                        help="gate settings (default: <root>/tools/spec-coverage.ini)")
    args = parser.parse_args(argv)
    root = args.root.resolve()
    result = check(root, args.config or root / "tools" / "spec-coverage.ini")
    report(result)
    return 0 if result.passed else 1


if __name__ == "__main__":
    sys.exit(main())
