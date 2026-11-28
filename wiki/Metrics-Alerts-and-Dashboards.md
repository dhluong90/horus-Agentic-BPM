# Metrics, alerts and dashboards

Both engines publish Micrometer meters when the host has Micrometer on its classpath. The repository ships the Prometheus alert rules and three Grafana dashboards for them, with an optional `docker compose` profile that runs Prometheus and Grafana beside the example host.

Start it from a fresh clone:

```bash
deploy/create-secrets.sh
docker compose --env-file .env --env-file deploy/observability/observability.env --profile observability up --build --wait
```

Grafana is at `http://127.0.0.1:3000` (user `admin`, password in `secrets/grafana_admin_password`), Prometheus at `http://127.0.0.1:9090`.

The full guide is [`docs/OBSERVABILITY.md`](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/docs/OBSERVABILITY.md). It lists every meter and tag, the 14 alerts with their thresholds, how to expose the endpoint on a separate management port in your own host, and how to check the rules with promtool.

The meters never carry a tenant, business key, instance id, rule set or variable. Alert descriptions link to the operations runbook, which is written separately.
