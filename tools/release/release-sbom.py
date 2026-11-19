#!/usr/bin/env python3
"""Release build checks, manifests and bill of materials (TR-LIC-01, TR-SEC-06, TR-UI-06).

Subcommands (standard library only, Python 3.9 or later):

  manifest DIR OUT [--exclude NAME]...
                          Writes OUT: one "sha256  ./path" line per file under DIR, sorted by path, in the
                          format of `shasum -a 256` (`shasum -a 256 -c OUT` checks it from DIR). Files named
                          NAME are left out. Fails on a file name with a line break.
  cockpit-jar MANIFEST JAR
                          Fails unless JAR holds under META-INF/horus-cockpit/ exactly the files of MANIFEST
                          (written by `manifest` right after the cockpit build), byte for byte: index.html
                          present, nothing missing, nothing different, nothing extra (stale).
  bundle-sbom MODULES NPM OUT
                          MODULES is written by cockpit-build.mjs: the node_modules packages and the bundler
                          runtime modules that ended up in the bundle. Fails, naming the package, when a
                          bundled package is not in NPM, the npm SBOM of the production dependencies (a
                          runtime import declared under devDependencies, for example). Writes OUT: the
                          components of NPM that are in the bundle, plus one component per bundler package
                          whose runtime code is in the bundle. Declared packages that are not bundled are
                          listed and left out.
  npm-licences NPM        Fails, naming the package, when a component of NPM has no licence or a licence
                          outside tools/licence-policy/allowed-licences.txt. An SPDX expression passes when
                          every alternative of an OR, or every part of an AND, is handled: OR needs one
                          allowed alternative, AND needs all parts allowed.
  merge MAVEN NPM OUT [--build-info FILE]
                          Writes OUT: the Maven SBOM plus every component of NPM, each carrying
                          horus:shippedIn=cockpit-spring-boot-starter, with the starter depending on them.
                          NPM is "-" for a Java-only release. OUT gets a new serial number and timestamp;
                          both source serial numbers and the BUILD-INFO.json fields go into
                          metadata.properties. Fails when an npm component has no purl.
  build-info OUT --trial true|false [--tag TAG]
                          Writes OUT (BUILD-INFO.json): commit, clean or dirty working tree, tag, trial flag,
                          CI run, and the version, path and SHA-256 of node, npm, java, mvn and python, plus
                          the SHA-256 of the npm lockfiles.
  deploy-plugins EFFECTIVE_POM
                          Fails when any plugin other than maven-deploy-plugin is bound to the deploy phase,
                          any plugin is a build extension (which can replace the deploy goal), or a known
                          publishing plugin is configured: the dry run must never be able to publish.
  tag-version TAG VERSION...
                          TAG is <module>/vX.Y.Z. Fails unless every VERSION is exactly X.Y.Z, so a
                          -SNAPSHOT version always fails.

Exit status: 0 when the check passes, 1 when it fails, 2 on a usage error.
"""

from __future__ import annotations

import datetime
import hashlib
import json
import os
import platform
import re
import shutil
import subprocess
import sys
import uuid
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Set, Tuple

ROOT = Path(__file__).resolve().parents[2]
ALLOWED_FILE = ROOT / "tools" / "licence-policy" / "allowed-licences.txt"
STARTER = "cockpit-spring-boot-starter"
BUNDLE_PREFIX = "META-INF/horus-cockpit/"
POM_NS = "{http://maven.apache.org/POM/4.0.0}"
# Plugins that upload to a remote repository by themselves, whatever phase they are bound to.
PUBLISHING_PLUGINS = {
    "central-publishing-maven-plugin",
    "nexus-staging-maven-plugin",
    "jreleaser-maven-plugin",
    "maven-release-plugin",
    "wagon-maven-plugin",
}


def load(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def fail(errors: Sequence[str]) -> int:
    for message in errors:
        print(f"ERROR: {message}")
    return 1 if errors else 0


# ---------------------------------------------------------------------------------------------- manifest

def manifest_entries(directory: Path, exclude: Set[str]) -> Dict[str, str]:
    entries: Dict[str, str] = {}
    for file in directory.rglob("*"):
        if not file.is_file() or file.name in exclude:
            continue
        relative = file.relative_to(directory).as_posix()
        if "\n" in relative or "\r" in relative:
            raise ValueError(f"file name with a line break: {relative!r}")
        entries[relative] = sha256_file(file)
    return dict(sorted(entries.items()))


def manifest(directory: Path, out: Path, exclude: Set[str]) -> int:
    try:
        entries = manifest_entries(directory, exclude)
    except ValueError as error:
        return fail([str(error)])
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("".join(f"{digest}  ./{path}\n" for path, digest in entries.items()), encoding="utf-8")
    print(f"{out}: SHA-256 of {len(entries)} file(s) under {directory}")
    return 0


def read_manifest(path: Path) -> Dict[str, str]:
    entries = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        digest, _, name = line.partition("  ")
        entries[name[2:] if name.startswith("./") else name] = digest
    return entries


def cockpit_jar(manifest_path: Path, jar_path: Path) -> int:
    expected = {BUNDLE_PREFIX + name: digest for name, digest in read_manifest(manifest_path).items()}
    errors = []
    if BUNDLE_PREFIX + "index.html" not in expected:
        errors.append(f"{manifest_path} has no index.html: the bundle was not built")
    with zipfile.ZipFile(jar_path) as jar:
        packaged = {n: hashlib.sha256(jar.read(n)).hexdigest() for n in jar.namelist()
                    if n.startswith(BUNDLE_PREFIX) and not n.endswith("/")}
    for name, digest in expected.items():
        if name not in packaged:
            errors.append(f"{jar_path.name} lacks {name}")
        elif packaged[name] != digest:
            errors.append(f"{jar_path.name} has a different {name} than the build recorded in {manifest_path.name}")
    for name in sorted(set(packaged) - set(expected)):
        errors.append(f"{jar_path.name} contains {name}, which the fresh build did not produce (stale bundle)")
    if errors:
        return fail(errors)
    index = expected.get(BUNDLE_PREFIX + "index.html", "")
    print(f"Cockpit bundle check passed: {jar_path.name} carries the {len(expected)} files recorded right after "
          f"the build (index.html sha256 {index[:16]}...).")
    return 0


# ------------------------------------------------------------------------------------------- npm SBOM

def full_name(component: dict) -> str:
    group = component.get("group")
    return f"{group}/{component.get('name')}" if group else str(component.get("name"))


def npm_purl(name: str, version: str) -> str:
    return f"pkg:npm/{name.replace('@', '%40', 1) if name.startswith('@') else name}@{version}"


def licence_entries(licence: object) -> List[dict]:
    if isinstance(licence, str) and licence.strip():
        text = licence.strip()
        if " OR " in text or " AND " in text or text.startswith("("):
            return [{"expression": text}]
        return [{"license": {"id": text}}]
    return []


def bundle_sbom(modules_path: Path, npm_path: Path, out_path: Path) -> int:
    modules, npm = load(modules_path), load(npm_path)
    declared: Dict[Tuple[str, str], dict] = {(full_name(c), str(c.get("version"))): c
                                             for c in npm.get("components", [])}
    bundled = {(p["name"], p["version"]) for p in modules.get("packages", [])}
    errors = [f"{name}@{version} is in the cockpit bundle but not among the production dependencies "
              f"(declared under devDependencies, or not declared); move it to dependencies"
              for name, version in sorted(bundled - set(declared))]
    for module in modules.get("unattributed", []):
        errors.append(f"bundled module {module!r} belongs to no package; teach cockpit-build.mjs where it comes from")
    if errors:
        return fail(errors)

    kept = [c for key, c in declared.items() if key in bundled]
    left_out = sorted(f"{name}@{version}" for name, version in set(declared) - bundled)
    runtime = []
    for package in modules.get("bundlerRuntime", []):
        name = package["name"]
        component = {"type": "library"}
        if name.startswith("@"):
            component["group"], component["name"] = name.split("/", 1)
        else:
            component["name"] = name
        component.update({
            "version": package["version"],
            "bom-ref": npm_purl(name, package["version"]),
            "purl": npm_purl(name, package["version"]),
            "licenses": licence_entries(package.get("license")),
            "properties": [{"name": "horus:bundledAs", "value": "bundler runtime"},
                           {"name": "horus:moduleIds", "value": ", ".join(package.get("moduleIds", []))}],
        })
        runtime.append(component)
    refs = {c.get("bom-ref") for c in kept + runtime}
    npm["components"] = kept + runtime
    npm["dependencies"] = [
        {"ref": d.get("ref"), "dependsOn": [r for r in d.get("dependsOn", []) if r in refs]}
        for d in npm.get("dependencies", []) if d.get("ref") in refs
    ]
    root_ref = npm.get("metadata", {}).get("component", {}).get("bom-ref")
    if root_ref:
        npm["dependencies"].append({"ref": root_ref, "dependsOn": sorted(r for r in refs if r)})
    out_path.write_text(json.dumps(npm, indent=2), encoding="utf-8")
    print(f"Bundle completeness check passed: {len(bundled)} bundled package(s), all production dependencies; "
          f"{len(runtime)} bundler runtime package(s).")
    if left_out:
        print(f"Declared but not in the bundle, left out of the release SBOM: {', '.join(left_out)}")
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
        label = f"{full_name(component)}@{component.get('version')}"
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


# ---------------------------------------------------------------------------------------------- merge

def merge(maven_path: Path, npm_path: Optional[Path], out_path: Path, build_info_path: Optional[Path]) -> int:
    maven = load(maven_path)
    npm = load(npm_path) if npm_path else {"components": [], "dependencies": []}
    missing_purl = [f"{full_name(c)}@{c.get('version')}" for c in npm.get("components", []) if not c.get("purl")]
    if missing_purl:
        return fail([f"npm component without a purl: {label}" for label in missing_purl])

    components = maven.setdefault("components", [])
    present = {c.get("purl") for c in components if c.get("purl")}
    starter = next((c for c in components if c.get("name") == STARTER), None)
    added: List[str] = []
    for component in npm.get("components", []):
        purl = component["purl"]
        if purl in present:
            continue
        component = dict(component)
        component["properties"] = list(component.get("properties", [])) + [
            {"name": "horus:shippedIn", "value": STARTER}]
        components.append(component)
        present.add(purl)
        added.append(component.get("bom-ref") or purl)

    dependencies = maven.setdefault("dependencies", [])
    npm_refs = {c.get("bom-ref") for c in npm.get("components", [])}
    for entry in npm.get("dependencies", []):
        if entry.get("ref") in npm_refs:
            dependencies.append(entry)
    if starter is not None and npm_refs:
        root_ref = npm.get("metadata", {}).get("component", {}).get("bom-ref")
        direct = next((d.get("dependsOn", []) for d in npm.get("dependencies", []) if d.get("ref") == root_ref), None)
        if direct is None:
            direct = sorted(r for r in npm_refs if r)
        entry = next((d for d in dependencies if d.get("ref") == starter.get("bom-ref")), None)
        if entry is None:
            entry = {"ref": starter.get("bom-ref"), "dependsOn": []}
            dependencies.append(entry)
        entry["dependsOn"] = sorted(set(entry.get("dependsOn", [])) | set(direct))

    metadata = maven.setdefault("metadata", {})
    properties = metadata.setdefault("properties", [])
    properties.append({"name": "horus:sbom",
                       "value": "release: Maven runtime and provided scopes plus the npm packages in the cockpit bundle"})
    # The source documents, by serial number when they carry one (cyclonedx-npm --output-reproducible
    # writes none) and always by SHA-256.
    for source, path, document in (("maven", maven_path, maven), ("npm", npm_path, npm)):
        if path is None:
            continue
        if document.get("serialNumber"):
            properties.append({"name": f"horus:sourceSerial:{source}", "value": str(document["serialNumber"])})
        properties.append({"name": f"horus:sourceSha256:{source}", "value": sha256_file(path)})
    if build_info_path:
        for key, value in flatten("horus:build", load(build_info_path)):
            properties.append({"name": key, "value": value})
    maven["serialNumber"] = f"urn:uuid:{uuid.uuid4()}"
    maven["version"] = 1
    metadata["timestamp"] = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(maven, indent=2), encoding="utf-8")
    print(f"{out_path}: {len(components)} components, {len(added)} npm package(s) shipped in {STARTER}"
          + ("" if starter or not added else f" ({STARTER} not in the Maven SBOM; packages listed without a parent)"))
    return 0


def flatten(prefix: str, value: object) -> List[Tuple[str, str]]:
    if isinstance(value, dict):
        return [pair for key, child in value.items() for pair in flatten(f"{prefix}:{key}", child)]
    return [(prefix, value if isinstance(value, str) else json.dumps(value))]


# ----------------------------------------------------------------------------------------- build info

def run(command: List[str]) -> subprocess.CompletedProcess:
    try:
        return subprocess.run(command, capture_output=True, text=True, cwd=ROOT)
    except OSError:
        return subprocess.CompletedProcess(command, 127, "", "")


def described(version: str, path: Optional[Path], hashed: Optional[Path] = None) -> dict:
    target = hashed or path
    return {
        "version": version,
        "path": str(path) if path else "",
        "sha256": sha256_file(target) if target and target.is_file() else "",
        **({"sha256Of": target.name} if hashed else {}),
    }


def on_path(name: str) -> Optional[Path]:
    found = shutil.which(name)
    return Path(found).resolve() if found else None


def build_info(out: Path, trial: bool, tag: str) -> int:
    java = run(["java", "-XshowSettings:properties", "-version"]).stderr
    java_props = {}
    for line in java.splitlines():
        key, separator, value = line.partition(" = ")
        if separator and key.strip():
            java_props.setdefault(key.strip(), value.strip())
    java_home = java_props.get("java.home", "")
    maven_lines = run(["mvn", "-v"]).stdout.splitlines()
    maven_home = next((l.split(":", 1)[1].strip() for l in maven_lines if l.startswith("Maven home:")), "")
    maven_core = sorted(Path(maven_home, "lib").glob("maven-core-*.jar")) if maven_home else []
    npm_root = run(["npm", "root", "-g"]).stdout.strip()
    npm_cli = Path(npm_root, "npm", "bin", "npm-cli.js") if npm_root else None
    info = {
        "commit": run(["git", "rev-parse", "HEAD"]).stdout.strip(),
        "workingTree": "clean" if not run(["git", "status", "--porcelain"]).stdout.strip() else "dirty",
        "tag": tag,
        "trial": trial,
        "ci": {
            "runId": os.environ.get("GITHUB_RUN_ID", ""),
            "runAttempt": os.environ.get("GITHUB_RUN_ATTEMPT", ""),
            "workflowRef": os.environ.get("GITHUB_WORKFLOW_REF", ""),
            "event": os.environ.get("GITHUB_EVENT_NAME", ""),
        },
        "toolchain": {
            "node": described(run(["node", "--version"]).stdout.strip(), on_path("node")),
            "npm": described(run(["npm", "--version"]).stdout.strip(), on_path("npm"),
                             npm_cli if npm_cli and npm_cli.is_file() else None),
            "java": described(f"{java_props.get('java.vendor', '')} {java_props.get('java.runtime.version', '')}".strip(),
                              Path(java_home, "bin", "java") if java_home else None),
            "maven": described(maven_lines[0] if maven_lines else "", Path(maven_home) if maven_home else None,
                               maven_core[0] if maven_core else None),
            "python": described(platform.python_version(), Path(sys.executable).resolve()),
        },
        "lockfiles": {
            str(path.relative_to(ROOT)): sha256_file(path)
            for path in (ROOT / "tools/release/npm/package-lock.json", ROOT / "cockpit/web/package-lock.json")
            if path.is_file()
        },
    }
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(info, indent=2) + "\n", encoding="utf-8")
    print(f"{out.name}: commit {info['commit'][:12]}, working tree {info['workingTree']}, "
          f"trial {str(trial).lower()}, {info['toolchain']['node']['version'] or 'no node'}, "
          f"{info['toolchain']['java']['version']}")
    return 0


# ----------------------------------------------------------------------------------- publishing guard

def deploy_plugins(effective_pom: Path) -> int:
    errors = []
    root = ET.parse(effective_pom).getroot()
    projects = [root] if root.tag == f"{POM_NS}project" else list(root.iter(f"{POM_NS}project"))
    for project in projects:
        name = (project.findtext(f"{POM_NS}artifactId") or "?").strip()
        build = project.find(f"{POM_NS}build")
        if build is None:
            continue
        # Active plugins only: pluginManagement declares versions and binds nothing.
        for plugin in build.findall(f"{POM_NS}plugins/{POM_NS}plugin"):
            artifact = (plugin.findtext(f"{POM_NS}artifactId") or "").strip()
            if (plugin.findtext(f"{POM_NS}extensions") or "").strip() == "true":
                errors.append(f"{name}: {artifact} is a build extension and can replace the deploy goal")
            if artifact in PUBLISHING_PLUGINS:
                errors.append(f"{name}: {artifact} publishes by itself")
            for execution in plugin.iter(f"{POM_NS}execution"):
                phase = (execution.findtext(f"{POM_NS}phase") or "").strip()
                if phase == "deploy" and artifact != "maven-deploy-plugin":
                    execution_id = (execution.findtext(f"{POM_NS}id") or "").strip()
                    errors.append(f"{name}: {artifact} execution {execution_id} is bound to deploy")
    if errors:
        return fail(sorted(set(errors)) + [
            "The dry run allows only maven-deploy-plugin at deploy, redirected into target/release "
            "(docs/RELEASING.md). Real publishing gets its own workflow."])
    print(f"Publishing guard passed: only maven-deploy-plugin is bound to deploy, in {len(projects)} module(s).")
    return 0


def tag_version(tag: str, versions: Sequence[str]) -> int:
    match = re.fullmatch(r"([A-Za-z0-9][A-Za-z0-9._-]*)/v(\d+\.\d+\.\d+)", tag)
    if not match:
        return fail([f"tag {tag!r} is not <module>/vX.Y.Z"])
    wanted = match.group(2)
    errors = [f"tag {tag} needs version {wanted}, but the build declares {version}"
              + (" (a SNAPSHOT is never released)" if version.endswith("-SNAPSHOT") else "")
              for version in versions if version != wanted]
    if errors:
        return fail(errors)
    print(f"Tag check passed: {tag} matches version {wanted}.")
    return 0


# ----------------------------------------------------------------------------------------------- main

def option(args: List[str], name: str) -> Optional[str]:
    if name in args:
        index = args.index(name)
        if index + 1 < len(args):
            value = args[index + 1]
            del args[index:index + 2]
            return value
    return None


def main(argv: List[str]) -> int:
    args = list(argv)
    command = args.pop(0) if args else ""
    if command == "manifest":
        exclude = set()
        while "--exclude" in args:
            exclude.add(option(args, "--exclude") or "")
        if len(args) == 2:
            return manifest(Path(args[0]), Path(args[1]), exclude)
    if command == "cockpit-jar" and len(args) == 2:
        return cockpit_jar(Path(args[0]), Path(args[1]))
    if command == "bundle-sbom" and len(args) == 3:
        return bundle_sbom(Path(args[0]), Path(args[1]), Path(args[2]))
    if command == "npm-licences" and len(args) == 1:
        return npm_licences(Path(args[0]))
    if command == "merge":
        info = option(args, "--build-info")
        if len(args) == 3:
            return merge(Path(args[0]), None if args[1] == "-" else Path(args[1]), Path(args[2]),
                         Path(info) if info else None)
    if command == "build-info":
        trial, tag = option(args, "--trial"), option(args, "--tag") or ""
        if len(args) == 1 and trial in ("true", "false"):
            return build_info(Path(args[0]), trial == "true", tag)
    if command == "deploy-plugins" and len(args) == 1:
        return deploy_plugins(Path(args[0]))
    if command == "tag-version" and len(args) >= 2:
        return tag_version(args[0], args[1:])
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
