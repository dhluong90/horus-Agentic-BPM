#!/usr/bin/env bash
# Load smoke test and regression check against perf/baseline.json (#656).
#
#   perf/smoke.sh            build the compose stack, run a short load, compare with the baseline, tear down
#   perf/smoke.sh --baseline run the full baseline load instead and write perf/results/baseline-run.json
#
# Needs Docker with Compose v2, Java 21 or later and python3. Uses its own compose project (horus-perf), its own
# secrets and a port of its own, so it can run beside a stack you started yourself. It removes what it started.
set -euo pipefail
cd "$(dirname "$0")/.."

PROJECT=horus-perf
PORT="${PERF_HTTP_PORT:-18090}"
MODE=smoke
[ "${1:-}" = "--baseline" ] && MODE=baseline
MACHINE="${PERF_MACHINE:-$(uname -srm), $(getconf _NPROCESSORS_ONLN 2>/dev/null || echo '?') logical CPUs; host container 2 CPUs and 1 GiB, PostgreSQL container 1 CPU and 512 MiB (docker-compose.yml)}"

ENVFILE="$(mktemp)"
cleanup() {
  docker compose -p "$PROJECT" --env-file "$ENVFILE" down -v --remove-orphans >/dev/null 2>&1 || true
  rm -f "$ENVFILE"
}
trap cleanup EXIT

[ -f secrets/demo_password ] || deploy/create-secrets.sh
cat > "$ENVFILE" <<EOF
SPRING_PROFILES_ACTIVE=dev
HORUS_EXAMPLE=rule-and-process
HORUS_HTTP_PORT=$PORT
EOF
docker compose -p "$PROJECT" --env-file "$ENVFILE" up --build --wait

mkdir -p perf/results
java perf/LoadHarness.java --base "http://127.0.0.1:$PORT" --password-file secrets/demo_password --mode "$MODE" \
  --machine "$MACHINE" --out "perf/results/$MODE-run.json"

if [ "$MODE" = smoke ]; then
  python3 perf/check_regression.py "perf/results/$MODE-run.json"
fi
