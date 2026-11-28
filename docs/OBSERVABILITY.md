# Metrics, alerts and dashboards

What an operator gets with a host that embeds the Process Engine and the Rule Engine: Prometheus meters, alert rules, three Grafana dashboards, and an optional compose profile that runs all of it on one machine. Everything is under `deploy/observability/`.

## Run it locally

From a fresh clone, after the quick start in [DEPLOYING.md](DEPLOYING.md):

```bash
deploy/create-secrets.sh
docker compose --env-file .env --env-file deploy/observability/observability.env --profile observability up --build --wait
```

| What | Where | Sign-in |
|---|---|---|
| Grafana, folder "Horus BPM" | http://127.0.0.1:3000 | `admin`, password in `secrets/grafana_admin_password` |
| Prometheus, with alert state | http://127.0.0.1:9090 | none |
| Host metrics | `host:9464/actuator/prometheus` on the compose network only | none; not published |

`observability.env` sets two variables, `HORUS_MANAGEMENT_PORT=9464` and `HORUS_MANAGEMENT_EXPOSURE=health,prometheus`. Without that file the host behaves as before and the profile's two containers do not start.

## Hosts outside compose

Add the registry to the host, expose the endpoint on a separate management port, and let Prometheus reach that port only:

```xml
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

```yaml
management:
  server:
    port: 9464              # not the application port, never behind the public ingress
  endpoints:
    web:
      exposure:
        include: health,prometheus
  metrics:
    distribution:
      percentiles-histogram:   # buckets for the p50 and p99 panels and the slow-evaluation alert
        http.server.requests: true
        horus.rle.evaluations: true
        horus.pre.step.duration: true
```

The host's own security rules must let the scrape through on that port. The example hosts do it in `HostSecurityConfiguration.prometheusOnManagementPort`: `/actuator/prometheus` is open only to a request that arrives on the management port, and stays behind the sign-in on the application port. On Kubernetes, scrape the pod's management port and add a `NetworkPolicy` that admits only Prometheus.

## Meters

Prometheus names replace `.` with `_` and add `_total` to counters and `_seconds` to timers. Tags never carry a tenant, a business key, an instance id, a rule set or a variable.

| Meter | Kind | Tags | Meaning |
|---|---|---|---|
| `horus.rle.evaluations` | timer | `outcome` = `matched`, `no_match`, `failed`, `refused` | Decision evaluations: rate, latency, and the decision log write |
| `horus.rle.evaluation.errors` | counter | `code` | Failed or refused evaluations by error code |
| `horus.rle.cache.size`, `horus.rle.cache.evictions` | gauge, counter | `cause` | Compiled versions held by this replica |
| `horus.rle.partition.default.rows` | gauge | | Decision rows outside every monthly partition; should be 0 |
| `horus.rle.lifecycle.retired`, `horus.rle.seal.unkeyed.accepted` | counter | `reason` | Retired versions; versions served on an unkeyed seal |
| `horus.pre.instances.started`, `horus.pre.instances.completed`, `horus.pre.instances.failed` | counter | none | Instance outcomes, counted after commit |
| `horus.pre.instances.active`, `horus.pre.incidents.open` | gauge | | Running instances and open incidents, refreshed every 30 s |
| `horus.pre.incidents.raised` | counter | `errorCode` | Incidents opened |
| `horus.pre.jobs.due.oldest.age` | gauge | `kind` | Age in seconds of the oldest due job: the backlog of timers, escalations, async steps and compensations |
| `horus.pre.timer.lag` | timer | | How late timers fire |
| `horus.pre.step.duration` | timer | `stepKind`, `outcome` | Time the kernel spends on one command |
| `horus.pre.async.attempts` | counter | `kind`, `outcome` | Async service and agent attempt results |
| `horus.pre.messages.refused` | counter | `reason` = `NOT_CORRELATED`, `AMBIGUOUS` | Message correlations that delivered nothing |
| `hikaricp_connections_*` | gauge, timer | `pool` | Database pool, from Spring Boot |
| `http_server_requests_seconds_*`, `jvm_*` | | | Spring Boot's HTTP and JVM meters |

Not yet metered, so not on the dashboards: a count of open user tasks (the table has no index that answers it cheaply; the age of the oldest due job covers the timer and async backlog), and outbox lag (the engines publish events through the host's `ProcessEventPublisher` and keep no outbox of their own, so lag belongs to the host's outbox).

## Alerts

`deploy/observability/prometheus/rules/horus-bpm.rules.yml` defines the alerts below. Thresholds are pilot starting points: set them to the bank's service levels. Each alert carries a `runbook_url` that points at an anchor of the operations runbook on the wiki, which is written in #661.

| Alert | Fires when | Severity |
|---|---|---|
| `HorusHostDown` | Prometheus cannot scrape a host for 2 minutes | critical |
| `HorusServerErrors` | More than 5% of requests answer 5xx for 5 minutes | critical |
| `HorusRuleEvaluationErrors` | More than 1% of evaluations fail for 5 minutes | critical |
| `HorusRuleEvaluationSlow` | Evaluation p99 above 250 ms for 10 minutes | warning |
| `HorusDecisionLogDefaultPartitionRows` | Decision rows outside every monthly partition for 15 minutes | warning |
| `HorusUnkeyedSealAccepted` | A version was served on an unkeyed seal while a key is set | warning |
| `HorusOpenIncidents` | Incidents stay open for 30 minutes | warning |
| `HorusIncidentSpike` | More than 10 incidents in 10 minutes | critical |
| `HorusInstancesFailing` | An instance ended `FAILED` in the last 15 minutes | warning |
| `HorusDueJobsBackedUp` | The oldest due job of a kind has waited more than 2 minutes for 5 minutes | critical |
| `HorusTimerLagHigh` | Timers fire on average more than 30 s late for 10 minutes | warning |
| `HorusAsyncAttemptsFailing` | More than 20% of async attempts fail or are refused for 10 minutes | warning |
| `HorusMessageCorrelationRefusals` | More than 5 refused messages in 10 minutes | warning |
| `HorusConnectionPoolSaturated` | Pool above 90% active, or threads waiting, for 5 minutes | warning |

Check the rules and run their unit tests with promtool (the image of the profile contains it):

```bash
docker run --rm -v "$PWD/deploy/observability:/w" --entrypoint promtool prom/prometheus check rules /w/prometheus/rules/horus-bpm.rules.yml
docker run --rm -v "$PWD/deploy/observability:/w" --entrypoint promtool prom/prometheus test rules /w/prometheus/tests/horus-bpm.rules.test.yml
```

CI runs both on every change under `deploy/observability/`. To route the alerts, point Prometheus at your Alertmanager (`alerting:` in `prometheus.yml`); the profile ships none.

## Dashboards

Grafana loads three dashboards from `deploy/observability/grafana/dashboards/` into the folder "Horus BPM": host overview (availability, requests, pool, JVM), Process Engine, and Rule Engine. They read the data source `horus-prometheus`, provisioned by `grafana/provisioning`. To use them in another Grafana, import the JSON files and select your Prometheus as the data source.

On Kubernetes, put the same JSON files in a ConfigMap labelled for the Grafana sidecar and the rules file into a `PrometheusRule`; the expressions are the same.

## Check it works

```bash
curl -s -u admin:"$(cat secrets/grafana_admin_password)" 127.0.0.1:3000/api/search?tag=horus-bpm | head -c 400
curl -s 127.0.0.1:9090/api/v1/targets | grep -o '"health":"[a-z]*"'
```

The first lists the three dashboards. The second prints `"health":"up"`.
