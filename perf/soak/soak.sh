#!/usr/bin/env bash
# HA multi-replica soak test (#655): three copies of the example host behind a load balancer and a pooler, one
# PostgreSQL, sustained mixed load, kills and restarts of one copy at a time, a paused database, then a verdict.
#
#   perf/soak/soak.sh ci                 about 15 minutes: 10 minutes of load with a fixed fault schedule, then the checks
#   perf/soak/soak.sh long --hours 72    hours to days, faults at random; prints the seed so a run can be repeated
#   perf/soak/soak.sh selftest           about 5 minutes: one minute of load, no faults, then the data is damaged on purpose and the
#                                        checks must fail (exit 0 when they do); proves the verdict can say "fail"
#
# Options (after the mode):
#   --hours N | --duration SECONDS   length of the load (ci: 600 s; long: 24 h unless given)
#   --flows-per-second N             flows started each second (ci: 10, long: 3)
#   --no-pooler                      the copies connect to PostgreSQL directly instead of through PgBouncer
#   --seed N                         seed of the random choices (flow mix, long-run faults)
#   --no-build                       use the image horus-bpm/rule-and-process:local that is already built
#   --keep                           leave the stack running afterwards (stop it with: perf/soak/soak.sh --down)
#   --out DIR                        where the report goes (default perf/results/soak)
#   --down                           remove a stack left by --keep, and exit
#
# Needs Docker with Compose v2, Java 21 or later (the load balancer and the driver are single-file programs) and
# openssl. Uses its own compose project (horus-soak) and containers named horus-soak-*; it refuses to start when
# those exist, and removes what it started. Nothing is pulled that is not pinned by digest in the compose file.
# Exit code: 0 all checks passed, 1 a check failed, 2 the stack could not be started.
set -euo pipefail
cd "$(dirname "$0")/../.."

COMPOSE=(docker compose -f perf/soak/docker-compose.soak.yml)
MODE="${1:-ci}"
[ $# -gt 0 ] && shift || true
DURATION=""; FLOWS=""; POOLER=on; SEED=""; KEEP=0; BUILD=1; TAMPER=off; OUT="perf/results/soak"

while [ $# -gt 0 ]; do
  case "$1" in
    --hours) DURATION=$(( $2 * 3600 )); shift 2 ;;
    --duration) DURATION="$2"; shift 2 ;;
    --flows-per-second) FLOWS="$2"; shift 2 ;;
    --no-pooler) POOLER=off; shift ;;
    --seed) SEED="$2"; shift 2 ;;
    --no-build) BUILD=0; shift ;;
    --keep) KEEP=1; shift ;;
    --out) OUT="$2"; shift 2 ;;
    --down) "${COMPOSE[@]}" --profile migrate down -v --remove-orphans; exit 0 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done

case "$MODE" in
  ci)   DURATION="${DURATION:-600}";   FLOWS="${FLOWS:-${SOAK_FLOWS_PER_SECOND:-8}}"; CHAOS=scheduled; CHECKPOINT=120; SAMPLE=10; DRAIN=240 ;;
  long) DURATION="${DURATION:-86400}"; FLOWS="${FLOWS:-3}";  CHAOS=random;    CHECKPOINT=900; SAMPLE=30; DRAIN=600 ;;
  selftest) DURATION="${DURATION:-60}"; FLOWS="${FLOWS:-8}"; CHAOS=none; CHECKPOINT=30; SAMPLE=10; DRAIN=150; TAMPER=on ;;
  --down) "${COMPOSE[@]}" --profile migrate down -v --remove-orphans; exit 0 ;;
  *) echo "usage: perf/soak/soak.sh ci|long|selftest [options]" >&2; exit 2 ;;
esac

if [ -n "$(docker ps -a --filter 'name=horus-soak-' -q)" ]; then
  echo "Containers named horus-soak-* exist. Remove them first: perf/soak/soak.sh --down" >&2
  exit 2
fi

cleanup() {
  if [ "$KEEP" = 0 ]; then
    "${COMPOSE[@]}" --profile migrate logs --no-color --tail 200 > "$OUT/containers-tail.log" 2>&1 || true
    "${COMPOSE[@]}" --profile migrate down -v --remove-orphans >/dev/null 2>&1 || true
  fi
}
mkdir -p "$OUT"
trap cleanup EXIT

deploy/create-secrets.sh >/dev/null
# PgBouncer reads its sign-in list from a secret file: the application role and its password, in its own format.
(umask 022; printf '"horus" "%s"\n' "$(cat secrets/db_password)" > secrets/pgbouncer_userlist)

echo "== compile the load balancer"
mkdir -p perf/results/soak-lb
javac --release 21 -d perf/results/soak-lb perf/soak/SoakLb.java

if [ "$POOLER" = on ]; then
  export SOAK_DB_HOST=pgbouncer SOAK_LISTEN_NOTIFY=false
else
  export SOAK_DB_HOST=postgres SOAK_LISTEN_NOTIFY=true
fi

echo "== build and migrate (a direct connection: Flyway's lock is a session lock)"
if [ "$BUILD" = 1 ]; then "${COMPOSE[@]}" build migrate; fi
"${COMPOSE[@]}" --profile migrate up -d --no-build --wait migrate
"${COMPOSE[@]}" stop migrate >/dev/null

echo "== start the pooler, three copies and the balancer"
"${COMPOSE[@]}" up -d --no-build --wait host1 host2 host3 lb

args=(--duration "$DURATION" --drain "$DRAIN" --flows-per-second "$FLOWS" --chaos "$CHAOS" --checkpoint "$CHECKPOINT"
      --sample "$SAMPLE" --tamper "$TAMPER" --pooler "$POOLER" --out "$OUT" --password-file secrets/demo_password)
[ -n "$SEED" ] && args+=(--seed "$SEED")

echo "== soak ($MODE): ${DURATION} s of load, ${FLOWS} flows/s, chaos $CHAOS, pooler $POOLER"
set +e
java -Xmx2g perf/soak/SoakDriver.java "${args[@]}"
status=$?
set -e
curl -s "http://127.0.0.1:${SOAK_STATS_PORT:-18092}/stats" > "$OUT/lb-stats.json" 2>/dev/null || true
exit $status
