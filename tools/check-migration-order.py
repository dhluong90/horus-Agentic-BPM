#!/usr/bin/env python3
"""Refuses engine database migrations that would break a database already migrated from main (#296).

Usage:   python3 tools/check-migration-order.py [--base REF] [--head REF]
           --base  the branch the change merges into, as fetched at run time (default origin/main).
                   On a push to main, the commit before the push.
           --head  the change (default HEAD).
Why:     the engines run Flyway with out-of-order migrations off. A migration numbered at or below one
         an adopter has already applied stops that host from starting.
What:    per engine (rule-engine/, process-engine/), the files V<version>__<description>.sql under a
         migration/ folder. Example host migrations (examples/) belong to the host and are not read.
         The change is the diff from the merge base of BASE and HEAD to HEAD, so a branch that is behind
         BASE is not blamed for migrations it has not merged yet. It fails when:
           - an added migration's version is at or below the highest version of that engine on BASE;
           - two migrations of one engine share a version, on the branch or against BASE;
           - an added migration uses a retired version (tools/migration-retired-versions.txt);
           - a migration on BASE is edited, deleted, or moved with different content or file name.
         One exception until the 1.0 release (BASELINE_OPEN, #479): the V1 baseline of an engine, V1__baseline.sql, may
         be edited in place, because 1.0 is the first supported schema and no database has to survive it. It still
         may not be deleted, renamed or moved. A change that adds or edits an engine's V1__baseline.sql is a squash of that
         engine: the other released migrations of that engine may then be deleted (they are folded into the baseline),
         and the baseline may take number 1 although higher ones were on BASE. Editing another released migration is
         still refused. The 1.0 release commit sets BASELINE_OPEN to False.
Exit:    0 when every check passes, 1 when one fails, 2 on a usage or git error.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

ENGINES = ("rule-engine", "process-engine")
# rule-engine/<any>/migration/V12__x.sql or V1_1__x.sql; nothing under examples/ starts with an engine folder.
MIGRATION = re.compile(r"^(?P<engine>rule-engine|process-engine)/(?:.+/)?migration/(?P<file>V(?P<version>\d+(?:[._]\d+)*)__[^/]+\.sql)$")
RETIRED_FILE = Path(__file__).with_name("migration-retired-versions.txt")
# Until the 1.0 release the V1 baseline of each engine may be edited in place (#479). Set to False when 1.0 is cut.
BASELINE_OPEN = True
BASELINE_FILE = "V1__baseline.sql"


@dataclass(frozen=True)
class Migration:
    path: str
    engine: str
    file: str
    version: tuple[int, ...]

    @property
    def label(self) -> str:
        return "V" + ".".join(str(part) for part in self.version)


def parse(path: str) -> Migration | None:
    match = MIGRATION.match(path)
    if not match:
        return None
    version = tuple(int(part) for part in re.split(r"[._]", match["version"]))
    return Migration(path, match["engine"], match["file"], version)


def is_baseline(m: Migration) -> bool:
    return m.file == BASELINE_FILE and m.version == (1,)


def label(version: tuple[int, ...]) -> str:
    return "V" + ".".join(str(part) for part in version)


def read_retired(path: Path) -> dict[str, dict[tuple[int, ...], str]]:
    """Lines "<engine> V<version> <reason>"; # starts a comment."""
    retired: dict[str, dict[tuple[int, ...], str]] = {engine: {} for engine in ENGINES}
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split(None, 2)
        if len(parts) < 3 or parts[0] not in ENGINES or not re.fullmatch(r"V\d+(?:[._]\d+)*", parts[1]):
            raise ValueError(f"{path.name}:{number}: expected '<rule-engine|process-engine> V<n> <reason>', got '{raw}'")
        retired[parts[0]][tuple(int(p) for p in re.split(r"[._]", parts[1][1:]))] = parts[2]
    return retired


def git(*args: str) -> str:
    result = subprocess.run(["git", *args], capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)}: {result.stderr.strip()}")
    return result.stdout


def tree(ref: str) -> dict[str, str]:
    """Engine migration paths at REF, mapped to their blob ids (equal ids mean equal bytes)."""
    files: dict[str, str] = {}
    for line in git("ls-tree", "-r", "--full-tree", ref, "--", *ENGINES).splitlines():
        meta, path = line.split("\t", 1)
        if parse(path):
            files[path] = meta.split()[2]
    return files


def next_free(highest: tuple[int, ...] | None, retired: dict[tuple[int, ...], str], taken: set[tuple[int, ...]]) -> str:
    candidate = (highest[0] + 1 if highest else 1,)
    while candidate in retired or candidate in taken:
        candidate = (candidate[0] + 1,)
    return label(candidate)


def baseline_edits(merge_base: dict[str, str], head: dict[str, str], baseline_open: bool = BASELINE_OPEN) -> list[str]:
    """V1 baselines edited in place (bytes changed, file kept), which BASELINE_OPEN allows until 1.0."""
    if not baseline_open:
        return []
    return sorted(path for path in head if path in merge_base and head[path] != merge_base[path] and is_baseline(parse(path)))


def check(base: dict[str, str], merge_base: dict[str, str], head: dict[str, str],
          retired: dict[str, dict[tuple[int, ...], str]], baseline_open: bool = BASELINE_OPEN) -> list[str]:
    """Each argument maps engine migration paths to blob ids. Returns the failures, one message each."""
    errors: list[str] = []
    added = sorted(path for path in head if path not in merge_base)
    changed = sorted(path for path in merge_base if path not in head or head[path] != merge_base[path])
    if not added and not changed:
        return errors

    # Engines squashed by this change: the V1 baseline is added or its bytes changed (only while the baseline is open).
    squashed: set[str] = set()
    if baseline_open:
        for path in head:
            m = parse(path)
            if is_baseline(m) and (path not in merge_base or head[path] != merge_base[path]):
                squashed.add(m.engine)

    base_by_engine: dict[str, list[Migration]] = {engine: [] for engine in ENGINES}
    for path in base:
        migration = parse(path)
        base_by_engine[migration.engine].append(migration)
    highest = {engine: max((m.version for m in found), default=None) for engine, found in base_by_engine.items()}

    def context(m: Migration) -> str:
        top = highest[m.engine]
        taken = {x.version for x in base_by_engine[m.engine]}
        return (f"highest {m.engine} version on main: {label(top) if top else 'none'}; "
                f"next free number: {next_free(top, retired[m.engine], taken)}")

    # Edited, deleted or moved: a migration on main whose bytes or file name are not on the branch any more.
    for path in changed:
        if path not in base:
            continue  # added on the branch's own history and then changed again; judged as an added file below
        old = parse(path)
        moved_intact = any(parse(p) and parse(p).file == old.file and head[p] == base[path] for p in added)
        if path in head:
            if baseline_open and is_baseline(old):
                continue  # the open V1 baseline may be edited until 1.0
            errors.append(f"{path}: {old.label} is on main and this change edits it. Released migrations never change; "
                          f"add a new migration instead ({context(old)}).")
        elif old.engine in squashed:
            continue  # folded into the engine's new V1 baseline
        elif not moved_intact:
            errors.append(f"{path}: {old.label} is on main and this change deletes it or moves it with other content "
                          f"or another file name. Released migrations never change.")

    base_blobs = set(base.items())
    for path in added:
        m = parse(path)
        if any(path_b != path and blob == head[path] and parse(path_b).file == m.file for path_b, blob in base_blobs):
            continue  # a migration on main moved with identical bytes and file name
        if m.engine in squashed and is_baseline(m):
            continue  # the squashed baseline takes number 1
        if m.version in retired[m.engine]:
            errors.append(f"{path}: {m.label} is a retired {m.engine} version ({retired[m.engine][m.version]}); "
                          f"{context(m)}.")
            continue
        same = [b for b in base_by_engine[m.engine] if b.version == m.version and b.path != path]
        if same:
            continue  # reported as a duplicate below
        if highest[m.engine] is not None and m.version <= highest[m.engine]:
            errors.append(f"{path}: {m.label} is at or below the highest {m.engine} version on main, "
                          f"{label(highest[m.engine])}; next free number: "
                          f"{next_free(highest[m.engine], retired[m.engine], {b.version for b in base_by_engine[m.engine]})}. "
                          f"Flyway refuses an older version once a newer one is applied.")

    # Duplicates: every version of an engine on main and on the branch together, where an added file takes part.
    # A migration the change deletes is reported above and takes no part; one on main that the branch has
    # not merged yet does.
    deleted = {path for path in merge_base if path not in head}
    seen: dict[tuple[str, tuple[int, ...]], set[str]] = {}
    for path in (set(base) | set(head)) - deleted:
        m = parse(path)
        seen.setdefault((m.engine, m.version), set()).add(path)
    added_set = set(added)
    for (engine, version), paths in sorted(seen.items()):
        if len(paths) > 1 and paths & added_set:
            names = ", ".join(sorted(paths))
            errors.append(f"{engine} {label(version)} is used by more than one migration: {names}. "
                          f"Each version is used once ({context(parse(sorted(paths & added_set)[0]))}).")
    return errors


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--base", default="origin/main")
    parser.add_argument("--head", default="HEAD")
    parser.add_argument("--retired", type=Path, default=RETIRED_FILE)
    args = parser.parse_args(argv)
    try:
        retired = read_retired(args.retired)
        merge_base = git("merge-base", args.base, args.head).strip()
        base, before, head = tree(args.base), tree(merge_base), tree(args.head)
    except (RuntimeError, ValueError, OSError) as error:
        print(f"check-migration-order: {error}", file=sys.stderr)
        return 2
    errors = check(base, before, head, retired)
    added = sorted(p for p in head if p not in before)
    edited = baseline_edits(before, head)
    if errors:
        for error in errors:
            print(f"::error title=Migration order::{error}")
        print(f"check-migration-order: {len(errors)} problem(s) against {args.base}.")
        return 1
    for path in edited:
        print(f"check-migration-order: baseline edited in place: {path} (allowed while BASELINE_OPEN)")
    if added:
        print("check-migration-order: added migrations in order: " + ", ".join(added))
    elif not edited:
        print("check-migration-order: no engine migration added or changed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
