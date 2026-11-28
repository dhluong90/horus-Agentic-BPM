#!/usr/bin/env bash
# Checks the observability pack (docs/OBSERVABILITY.md): the alert rules parse, their unit tests pass, the
# dashboards are valid JSON, and every horus_* meter that a rule or dashboard queries is documented.
#
#   deploy/observability/check.sh
#
# promtool comes from the Prometheus image that docker-compose.yml pins, so the check needs Docker only.
# Exit 0 when all checks pass, 1 otherwise.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
IMAGE="$(sed -n 's/^ *image: \(prom\/prometheus@sha256:[0-9a-f]\{64\}\).*/\1/p' "$ROOT/docker-compose.yml")"
[[ -n "$IMAGE" ]] || { echo "no pinned prom/prometheus image in docker-compose.yml" >&2; exit 1; }

promtool() { docker run --rm -v "$HERE:/w:ro" --entrypoint promtool "$IMAGE" "$@"; }

echo "== promtool check rules"
promtool check rules /w/prometheus/rules/horus-bpm.rules.yml
echo "== promtool check config"
promtool check config --lint=none /w/prometheus/prometheus.yml 2>&1 | grep -v "rule_files\|No rule files" || true
echo "== promtool test rules"
promtool test rules /w/prometheus/tests/horus-bpm.rules.test.yml

echo "== dashboards and documented meters"
python3 - "$HERE" "$ROOT/docs/OBSERVABILITY.md" <<'PY'
import json, pathlib, re, sys
here, doc = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]).read_text()
# The doc names meters with dots (horus.pre.instances.active); Prometheus writes underscores, a unit and _total.
documented = {m.replace(".", "_") for m in re.findall(r"horus\.(?:pre|rle)\.[a-z.]+[a-z]", doc)}
def known(name):
    base = re.sub(r"_(total|seconds|bucket|count|sum|max)$", "", name)
    base = re.sub(r"_(total|seconds|bucket|count|sum|max)$", "", base)
    return any(base == d or base.startswith(d + "_") or d.startswith(base) for d in documented)
queried = set()
for path in sorted(here.glob("grafana/dashboards/*.json")):
    text = path.read_text()
    json.loads(text)
    queried |= set(re.findall(r"\bhorus_(?:pre|rle)_[a-z_]+", text))
queried |= set(re.findall(r"\bhorus_(?:pre|rle)_[a-z_]+", (here / "prometheus/rules/horus-bpm.rules.yml").read_text()))
missing = sorted(q for q in queried if not known(q))
if missing:
    sys.exit("meters queried but not in docs/OBSERVABILITY.md: " + ", ".join(missing))
print(f"{len(list(here.glob('grafana/dashboards/*.json')))} dashboards valid; {len(queried)} horus meters queried, all documented")
PY
