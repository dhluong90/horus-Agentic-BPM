#!/usr/bin/env bash
# Starts Prometheus and Grafana from the images docker-compose.yml pins, with the shipped provisioning and
# dashboards, and checks that Grafana loads every dashboard and that every panel query is valid
# (docs/OBSERVABILITY.md). check.sh only proves the JSON parses; this proves Grafana accepts it and that the
# Prometheus data source answers each expression, through Grafana's own query API.
#
#   deploy/observability/check-grafana.sh
#
# Needs Docker and python3. The containers run on a private network, publish Grafana on 127.0.0.1 only, use a
# throwaway admin password and are removed on exit, by name. Exit 0 when every panel query is valid, 1 otherwise.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
PROM_IMAGE="$(sed -n 's/^ *image: \(prom\/prometheus@sha256:[0-9a-f]\{64\}\).*/\1/p' "$ROOT/docker-compose.yml")"
GRAFANA_IMAGE="$(sed -n 's/^ *image: \(grafana\/grafana@sha256:[0-9a-f]\{64\}\).*/\1/p' "$ROOT/docker-compose.yml")"
[[ -n "$PROM_IMAGE" && -n "$GRAFANA_IMAGE" ]] || { echo "docker-compose.yml pins no prom/prometheus or grafana/grafana image" >&2; exit 1; }

RUN="horus-obs-check-$$"
PASSWORD="$(python3 -c 'import secrets; print(secrets.token_urlsafe(24))')"
cleanup() {
  status=$?
  if (( status != 0 )); then
    echo "== grafana log (last 40 lines)"; docker logs --tail 40 "$RUN-grafana" 2>&1 || true
  fi
  docker rm -f "$RUN-grafana" "$RUN-prometheus" > /dev/null 2>&1 || true
  docker network rm "$RUN" > /dev/null 2>&1 || true
  exit $status
}
trap cleanup EXIT

docker network create "$RUN" > /dev/null
# The alias matches the URL in provisioning/datasources/prometheus.yml. The scrape target is down: the queries
# are checked for validity, not for data.
docker run -d --name "$RUN-prometheus" --network "$RUN" --network-alias prometheus \
  -v "$HERE/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro" \
  -v "$HERE/prometheus/rules:/etc/prometheus/rules:ro" \
  "$PROM_IMAGE" --config.file=/etc/prometheus/prometheus.yml > /dev/null
docker run -d --name "$RUN-grafana" --network "$RUN" -p 127.0.0.1::3000 \
  -e GF_SECURITY_ADMIN_USER=admin -e "GF_SECURITY_ADMIN_PASSWORD=$PASSWORD" \
  -e GF_ANALYTICS_REPORTING_ENABLED=false -e GF_ANALYTICS_CHECK_FOR_UPDATES=false \
  -v "$HERE/grafana/provisioning:/etc/grafana/provisioning:ro" \
  -v "$HERE/grafana/dashboards:/var/lib/grafana/dashboards:ro" \
  "$GRAFANA_IMAGE" > /dev/null
PORT="$(docker port "$RUN-grafana" 3000/tcp | head -n 1 | sed 's/.*://')"

GRAFANA_URL="http://127.0.0.1:$PORT" GRAFANA_PASSWORD="$PASSWORD" python3 "$HERE/check-grafana.py" "$HERE/grafana/dashboards"
