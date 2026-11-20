#!/usr/bin/env python3
"""Refuses container image references that are not pinned by digest (PD-100, #288).

Usage:   python3 tools/check-image-pins.py [ROOT]          (ROOT defaults to the repository root)
Why:     test-only images stay off the licence allow-list on the condition that they are pinned by digest.
         A tag can be pushed again, so a reference by tag alone can change what the tests run.
Where:   Java, properties and YAML under src/test/ of every module; .github/workflows/*.yml; every pom.xml;
         tools/*.sh. node_modules, target and examples' runtime images (deploy/, docker-compose.yml, which
         the image gates cover) are not read.
What:    an image reference is
           - in a workflow: the value of an `image:` key, of the shorthand `container: <image>`, of
             `uses: docker://<image>`, or of an environment variable whose name ends in IMAGE;
           - in a POM: the value of a property whose name ends in `image`;
           - in test code: a string literal on a line that starts a container or names an image
             (Container, DockerImageName, image);
           - in a tool script: the default or value of a variable whose name ends in IMAGE.
         A value that is only an expression (${...}) is skipped: it names a value pinned elsewhere. A reference tagged :local on a
         line with the comment "pin-exempt: <reason>" is skipped: an image built in the same job.
         It fails when a reference has no @sha256:<64 hex digits>, and when a reference that Testcontainers
         reads (test code, POM properties, workflow *IMAGE variables) carries a tag and a digest together,
         which Testcontainers 2 refuses: write <name>@sha256:<digest> and name the tag in a comment.
         It also fails when a module's tests use Testcontainers and its src/test/resources/
         testcontainers.properties does not set image.substitutor: the runtime guard that refuses any container
         image without a digest, Ryuk included.
Exit:    0 when every reference is pinned and every such module has the guard, 1 otherwise, naming the file.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

DIGEST = re.compile(r"@sha256:[0-9a-f]{64}$")
# name[:tag][@sha256:...]; a name has at least one lower-case letter and no spaces.
IMAGE_LIKE = re.compile(r"^(?:[a-z0-9.-]+(?::[0-9]+)?/)*[a-z0-9]+(?:[._-][a-z0-9]+)*(?::[A-Za-z0-9._-]+)?(?:@sha256:[0-9a-fA-F]*)?$")
TAG_AND_DIGEST = re.compile(r"^(?:[^/@]+/)*[^/:@]+:[^/@]+@sha256:")

WORKFLOW_CONTAINER = re.compile(r"^\s*container:\s*(?P<value>[^\s#]+)\s*(?:#.*)?$")
WORKFLOW_DOCKER_ACTION = re.compile(r"\buses:\s*['\"]?docker://(?P<value>[^\s#'\"]+)")
WORKFLOW_IMAGE_KEY = re.compile(r"^\s*-?\s*image:\s*(?P<value>[^#]*?)\s*(?:#.*)?$")
WORKFLOW_IMAGE_VAR = re.compile(r"^\s*(?P<name>[A-Za-z0-9_]*IMAGE):\s*(?P<value>[^#]*?)\s*(?:#.*)?$")
POM_IMAGE = re.compile(r"<(?P<name>[\w.-]*image)>\s*(?P<value>[^<\s]+)\s*</(?P=name)>")
JAVA_HINT = re.compile(r"Container|DockerImageName|image", re.IGNORECASE)
STRING_LITERAL = re.compile(r'"((?:[^"\\]|\\.)*)"')
SHELL_IMAGE = re.compile(r"\b[A-Za-z0-9_]*IMAGE(?::-|=)(?P<value>[^\s}\"')]+)")
# A trailing comment "pin-exempt: <reason>" exempts an image tagged :local on that line, one built in the
# same job and never pulled. It exempts nothing else.
EXEMPT = "pin-exempt:"
SKIP_DIRS = {"node_modules", "target", ".git", "dist"}


def files(root: Path):
    for path in sorted(root.rglob("*")):
        if not path.is_file() or SKIP_DIRS.intersection(path.relative_to(root).parts):
            continue
        rel = path.relative_to(root).as_posix()
        if rel.startswith(".github/workflows/") and path.suffix in (".yml", ".yaml"):
            yield path, rel, "workflow"
        elif path.name == "pom.xml":
            yield path, rel, "pom"
        elif "/src/test/" in f"/{rel}" and path.suffix in (".java", ".kt", ".groovy"):
            yield path, rel, "code"
        elif "/src/test/" in f"/{rel}" and path.suffix in (".properties", ".yml", ".yaml"):
            yield path, rel, "config"
        elif rel.startswith("tools/") and "/" not in rel[len("tools/"):] and path.suffix == ".sh":
            yield path, rel, "shell"


def references(kind: str, line: str):
    """Yields (value, read_by_testcontainers) for each image reference on LINE."""
    stripped = line.strip()
    if kind in ("workflow", "config"):
        if stripped.startswith("#"):
            return
        match = WORKFLOW_IMAGE_KEY.match(line)
        if match:
            yield match["value"].strip("'\""), False
        match = WORKFLOW_CONTAINER.match(line)
        if match and kind == "workflow":
            yield match["value"].strip("'\""), False
        match = WORKFLOW_DOCKER_ACTION.search(line)
        if match and kind == "workflow":
            yield match["value"], False
        match = WORKFLOW_IMAGE_VAR.match(line)
        if match:
            yield match["value"].strip("'\""), True
        if kind == "config":
            for match in re.finditer(r"image\s*[=:]\s*(?P<value>[^\s#]+)", line, re.IGNORECASE):
                yield match["value"].strip("'\""), True
    elif kind == "pom":
        for match in POM_IMAGE.finditer(line):
            yield match["value"], True
    elif kind == "code":
        if stripped.startswith(("//", "*", "/*")) or not JAVA_HINT.search(line):
            return
        for literal in STRING_LITERAL.findall(line):
            if (":" in literal or "@" in literal) and IMAGE_LIKE.match(literal):
                yield literal, True
    elif kind == "shell":
        if stripped.startswith("#"):
            return
        for match in SHELL_IMAGE.finditer(line):
            yield match["value"], True


def untagged(value: str) -> str:
    """postgres:16-alpine@sha256:abc -> postgres@sha256:abc; a registry port is kept."""
    name, digest = value.split("@", 1)
    head, _, last = name.rpartition("/")
    return f"{head + '/' if head else ''}{last.split(':', 1)[0]}@{digest}"


def check(root: Path) -> list[str]:
    errors: list[str] = []
    for path, rel, kind in files(root):
        for number, line in enumerate(path.read_text(encoding="utf-8", errors="replace").splitlines(), start=1):
            for value, testcontainers in references(kind, line):
                if not value or value.startswith("$"):
                    continue  # the whole value is an expression naming a value pinned elsewhere
                if EXEMPT in line and value.endswith(":local"):
                    continue  # an image built earlier in the same job, never pulled
                if not DIGEST.search(value):
                    errors.append(f"{rel}:{number}: image '{value}' is not pinned by digest. Write "
                                  f"<name>@sha256:<digest> and name the tag in a comment (PD-100).")
                elif testcontainers and TAG_AND_DIGEST.match(value):
                    errors.append(f"{rel}:{number}: image '{value}' has a tag and a digest, which Testcontainers 2 "
                                  f"refuses. Write {untagged(value)} and name the tag in a comment.")
    return errors


def runtime_guard(root: Path) -> list[str]:
    """A module whose tests use Testcontainers must register the runtime guard in its testcontainers.properties."""
    errors: list[str] = []
    modules: set[str] = set()
    for path, rel, kind in files(root):
        if kind == "code" and "org.testcontainers" in path.read_text(encoding="utf-8", errors="replace"):
            modules.add(rel.split("/src/test/", 1)[0] if not rel.startswith("src/test/") else "")
    for module in sorted(modules):
        props = root / module / "src/test/resources/testcontainers.properties"
        shown = props.relative_to(root).as_posix()
        configured = props.is_file() and any(
            re.match(r"^\s*image\.substitutor\s*[=:]\s*\S", line)
            for line in props.read_text(encoding="utf-8", errors="replace").splitlines())
        if not configured:
            errors.append(f"{shown}: the tests of {module or 'the root module'} use Testcontainers, but this file does not set "
                          f"image.substitutor, so containers start without the digest check. Add "
                          f"image.substitutor=<package>.DigestPinnedImages and that class (PD-100).")
    return errors


def main(argv: list[str]) -> int:
    root = Path(argv[1]) if len(argv) > 1 else Path(__file__).resolve().parent.parent
    errors = check(root) + runtime_guard(root)
    for error in errors:
        print(f"::error title=Image not pinned by digest::{error}")
    if errors:
        print(f"check-image-pins: {len(errors)} problem(s): an image not pinned by digest, or a module without the runtime guard.")
        return 1
    print("check-image-pins: every container image in tests, workflows, POMs and tool scripts is pinned by digest.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
