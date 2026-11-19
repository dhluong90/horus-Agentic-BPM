#!/usr/bin/env python3
"""TR-EMB-07 and PD-071: on a Spring Boot 3.5 host, Jackson 3 resolves beside the host's own Jackson 2.

Reads the output of `mvn dependency:tree -Dincludes=com.fasterxml.jackson.core,tools.jackson.core`
for the example host built with -Dspring-boot.line=3.5 and checks that
  * tools.jackson.core:jackson-databind 3.x is on the classpath (the engine's Jackson), at the
    version pom.xml pins as jackson-3.version, and that version equals the Jackson 3 version the
    build line (spring-boot-4.version) manages, so the 3.5 legs test the version that ships;
  * com.fasterxml.jackson.core:jackson-databind and jackson-core 2.x are at exactly the version
    Spring Boot 3.5 manages (the host's Jackson, not overridden by the engine);
  * com.fasterxml.jackson.core:jackson-annotations, the one artifact both majors share and the host's
    Jackson 2 BOM manages, is at least the annotations version the resolved Jackson 3 declares.
Versions come from pom.xml and from the spring-boot-dependencies and jackson-bom POMs in the local
Maven repository, whose path is the second argument (CI passes
`mvn help:evaluate -Dexpression=settings.localRepository -q -DforceStdout`).

Usage: python3 tools/check-jackson-coexistence.py <dependency-tree.txt> <local-maven-repository>
Exit status: 0 when every check passes, 1 otherwise.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path
from typing import List, Tuple

ROOT = Path(__file__).resolve().parent.parent


def pom_property(path: Path, name: str) -> str:
    match = re.search(rf"<{re.escape(name)}>([^<$]+)<", path.read_text(encoding="utf-8"))
    if not match:
        raise SystemExit(f"ERROR: <{name}> not found in {path}")
    return match.group(1).strip()


def version_key(version: str) -> Tuple[int, ...]:
    return tuple(int(part) for part in re.findall(r"\d+", version))


def boot_bom(repo: Path, version: str) -> Path:
    return repo / "org/springframework/boot/spring-boot-dependencies" / version / f"spring-boot-dependencies-{version}.pom"


def main(argv: List[str]) -> int:
    if len(argv) != 2:
        print(__doc__)
        return 1
    tree = Path(argv[0]).read_text(encoding="utf-8")
    repo = Path(argv[1])
    root_pom = ROOT / "pom.xml"
    boot35 = pom_property(root_pom, "spring-boot-3.5.version")
    boot4 = pom_property(root_pom, "spring-boot-4.version")
    pinned3 = pom_property(root_pom, "jackson-3.version")
    managed2 = pom_property(boot_bom(repo, boot35), "jackson-bom.version")
    managed3 = pom_property(boot_bom(repo, boot4), "jackson-bom.version")

    found = {}
    for group, artifact, version in re.findall(r"([\w.]+):(jackson-[\w-]+):jar:([\w.-]+)", tree):
        found[(group, artifact)] = version
    errors = []

    engine = found.get(("tools.jackson.core", "jackson-databind"), "")
    if not engine.startswith("3."):
        errors.append(f"tools.jackson.core:jackson-databind 3.x not on the classpath (found '{engine}')")
    elif engine != pinned3:
        errors.append(f"tools.jackson.core:jackson-databind is {engine}, but pom.xml pins jackson-3.version {pinned3}")
    if pinned3 != managed3:
        errors.append(f"jackson-3.version {pinned3} differs from the Jackson 3 version Spring Boot {boot4} manages "
                      f"({managed3}); update jackson-3.version in pom.xml")

    for artifact in ("jackson-databind", "jackson-core"):
        host = found.get(("com.fasterxml.jackson.core", artifact), "")
        if host != managed2:
            errors.append(f"com.fasterxml.jackson.core:{artifact} is '{host}', but Spring Boot {boot35} manages {managed2}")

    annotations = found.get(("com.fasterxml.jackson.core", "jackson-annotations"), "")
    needed = ""
    if engine.startswith("3."):
        jackson3_bom = repo / "tools/jackson/jackson-bom" / engine / f"jackson-bom-{engine}.pom"
        needed = pom_property(jackson3_bom, "jackson.version.annotations")
        if not annotations:
            errors.append("com.fasterxml.jackson.core:jackson-annotations is not on the classpath")
        elif version_key(annotations) < version_key(needed):
            errors.append(f"com.fasterxml.jackson.core:jackson-annotations is {annotations}, below the {needed} that "
                          f"Jackson {engine} needs: Spring Boot {boot35} is too old for the engine")

    print(tree.strip())
    for message in errors:
        print(f"ERROR: {message}")
    if errors:
        return 1
    print(f"Jackson coexistence passed on Spring Boot {boot35}: engine Jackson {engine} (as Spring Boot {boot4} "
          f"manages), host Jackson {managed2} as Spring Boot {boot35} manages it, shared jackson-annotations "
          f"{annotations} (Jackson {engine} needs {needed} or later) (TR-EMB-07).")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
