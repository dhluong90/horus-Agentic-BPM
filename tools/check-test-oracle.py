#!/usr/bin/env python3
"""Checks the test-scope FEEL oracle against its allow-list file (TR-LIC-01, TR-SEC-06, PD-098, #285).

Usage:   python3 tools/check-test-oracle.py tree TREE [--allow-list FILE]
         python3 tools/check-test-oracle.py sbom BOM  [--allow-list FILE]

  tree TREE  TREE is the output of
               mvn -B -ntp -q dependency:tree -Dscope=test -pl rule-engine/rule-engine-core -DoutputFile=TREE
             (TREE an absolute path: Maven resolves a relative one against the module folder).
             The org.camunda.feel:feel-engine node and every artifact under it are compared with the file.
             Fails, naming the artifact, on a version that differs (with the listed and the resolved one),
             an artifact the file does not name, and a listed artifact that is no longer resolved. Every
             licence in the file must be in tools/licence-policy/allowed-licences.txt. Lists what it checked.
  sbom BOM   BOM is a CycloneDX JSON bill of materials (the release build's bom-release.json). Fails when a
             component has the group and name of a listed artifact, at any version: the oracle never ships.

The allow-list file defaults to tools/licence-policy/test-oracle-allow-list.txt. Standard library only.
Exit:    0 the check passed, 1 it failed, 2 usage error or unreadable input.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ALLOW_LIST = ROOT / "tools/licence-policy/test-oracle-allow-list.txt"
ALLOWED_LICENCES = ROOT / "tools/licence-policy/allowed-licences.txt"
ORACLE = "org.camunda.feel:feel-engine"
# A dependency:tree line: tree drawing, then groupId:artifactId:type[:classifier]:version:scope.
TREE_LINE = re.compile(r"^(?P<indent>[| +\\-]*)(?P<coords>[^\s:]+:[^\s:]+:[^\s]+)")


def read_allow_list(path: Path) -> dict[str, tuple[str, str]]:
    """{groupId:artifactId: (version, licence)}; raises ValueError naming a malformed line."""
    entries: dict[str, tuple[str, str]] = {}
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        fields = [field.strip() for field in line.split("|")]
        coords = fields[0].split(":")
        if len(fields) != 3 or len(coords) != 3 or not all(coords) or not fields[1] or not fields[2]:
            raise ValueError(f"{path.name}:{number}: expected 'groupId:artifactId:version | licence | reason', got '{line}'")
        key = f"{coords[0]}:{coords[1]}"
        if key in entries:
            raise ValueError(f"{path.name}:{number}: {key} is listed twice")
        entries[key] = (coords[2], fields[1])
    return entries


def oracle_subtree(tree: str) -> dict[str, str] | None:
    """{groupId:artifactId: version} of the oracle node and its descendants; None when the oracle is absent."""
    found: dict[str, str] | None = None
    depth = -1
    for raw in tree.splitlines():
        line = re.sub(r"^\[[A-Z]+\]\s?", "", raw.rstrip())
        match = TREE_LINE.match(line)
        if not match:
            continue
        level = len(match["indent"]) // 3
        parts = match["coords"].split(":")
        if len(parts) < 5:
            continue  # the module line itself (groupId:artifactId:type:version) or not a dependency
        key, version = f"{parts[0]}:{parts[1]}", parts[-2]
        if found is None:
            if key == ORACLE:
                found, depth = {key: version}, level
        elif level > depth:
            found[key] = version
        else:
            break  # back at the oracle's level or above: its subtree ended
    return found


def tree_errors(resolved: dict[str, str] | None, listed: dict[str, tuple[str, str]], allowed: set[str]) -> list[str]:
    errors: list[str] = []
    for key, (version, licence) in sorted(listed.items()):
        if licence not in allowed:
            errors.append(f"{key}:{version} is listed under {licence}, which is not in allowed-licences.txt")
    if resolved is None:
        return errors + [f"{ORACLE} is not in the resolved test tree; every listed artifact is gone. "
                         f"Remove the file's entries only together with the oracle (PD-098)."]
    for key, version in sorted(resolved.items()):
        if key not in listed:
            errors.append(f"{key}:{version} is in the {ORACLE} test subtree but not in the allow-list file")
        elif listed[key][0] != version:
            errors.append(f"{key} is listed at {listed[key][0]} but resolved at {version}")
    for key, (version, _) in sorted(listed.items()):
        if key not in resolved:
            errors.append(f"{key}:{version} is listed but no longer resolved under {ORACLE}")
    return errors


def sbom_errors(bom: dict, listed: dict[str, tuple[str, str]]) -> list[str]:
    errors: list[str] = []
    seen: set[tuple[str, str | None]] = set()

    def walk(components: list) -> None:
        for component in components or []:
            key = f"{component.get('group', '')}:{component.get('name', '')}"
            if key in listed and (key, component.get("version")) not in seen:
                seen.add((key, component.get("version")))
                errors.append(f"{key}:{component.get('version', '?')} is in the bill of materials, "
                              f"but the allow-list file allows it in test scope only")
            walk(component.get("components", []))

    walk(bom.get("components", []))
    return errors


def main(argv: list[str]) -> int:
    args = argv[1:]
    allow_list = ALLOW_LIST
    if "--allow-list" in args:
        index = args.index("--allow-list")
        if index + 1 >= len(args):
            print(__doc__, file=sys.stderr)
            return 2
        allow_list = Path(args[index + 1])
        del args[index:index + 2]
    if len(args) != 2 or args[0] not in ("tree", "sbom"):
        print(__doc__, file=sys.stderr)
        return 2
    command, target = args
    try:
        listed = read_allow_list(allow_list)
        text = Path(target).read_text(encoding="utf-8")
    except (OSError, ValueError) as error:
        print(f"check-test-oracle: {error}", file=sys.stderr)
        return 2
    if command == "tree":
        allowed = {line.strip() for line in ALLOWED_LICENCES.read_text(encoding="utf-8").splitlines() if line.strip()}
        resolved = oracle_subtree(text)
        errors = tree_errors(resolved, listed, allowed)
        what = f"the {ORACLE} test subtree in {target}"
    else:
        try:
            errors = sbom_errors(json.loads(text), listed)
        except json.JSONDecodeError as error:
            print(f"check-test-oracle: {target} is not JSON: {error}", file=sys.stderr)
            return 2
        what = f"the bill of materials {target}"
    for error in errors:
        print(f"FAIL  {error}")
    if errors:
        advice = (f"Update {allow_list.name} to the resolved tree in a pull request, after checking each licence."
                  if command == "tree" else "Keep the oracle in test scope: no shipped module may depend on it.")
        print(f"check-test-oracle: {len(errors)} problem(s) in {what} (TR-LIC-01, PD-098). {advice}")
        return 1
    if command == "tree":
        for key, (version, licence) in sorted(listed.items()):
            print(f"PASS  {key}:{version} ({licence})")
        print(f"check-test-oracle: {len(listed)} artifacts checked; {what} matches {allow_list.name}.")
    else:
        print(f"check-test-oracle: none of the {len(listed)} test-only artifacts of {allow_list.name} is in {what}.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
