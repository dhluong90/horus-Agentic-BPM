#!/usr/bin/env python3
"""TR-EMB-07 and PD-071: on a Spring Boot 3.5 host, Jackson 3 resolves beside the host's own Jackson 2.

Reads the output of `mvn dependency:tree -Dincludes=com.fasterxml.jackson.core,tools.jackson.core`
for the example host built with -Dspring-boot.line=3.5 and checks that
  * tools.jackson.core:jackson-databind 3.x is on the classpath (the engine's Jackson);
  * com.fasterxml.jackson.core:jackson-databind and jackson-core 2.x are on the classpath at exactly
    the version Spring Boot 3.5 manages (the host's Jackson, not overridden by the engine).
The Spring Boot 3.5 version comes from <spring-boot-3.5.version> in pom.xml; its managed Jackson 2
version from that release's spring-boot-dependencies POM in the local Maven repository.

Usage: python3 tools/check-jackson-coexistence.py <dependency-tree.txt>
Exit status: 0 when both majors resolve as expected, 1 otherwise.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def main(argv: list) -> int:
    if len(argv) != 1:
        print(__doc__)
        return 1
    tree = Path(argv[0]).read_text(encoding="utf-8")
    boot = re.search(r"<spring-boot-3\.5\.version>([^<]+)<", (ROOT / "pom.xml").read_text(encoding="utf-8")).group(1)
    bom = (Path.home() / ".m2" / "repository" / "org" / "springframework" / "boot" / "spring-boot-dependencies"
           / boot / f"spring-boot-dependencies-{boot}.pom")
    managed = re.search(r"<jackson-bom\.version>([^<]+)<", bom.read_text(encoding="utf-8")).group(1)

    found = {}
    for group, artifact, version in re.findall(r"([\w.]+):(jackson-[\w-]+):jar:([\w.-]+)", tree):
        found[(group, artifact)] = version
    errors = []
    engine = found.get(("tools.jackson.core", "jackson-databind"), "")
    if not engine.startswith("3."):
        errors.append(f"tools.jackson.core:jackson-databind 3.x not on the classpath (found '{engine}')")
    for artifact in ("jackson-databind", "jackson-core"):
        host = found.get(("com.fasterxml.jackson.core", artifact), "")
        if host != managed:
            errors.append(f"com.fasterxml.jackson.core:{artifact} is '{host}', but Spring Boot {boot} manages {managed}")

    print(tree.strip())
    for message in errors:
        print(f"ERROR: {message}")
    if errors:
        return 1
    print(f"Jackson coexistence passed on Spring Boot {boot}: engine Jackson {engine}, "
          f"host Jackson {managed} as Spring Boot manages it (TR-EMB-07).")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
