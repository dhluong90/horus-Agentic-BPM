#!/usr/bin/env python3
"""Turn the runnable part of wiki/Getting-Started.md into one bash script (CKP-045).

Only the bash blocks between "## 1. Clone" and "## Clean up" are used, in page order. A comment on the line
before a block changes how it runs:
  <!-- guide: skip -->        not run (CI is already in a clone)
  <!-- guide: background --> started in the background, then the script waits for /actuator/health
The script ends by checking that the decision log holds exactly one row. A guide change that breaks a step
fails the build.

Usage: tools/extract-guide-commands.py [wiki/Getting-Started.md] > guide.sh
"""
import re
import sys
from pathlib import Path

START, END = "## 1. Clone", "## Clean up"


def extract(text: str) -> str:
    if START not in text or END not in text:
        raise SystemExit(f"guide must contain '{START}' and '{END}'")
    body = text.split(START, 1)[1].split(END, 1)[0]
    out = ["#!/usr/bin/env bash", "set -euo pipefail"]
    n = 0
    for m in re.finditer(r"(<!-- guide: (\w+) -->\n)?```bash\n(.*?)```", body, re.S):
        mode, code = m.group(2), m.group(3).rstrip("\n")
        n += 1
        if mode == "skip":
            continue
        if mode == "background":
            code = code.replace("\\\n", " ")
            out.append(f"( {code} > host.log 2>&1 & )")
            out.append("for i in $(seq 1 60); do curl -sf localhost:8080/actuator/health >/dev/null && break; sleep 2; done")
        elif mode:
            raise SystemExit(f"unknown marker: {mode}")
        else:
            out.append(re.sub(r"\bcurl -s ", "curl -sf ", code))
    if n == 0:
        raise SystemExit("no bash blocks found")
    out.append("test \"$(docker exec horus-pg psql -U horus -d horus -tA -c 'SELECT count(*) FROM rle_decision')\" = 1")
    return "\n".join(out) + "\n"


if __name__ == "__main__":
    path = Path(sys.argv[1] if len(sys.argv) > 1 else "wiki/Getting-Started.md")
    sys.stdout.write(extract(path.read_text()))
