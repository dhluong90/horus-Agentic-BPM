#!/usr/bin/env python3
"""Release bill of materials and cockpit bundle checks (TR-LIC-01, TR-SEC-06, TR-UI-06).

Subcommands (standard library only, Python 3.9 or later):

  merge MAVEN NPM OUT     Writes OUT: the Maven SBOM (target/bom.json) plus every component of the npm
                          SBOM of the cockpit's production packages. npm components carry the property
                          horus:shippedIn=cockpit-spring-boot-starter, and the starter depends on them in
                          the dependency graph, because they ship inside its jar.
  npm-licences NPM        Fails, naming the package, when a component of the npm SBOM has no licence or a
                          licence outside tools/licence-policy/allowed-licences.txt. An SPDX expression
                          passes when every alternative of an OR, or every part of an AND, is handled:
                          OR needs one allowed alternative, AND needs all parts allowed.
  cockpit-jar DIST JAR    Fails unless JAR contains META-INF/horus-cockpit/index.html and every file of the
                          freshly built DIST directory byte for byte (cockpit-hosts.json excepted, as in the
                          starter's resource rule), and nothing else under META-INF/horus-cockpit/.

Exit status: 0 when the check passes, 1 when it fails, 2 on a usage error.
"""

from __future__ import annotations

import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path
from typing import Dict, List, Set

ROOT = Path(__file__).resolve().parents[2]
ALLOWED_FILE = ROOT / "tools" / "licence-policy" / "allowed-licences.txt"
STARTER = "cockpit-spring-boot-starter"
BUNDLE_PREFIX = "META-INF/horus-cockpit/"
NOT_PACKAGED = {"cockpit-hosts.json"}


def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def merge(maven_path: Path, npm_path: Path, out_path: Path) -> int:
    maven, npm = load(maven_path), load(npm_path)
    components = maven.setdefault("components", [])
    present = {c.get("purl") for c in components if c.get("purl")}
    starter = next((c for c in components if c.get("name") == STARTER), None)
    added: List[str] = []
    for component in npm.get("components", []):
        if component.get("purl") in present:
            continue
        component = dict(component)
        component.setdefault("properties", []).append({"name": "horus:shippedIn", "value": STARTER})
        components.append(component)
        present.add(component.get("purl"))
        added.append(component.get("bom-ref") or component.get("purl"))

    dependencies = maven.setdefault("dependencies", [])
    npm_refs = {c.get("bom-ref") for c in npm.get("components", [])}
    for entry in npm.get("dependencies", []):
        if entry.get("ref") in npm_refs:
            dependencies.append(entry)
    if starter is not None:
        root_ref = npm.get("metadata", {}).get("component", {}).get("bom-ref")
        direct = next((d.get("dependsOn", []) for d in npm.get("dependencies", []) if d.get("ref") == root_ref), None)
        if direct is None:
            direct = sorted(r for r in npm_refs if r)
        entry = next((d for d in dependencies if d.get("ref") == starter.get("bom-ref")), None)
        if entry is None:
            entry = {"ref": starter.get("bom-ref"), "dependsOn": []}
            dependencies.append(entry)
        entry["dependsOn"] = sorted(set(entry.get("dependsOn", [])) | set(direct))

    maven.setdefault("metadata", {}).setdefault("properties", []).append(
        {"name": "horus:sbom", "value": "release: Maven runtime and provided scopes plus shipped cockpit npm packages"})
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(maven, indent=2), encoding="utf-8")
    print(f"{out_path}: {len(components)} components, {len(added)} npm package(s) shipped in {STARTER}"
          + ("" if starter else f" ({STARTER} not in the Maven SBOM; packages listed without a parent)"))
    return 0


def allowed_licences() -> Set[str]:
    return {line.strip() for line in ALLOWED_FILE.read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.startswith("#")}


def expression_allowed(expression: str, allowed: Set[str]) -> bool:
    text = expression.strip()
    while text.startswith("(") and text.endswith(")") and balanced(text[1:-1]):
        text = text[1:-1].strip()
    for operator, combine in ((" OR ", any), (" AND ", all)):
        parts = split_top(text, operator)
        if len(parts) > 1:
            return combine(expression_allowed(part, allowed) for part in parts)
    return text in allowed


def balanced(text: str) -> bool:
    depth = 0
    for ch in text:
        depth += {"(": 1, ")": -1}.get(ch, 0)
        if depth < 0:
            return False
    return depth == 0


def split_top(text: str, operator: str) -> List[str]:
    parts, depth, start, i = [], 0, 0, 0
    while i < len(text):
        ch = text[i]
        depth += {"(": 1, ")": -1}.get(ch, 0)
        if depth == 0 and text.startswith(operator, i):
            parts.append(text[start:i])
            i += len(operator)
            start = i
            continue
        i += 1
    parts.append(text[start:])
    return [p for p in parts if p.strip()]


def component_licences(component: dict) -> List[str]:
    found = []
    for entry in component.get("licenses", []) or []:
        if "expression" in entry:
            found.append(entry["expression"])
        elif "license" in entry:
            licence = entry["license"]
            found.append(licence.get("id") or licence.get("name") or "")
    return [f for f in found if f]


def npm_licences(npm_path: Path) -> int:
    allowed = allowed_licences()
    errors, checked = [], 0
    for component in load(npm_path).get("components", []):
        name = f"{component.get('group') + '/' if component.get('group') else ''}{component.get('name')}"
        label = f"{name}@{component.get('version')}"
        licences = component_licences(component)
        checked += 1
        if not licences:
            errors.append(f"{label}: no licence declared")
            continue
        # Several entries mean the package declares several licences at once; each must be allowed.
        bad = [l for l in licences if not expression_allowed(l, allowed)]
        if bad:
            errors.append(f"{label}: licence {' / '.join(bad)} is not allowed")
    for message in errors:
        print(f"ERROR: {message} (TR-LIC-01; allowed: {', '.join(sorted(allowed))})")
    if errors:
        return 1
    print(f"npm licence check passed: {checked} shipped package(s), all under allowed licences (TR-LIC-01).")
    return 0


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def cockpit_jar(dist: Path, jar_path: Path) -> int:
    errors = []
    expected: Dict[str, str] = {}
    for file in sorted(p for p in dist.rglob("*") if p.is_file()):
        relative = file.relative_to(dist).as_posix()
        if relative in NOT_PACKAGED:
            continue
        expected[BUNDLE_PREFIX + relative] = sha256(file.read_bytes())
    if BUNDLE_PREFIX + "index.html" not in expected:
        errors.append(f"{dist}/index.html is missing: the bundle was not built")
    with zipfile.ZipFile(jar_path) as jar:
        packaged = {n: sha256(jar.read(n)) for n in jar.namelist()
                    if n.startswith(BUNDLE_PREFIX) and not n.endswith("/")}
    for name, digest in sorted(expected.items()):
        if name not in packaged:
            errors.append(f"{jar_path.name} lacks {name}")
        elif packaged[name] != digest:
            errors.append(f"{jar_path.name} has a different {name} than the fresh build")
    for name in sorted(set(packaged) - set(expected)):
        errors.append(f"{jar_path.name} contains {name}, which the fresh build did not produce (stale bundle)")
    for message in errors:
        print(f"ERROR: {message}")
    if errors:
        return 1
    index = expected.get(BUNDLE_PREFIX + "index.html", "")
    print(f"Cockpit bundle check passed: {jar_path.name} carries the {len(expected)} files of the fresh build "
          f"(index.html sha256 {index[:16]}...).")
    return 0


def main(argv: List[str]) -> int:
    if len(argv) >= 1 and argv[0] == "merge" and len(argv) == 4:
        return merge(Path(argv[1]), Path(argv[2]), Path(argv[3]))
    if len(argv) == 2 and argv[0] == "npm-licences":
        return npm_licences(Path(argv[1]))
    if len(argv) == 3 and argv[0] == "cockpit-jar":
        return cockpit_jar(Path(argv[1]), Path(argv[2]))
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
