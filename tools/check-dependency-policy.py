#!/usr/bin/env python3
"""Policy checks around the dependency gates (TR-LIC-01, TR-LIC-02, TR-SEC-06).

Subcommands (standard library only, Python 3.9 or later; run from any directory):

  poms            Only the root pom.xml may configure the gates, and only examples/pom.xml may
                  set maven.deploy.skip (the one "never ships" switch). Fails on any other POM
                  that sets a skip, scope or policy setting of the licence gate, the SBOM or
                  the enforcer.
  grype-config    Lints .grype.yaml: known keys only, the high threshold, database age checks on,
                  and every suppression narrowed to one vulnerability, package and version, with
                  a reason that carries an expiry date ("expires YYYY-MM-DD") not yet passed.
                  .grype.yaml is written as JSON (valid YAML) with '#' comment lines, so it can be
                  read without a YAML library.
  jar-notices     Every engine jar named in target/bom.json carries META-INF/LICENSE,
                  META-INF/COMMERCIAL-LICENSE.md and the licence manifest entries.
  add-processors  Adds the annotation processors of target/effective-pom.xml to
                  target/bom-build.json, so the build SBOM scan covers them.

Exit status: 0 when the check passes, 1 when it fails, 2 on a usage error.
"""

from __future__ import annotations

import datetime
import json
import re
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path
from typing import List

ROOT = Path(__file__).resolve().parent.parent
POM_NS = {"m": "http://maven.apache.org/POM/4.0.0"}

# Settings that switch a gate off or change what it sees. Only the root pom.xml may set them.
GATE_SETTINGS = [
    "maven.deploy.skip", "skipAddThirdParty", "license.skip", "license.skipAddThirdParty",
    "excludedScopes", "excludedGroups", "excludedArtifacts", "includedLicenses", "licenseMerges",
    "licenseMergesUrl", "failOnBlacklist", "failOnMissing", "cyclonedx.skip", "skipNotDeployed",
    "includeProvidedScope", "includeTestScope", "excludeArtifactId", "enforcer.skip", "bpm.root",
    "bpm.licence-policy",
]
# The single exception: examples/pom.xml marks the example hosts as never shipped.
ALLOWED_OUTSIDE_ROOT = {"examples/pom.xml": {"maven.deploy.skip"}}


def fail(messages: List[str], ok: str) -> int:
    for message in messages:
        print(f"ERROR: {message}")
    if messages:
        return 1
    print(ok)
    return 0


def check_poms() -> int:
    errors = []
    for pom in sorted(ROOT.rglob("pom.xml")):
        relative = pom.relative_to(ROOT).as_posix()
        if "/target/" in f"/{relative}" or relative == "pom.xml":
            continue
        text = pom.read_text(encoding="utf-8")
        text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
        allowed = ALLOWED_OUTSIDE_ROOT.get(relative, set())
        for setting in GATE_SETTINGS:
            if setting in allowed:
                continue
            if re.search(rf"<{re.escape(setting)}>", text):
                errors.append(f"{relative} sets <{setting}>. Gate settings live in the root pom.xml only; "
                              "a module that never ships goes under examples/ (see wiki/Contributing.md)")
    return fail(errors, "POM policy passed: gate settings only in pom.xml; maven.deploy.skip only in examples/pom.xml.")


TOP_KEYS = {"fail-on-severity", "only-fixed", "show-suppressed", "output", "db", "ignore"}
IGNORE_KEYS = {"vulnerability", "package", "reason"}
PACKAGE_KEYS = {"name", "version"}
EXPIRY = re.compile(r"\bexpires (\d{4}-\d{2}-\d{2})\b")


def read_json_yaml(path: Path):
    lines = [line for line in path.read_text(encoding="utf-8").splitlines() if not line.lstrip().startswith("#")]
    return json.loads("\n".join(lines))


def check_grype_config(path: Path, today: datetime.date) -> int:
    try:
        config = read_json_yaml(path)
    except (OSError, ValueError) as error:
        return fail([f"{path.name}: not readable as JSON with '#' comment lines ({error})"], "")
    errors = []
    for key in sorted(set(config) - TOP_KEYS):
        errors.append(f"{path.name}: key '{key}' is not allowed (allowed: {', '.join(sorted(TOP_KEYS))})")
    if config.get("fail-on-severity") != "high":
        errors.append(f"{path.name}: fail-on-severity must be 'high' (TR-SEC-06)")
    if config.get("only-fixed") is not False:
        errors.append(f"{path.name}: only-fixed must be false; findings without a fix still fail the gate")
    if config.get("show-suppressed") is not True:
        errors.append(f"{path.name}: show-suppressed must be true, so suppressed findings stay in the log")
    db = config.get("db", {})
    if db.get("validate-age") is not True:
        errors.append(f"{path.name}: db.validate-age must be true")
    age = re.fullmatch(r"(\d+)h", str(db.get("max-allowed-built-age", "")))
    if not age or int(age.group(1)) > 120:
        errors.append(f"{path.name}: db.max-allowed-built-age must be set, at most 120h")
    ignores = config.get("ignore", [])
    if not isinstance(ignores, list):
        errors.append(f"{path.name}: ignore must be a list")
        ignores = []
    for number, rule in enumerate(ignores, start=1):
        where = f"{path.name}: ignore entry {number}"
        if not isinstance(rule, dict):
            errors.append(f"{where} is not a map")
            continue
        for key in sorted(set(rule) - IGNORE_KEYS):
            errors.append(f"{where}: key '{key}' is not allowed; an exception names one vulnerability, "
                          "package and version")
        package = rule.get("package") if isinstance(rule.get("package"), dict) else {}
        for key in sorted(set(package) - PACKAGE_KEYS):
            errors.append(f"{where}: package key '{key}' is not allowed (only name and version)")
        for label, value in (("vulnerability", rule.get("vulnerability")), ("package.name", package.get("name")),
                             ("package.version", package.get("version")), ("reason", rule.get("reason"))):
            if not isinstance(value, str) or not value.strip() or "*" in value:
                errors.append(f"{where}: {label} is required and must be exact (no wildcard)")
        expiry = EXPIRY.search(str(rule.get("reason", "")))
        if not expiry:
            errors.append(f"{where}: reason must end with 'expires YYYY-MM-DD'")
        else:
            try:
                date = datetime.date.fromisoformat(expiry.group(1))
            except ValueError:
                errors.append(f"{where}: expiry {expiry.group(1)} is not a date")
            else:
                if date < today:
                    errors.append(f"{where}: the exception for {rule.get('vulnerability')} expired on {date}; "
                                  "fix the dependency or renew the review")
    return fail(errors, f"Grype config passed: {len(ignores)} reviewed exception(s).")


REQUIRED_ENTRIES = ["META-INF/LICENSE", "META-INF/COMMERCIAL-LICENSE.md"]
REQUIRED_MANIFEST = ["Bundle-License:", "X-HORUS-License:", "X-HORUS-Commercial-License:"]


def check_jar_notices(bom: Path) -> int:
    if not bom.exists():
        return fail([f"{bom} not found; run mvn -B verify first"], "")
    components = json.loads(bom.read_text(encoding="utf-8")).get("components", [])
    jars = [c for c in components if c.get("group") == "com.horus.bpm" and "type=jar" in c.get("purl", "")]
    errors, checked = [], 0
    for component in jars:
        name = f"{component['name']}-{component['version']}.jar"
        found = [p for p in ROOT.rglob(name) if p.parent.name == "target"]
        if not found:
            errors.append(f"{name} is in the SBOM but was not built")
            continue
        with zipfile.ZipFile(found[0]) as jar:
            entries = set(jar.namelist())
            manifest = jar.read("META-INF/MANIFEST.MF").decode("utf-8", "replace").replace("\r\n ", "").replace("\n ", "")
        for entry in REQUIRED_ENTRIES:
            if entry not in entries:
                errors.append(f"{found[0].relative_to(ROOT)} has no {entry} (TR-LIC-02)")
        for header in REQUIRED_MANIFEST:
            if header not in manifest:
                errors.append(f"{found[0].relative_to(ROOT)} manifest has no {header[:-1]} (TR-LIC-02)")
        checked += 1
        print(f"{found[0].relative_to(ROOT)}: licence files and manifest entries present")
    if not jars:
        errors.append(f"{bom} names no engine jar")
    return fail(errors, f"Licence notices passed: {checked} jar(s) (TR-LIC-02).")


def add_processors(effective_pom: Path, bom: Path) -> int:
    if not effective_pom.exists() or not bom.exists():
        return fail([f"{effective_pom} or {bom} not found"], "")
    tree = ET.parse(effective_pom)
    found = set()
    for path in tree.getroot().iter("{%s}path" % POM_NS["m"]):
        values = {child.tag.split("}")[-1]: (child.text or "").strip() for child in path}
        if values.get("groupId") and values.get("artifactId") and values.get("version"):
            found.add((values["groupId"], values["artifactId"], values["version"]))
    data = json.loads(bom.read_text(encoding="utf-8"))
    components = data.setdefault("components", [])
    present = {c.get("purl", "").split("?")[0] for c in components}
    added = 0
    for group, artifact, version in sorted(found):
        purl = f"pkg:maven/{group}/{artifact}@{version}"
        if purl in present:
            continue
        components.append({"type": "library", "group": group, "name": artifact, "version": version,
                           "purl": f"{purl}?type=jar", "scope": "excluded",
                           "description": "Annotation processor (build only)"})
        added += 1
        print(f"Added annotation processor {group}:{artifact}:{version}")
    bom.write_text(json.dumps(data, indent=2), encoding="utf-8")
    print(f"{bom.name}: {added} annotation processor(s) added, {len(components)} components.")
    return 0


def main(argv: List[str]) -> int:
    if not argv:
        print(__doc__)
        return 2
    command, rest = argv[0], argv[1:]
    if command == "poms":
        return check_poms()
    if command == "grype-config":
        return check_grype_config(Path(rest[0]) if rest else ROOT / ".grype.yaml", datetime.date.today())
    if command == "jar-notices":
        return check_jar_notices(Path(rest[0]) if rest else ROOT / "target" / "bom.json")
    if command == "add-processors":
        return add_processors(ROOT / "target" / "effective-pom.xml", ROOT / "target" / "bom-build.json")
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
