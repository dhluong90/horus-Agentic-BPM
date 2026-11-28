# Operations runbook

For the people who run a host that embeds the Process Engine, the Rule Engine and the cockpit. It covers what to watch, what to do when an alert fires, how to back up, restore and upgrade, and how to look after the seal key. It assumes you can run `psql`, `curl` and your own deployment tooling.

The engines are libraries, not servers. Everything they keep is in your PostgreSQL database under the `rle_` and `pre_` prefixes. You operate the host, the database, and (if you use them) Prometheus, Grafana and a trace collector.

## Find what you need

| You want to | Go to |
|---|---|
| Know what a Prometheus alert means and what to do | [Alerts](#alerts) |
| Fix stuck timers, refused messages, `QUERY_TIMEOUT`, or a refused rule version | [Common incidents](#common-incidents) |
| Give the application a database role that cannot switch off the audit triggers | [Database roles](#database-roles) |
| Back up, restore, or size the recovery time | [Backup, restore and recovery](#backup-restore-and-recovery) |
| Back up, rotate or recover the seal key | [Seal key custody](#seal-key-custody) |
| Move from 1.0.0 to a later release | [Upgrade to a later release](#upgrade-to-a-later-release) |
| Size a deployment | [Load baselines](#load-baselines) |
| Send traces to a collector | [Traces](#traces-opentelemetry) |
| Check that a downloaded jar is the one we built | [Verify a release artifact](#verify-a-release-artifact) |

## Install and scale

Start from [`docs/DEPLOYING.md`](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/docs/DEPLOYING.md): it builds the example host and runs it with PostgreSQL 16 from a fresh clone. It is a local evaluation set-up. For anything shared, read its "Run without demo users" and "Database roles" sections first.

- **Replicas.** The host is stateless. Run as many copies as you need against one database. A timer fires once across all copies, and a copy that crashes loses its claims after the lease (`jobs.timer-lease`, 60 seconds). See [[Process Engine Overview|Process-Engine-Overview]], "Timers and jobs with leases".
- **Connection pool.** Each copy keeps one connection open for `LISTEN` (cache invalidation), so size the pool with one spare. Before raising `spring.datasource.hikari.maximum-pool-size`, check the database's `max_connections` across all copies.
- **Rolling out.** Start one copy and wait for `/actuator/health` to report `UP` before starting the next.

```bash
curl -s http://127.0.0.1:8080/actuator/health
```

Expected: `{"status":"UP", ...}`. When the engines' schema is behind the code, the `ruleEngine` health contributor reports `DOWN`; run the migrations and restart.

## Watch it

[[Metrics, alerts and dashboards|Metrics-Alerts-and-Dashboards]] lists every meter and ships the alert rules and three Grafana dashboards under `deploy/observability/`. Start with the "Horus BPM" overview dashboard, then the Process Engine and Rule Engine dashboards. The thresholds are pilot starting points: set them to your service levels.

Every alert carries a `runbook_url` that points at its section below. Alert names are the section titles.

## Alerts

Each section has the same four parts: what you see, the usual cause, what to check, and what to do. The queries are PromQL unless the line starts with `SELECT`; run `SELECT` statements with a read-only database role.

### HorusHostDown

**Symptom.** Prometheus cannot scrape a host for 2 minutes. Critical.

**Likely cause.** The process is down or restarting, the management port is not reachable from Prometheus, or the host's security rules block `/actuator/prometheus` on that port.

**Check.**

```bash
curl -s http://<host>:<management-port>/actuator/health
curl -s -o /dev/null -w '%{http_code}\n' http://<host>:<management-port>/actuator/prometheus
```

Expected: `UP` and `200`. The compose profile uses management port 9464. Then read the host's log for the start-up error, and check the orchestrator's restart count.

**Remedy.** Fix the start-up error (a refused setting, a database that is down, a pending migration with `migrate-on-start: false`). If the host is up but the scrape fails, fix the network policy or the port.

### HorusServerErrors

**Symptom.** More than 5% of HTTP requests answer 5xx for 5 minutes. Critical.

**Likely cause.** The database is slow or unreachable, the connection pool is full ([HorusConnectionPoolSaturated](#horusconnectionpoolsaturated)), or one endpoint fails after a change.

**Check.** Split by route and status, then take a `correlationId` from a failing response and find it in the log. A 500 body carries only the `correlationId`; the cause is in the log.

```
sum by (uri, status) (rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
```

`503 QUERY_TIMEOUT` is counted here too: see [QUERY_TIMEOUT](#query-timeouts-503).

**Remedy.** Remove the cause the log names. If a deployment started the errors, roll back only as the release notes allow: an image from before a migration may not run on the migrated schema. When in doubt, restore the backup you took before the upgrade.

### HorusRuleEvaluationErrors

**Symptom.** More than 1% of rule evaluations fail for 5 minutes. Critical. Each failure is a decision row with outcome `EVALUATION_ERROR`.

**Likely cause.** A version fails its integrity check (the stored content or the approval seal no longer matches), or a rule fails when it runs. The error code says which.

**Check.**

```
sum by (code) (increase(horus_rle_evaluation_errors_total[15m]))
```

Then list the failing decisions, newest first, and read `errorCode` and `detail`:

```bash
curl -s -u "$USER:$PASS" 'http://127.0.0.1:8080/api/bpm/rules/v1/decisions?outcome=EVALUATION_ERROR&from=2026-11-28T00:00:00Z&to=2026-11-29T00:00:00Z'
```

**Remedy.** A detail that names a failed integrity check is the case in [A rule version is refused](#a-rule-version-is-refused-integrity-or-seal). Other codes are explained in [[Common error codes|Common-Error-Codes]]. Do not edit rows to clear the error: that is the tampering the seal exists to find.

### HorusRuleEvaluationSlow

**Symptom.** Evaluation p99 is above 250 ms for 10 minutes. Warning. The time includes the write of the decision log row.

**Likely cause.** The database is slow (disk, locks, a long backup), the pool is saturated, or the decision log partition for the current month is missing and rows go to the default partition.

**Check.** The Rule Engine dashboard, then [HorusConnectionPoolSaturated](#horusconnectionpoolsaturated) and [HorusDecisionLogDefaultPartitionRows](#horusdecisionlogdefaultpartitionrows). In PostgreSQL:

```sql
SELECT pid, state, wait_event_type, wait_event, now() - query_start AS running, left(query, 80) AS query
FROM pg_stat_activity WHERE datname = current_database() AND state <> 'idle' ORDER BY running DESC LIMIT 10;
```

**Remedy.** Fix the database cause first.

### HorusDecisionLogDefaultPartitionRows

**Symptom.** Decision rows sit outside every monthly partition for 15 minutes. Warning. The gauge `horus_rle_partition_default_rows` should be 0.

**Likely cause.** The daily roll-over did not create the month. The usual reasons: the application role cannot run DDL (see [Database roles](#database-roles)), or a lock wait timed out and the next run has not happened yet. Rows in the default partition can be erased by the roll-over, so do not leave them.

**Check.**

```sql
SELECT count(*), min(created_at), max(created_at) FROM rle_decision_default;
SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
WHERE i.inhparent = 'rle_decision'::regclass ORDER BY 1 DESC LIMIT 3;
```

The host log has a warning from `horus-rle-partitions` that names the month and the cause. `GET /actuator/health` shows `partitionMaintenanceLastSuccess`.

**Remedy.** If the application role is not the table owner, give the host the owner settings (see [the partition step](#the-decision-log-partition-step)): the roll-over then creates the month as the owner and moves the rows into it in the same transaction. Otherwise fix the cause in the log and restart the host: it creates the missing months at start-up.

### HorusUnkeyedSealAccepted

**Symptom.** A version was served on an unkeyed approval seal while a seal key is set. Warning. This only happens while the host has `acceptUnkeyedSeals()` open, which is a window for switching a key on.

**Likely cause.** The switch-over window was left open, or versions approved before the key was set are not re-sealed yet.

**Check.**

```sql
SELECT split_part(approval_seal, ':', 1) AS kind, count(*) FROM rle_version
WHERE approved_at IS NOT NULL GROUP BY 1;
```

Rows of kind `sha256` or `legacy` are unkeyed. Kind `hmac-sha256` is keyed.

**Remedy.** Re-seal each unkeyed version (see [Rotate the key](#rotate-the-key)), then set `acceptUnkeyedSeals()` back to `false` and restart. If the alert fires and no window was opened on purpose, treat it as a security event: someone may have written plain seals.

### HorusOpenIncidents

**Symptom.** Process incidents have been open for 30 minutes. Warning. Open incidents hold their instances.

**Likely cause.** A step failed for good: an adapter refused, an output failed its schema, a decision step found no published rule set.

**Check.**

```bash
curl -s -u "$USER:$PASS" 'http://127.0.0.1:8080/api/bpm/processes/v1/incidents?status=OPEN'
```

Read `errorCode`, `causeSummary` and `nodeId`. The cockpit shows the same list.

**Remedy.** Fix the cause, then retry the incident (`:retry`), or propose an output with `:resolve`, which a second person approves. Both need the incident's `If-Match` and an `Idempotency-Key`. See [[Process Engine API|Process-Engine-API]], "Incidents".

### HorusIncidentSpike

**Symptom.** More than 10 incidents opened in 10 minutes. Critical.

**Likely cause.** One broken dependency: an adapter or agent that is down, or a rule or definition change that fails for every instance.

**Check.**

```
sum by (errorCode) (increase(horus_pre_incidents_raised_total[10m]))
```

One dominant code points at one cause. Ask what was deployed or published just before the first incident.

**Remedy.** Fix or roll back the cause first, then retry the incidents one code at a time. Retrying before the cause is fixed fails again.

### HorusInstancesFailing

**Symptom.** An instance ended `FAILED` in the last 15 minutes. Warning. A `FAILED` instance cannot be resumed.

**Likely cause.** A failure that no boundary event, compensation or incident handled.

**Check.** The instance's timeline (`GET /instances/{id}/activities`) shows the step and the error code. Look for the same code in other instances.

**Remedy.** Decide with the business owner whether the case is started again. Fix the process definition so that the failure becomes an incident, which can be retried.

### HorusDueJobsBackedUp

**Symptom.** The oldest due job of a kind has waited more than 2 minutes for 5 minutes. Critical. A job is a timer, an escalation, an async step attempt or a compensation.

**Likely cause.** No replica is polling (`jobs.enabled=false`, or all hosts are down), the poll loop is slower than the arrivals, a slow adapter holds all runner slots, or the database is slow.

**Check.** Per kind and per status:

```sql
SELECT job_kind, status, count(*) AS jobs, min(next_fire_at) AS oldest_due
FROM pre_timer WHERE status IN ('DUE', 'CLAIMED') AND next_fire_at < now()
GROUP BY 1, 2 ORDER BY oldest_due;
```

`CLAIMED` rows whose `locked_until` is in the past belong to a copy that died: another copy takes them over on the next poll. Then check `up` for every replica and the Process Engine dashboard's job panels.

**Remedy.** Start a replica, or set `jobs.enabled` back to `true`. For a slow adapter, see [HorusAsyncAttemptsFailing](#horusasyncattemptsfailing). For a long backlog, raise `jobs.batch-size` (1 to 1000, default 32) in steps and watch the database. See [Stuck timers](#stuck-timers).

### HorusTimerLagHigh

**Symptom.** Timers fire on average more than 30 seconds late for 10 minutes. Warning.

**Likely cause.** The same as [HorusDueJobsBackedUp](#horusduejobsbackedup), seen from the timers that did fire.

**Check and remedy.** As for that alert. Also check the host clocks: the claim compares each timer's due time with the host's clock, so a clock that is behind makes timers late. Keep NTP running.

### HorusAsyncAttemptsFailing

**Symptom.** More than 20% of async service and agent attempts fail or are refused for 10 minutes. Warning.

**Likely cause.** The system behind a `ServiceAdapter`, or an agent, is down or slow; or an agent answer fails its output schema or guardrail (`REFUSED`).

**Check.**

```
sum by (kind, outcome) (rate(horus_pre_async_attempts_total[10m]))
```

`FAILED` is an adapter or timeout failure. `REFUSED` is an answer the engine would not accept. Then read the adapter's own log.

**Remedy.** Restore the adapter or agent. Attempts retry with back-off up to `max-attempts`, then open an incident; retry those incidents once the adapter is back. Adapters must be idempotent by `attemptId`, so a retry never repeats the effect.

### HorusMessageCorrelationRefusals

**Symptom.** More than 5 messages refused in 10 minutes. Warning. The reason label is `NOT_CORRELATED` (no instance waits) or `AMBIGUOUS` (two do).

**Check and remedy.** See [Messages are refused](#messages-are-refused).

### HorusConnectionPoolSaturated

**Symptom.** The database pool is above 90% active, or threads wait, for 5 minutes. Warning.

**Likely cause.** A slow query or lock holds connections; too few connections for the load; too many copies for the database's `max_connections`.

**Check.**

```
max by (instance) (hikaricp_connections_active / hikaricp_connections_max)
max by (instance) (hikaricp_connections_pending)
```

```sql
SELECT state, count(*) FROM pg_stat_activity WHERE datname = current_database() GROUP BY 1;
```

Many `idle in transaction` rows mean the host holds a transaction open around a slow call.

**Remedy.** Find and fix the slow statement first. Raise `spring.datasource.hikari.maximum-pool-size` only after checking that copies times pool size stays under the database's `max_connections`; use a pooler in front of the database if it does not. Poolers in transaction mode break `LISTEN`: see "Replicas and connection poolers" in [[Rule Engine Configuration|Rule-Engine-Configuration]].

## Common incidents

### Stuck timers

A timer or escalation that should have fired has not.

1. Look at the queue: the `SELECT` in [HorusDueJobsBackedUp](#horusduejobsbackedup). A `DUE` row with an old `next_fire_at` means nothing polled it. A `CLAIMED` row with `locked_until` in the future is being worked now. A `CLAIMED` row with `locked_until` in the past waits for the next poll to be taken over.
2. Check that at least one replica runs the job poller: `jobs.enabled` is `true` and the host is `UP`.
3. A job in `FAILED` used all of its attempts (`jobs.max-attempts`, 3). The instance has an open incident: retry it from the cockpit or with `POST /incidents/{id}:retry`.
4. After a database restore, timers that came due while the database was down fire on the first scan. Nothing to do.

Never update `pre_timer` by hand. The claim and the fire are fenced by an attempt number, and a hand edit can fire a timer twice.

### Messages are refused

`POST /messages:correlate` answers `422 MESSAGE_NOT_CORRELATED` or `409 MESSAGE_CORRELATION_AMBIGUOUS`. Nothing is stored for a refused message.

| Reason | Cause | What to do |
|---|---|---|
| `NOT_CORRELATED` | The name or key differs from what the instance waits for, the instance has not reached the catch step yet, or it is already past it | Compare the sender's name and key with the definition's `horus:correlationKey` and the instance's variable. If the instance is not there yet, the sender sends it again later |
| `NOT_CORRELATED` after a sender change | The sender changed the format of the key (case, padding, prefix) | Restore the format. Correlation is an exact match |
| `AMBIGUOUS` | Two instances wait on the same name and key, or two definitions start on it | Make the key unique per instance. Find the duplicates with `GET /instances?status=RUNNING&definitionKey=...`, cancel the one that is wrong |

The alert fires on volume. A few refusals after a sender retry are normal; a sustained rate is a sender that uses the wrong key. See [[Guide: messages, signals and timers|Guide-Messages-Signals-and-Timers]].

### Decision log backlog (`DECISION_LOG_BACKLOG`) (from 1.1, not in 1.0.x)

The code `DECISION_LOG_BACKLOG` is not in this release. It belongs to the asynchronous decision log (`decision-log.mode: async`, [#45](https://github.com/dhluong90/horus-Agentic-BPM/issues/45)), which is not built: today the engine writes each decision row in the caller's transaction, so no queue of unwritten decisions can build up. This section will describe the backlog once that mode ships.

What you can see today that looks like a log problem:

- **Evaluations are slow because the write is slow.** See [HorusRuleEvaluationSlow](#horusruleevaluationslow).
- **Rows outside every monthly partition.** See [HorusDecisionLogDefaultPartitionRows](#horusdecisionlogdefaultpartitionrows).
- **The log is growing without limit.** Set `decision-log.retention` (whole months, 12 or more, or `keep-forever`). From 1.0 the setting is required. See [[Rule Engine Decision Log and Audit|Rule-Engine-Decision-Log-and-Audit]].

### Query timeouts (503)

`503 QUERY_TIMEOUT` from `GET /instances` or `GET /definitions`: a list read ran longer than `horus.bpm.processes.query.statement-timeout` (default `PT2S`) and PostgreSQL stopped it. No partial page is returned.

1. Ask the caller to narrow the read: a shorter `startedFrom` to `startedTo` range, one `definitionKey`, one `status`. This is the usual fix.
2. If many callers see it, check the database: a long-running statement, a lock, a table that has not been analysed, or the disk.

```sql
SELECT relname, n_live_tup, n_dead_tup, last_autovacuum, last_autoanalyze
FROM pg_stat_user_tables WHERE relname IN ('pre_instance', 'pre_history', 'pre_token') ORDER BY n_dead_tup DESC;
```

3. Run `ANALYZE pre_instance;` if the statistics are old.
4. Raise the timeout only for a known, bounded reason. A higher limit keeps a connection busy longer and moves the failure to the pool.

The read runs in its own read-only transaction, so a host transaction around the call is untouched. Details: [[Common error codes|Common-Error-Codes]].

### A rule version is refused (integrity or seal)

Evaluation answers `EVALUATION_ERROR` and the detail names a failed integrity check. One version no longer matches its content hash or its approval seal. The other versions of the rule set keep serving. Each refusal writes an audit row and raises the alert in [HorusRuleEvaluationErrors](#horusruleevaluationerrors).

| Cause | How to tell | What to do |
|---|---|---|
| Someone changed `effective_from`, `canonical_json` or the approver in the database | The history rows in `rle_version_history` (append-only) still show the approved values | Do not recompute the seal. Restore the row from a backup, or publish a new version and retire this one. Investigate who had write access |
| The seal key is missing or is the wrong key | The seal reads `hmac-sha256:<id>:...` and no configured key has that id | Restore the key: see [Seal key custody](#seal-key-custody) |
| The host was restored to a database that is older or newer than its key set | As above | Use the key set that matches the data |
| The host started with a seal key after versions were approved and the window is closed | The seal reads `sha256:` or `legacy:pre-seal` | Open the window for the re-seal: see [Upgrade to a later release](#upgrade-to-a-later-release) |

A replica that already holds the version in its cache serves it until the cache epoch moves. Tampering is found on a cold load.

## Database roles

**Run the application with a database role that does not own the tables, and run the migrations with a separate owner role.** A table owner can switch off the audit and seal triggers (`ALTER TABLE ... DISABLE TRIGGER`, `DROP TRIGGER`) or drop the seal constraint, then edit decisions, history or seals without a trace. A role that only holds data rights cannot. The triggers stop the application role; they do not stop the owner.

| Role | Used by | Rights |
|---|---|---|
| `horus_owner` | Migrations and the decision log partition step, on a connection opened for each run and closed after it. Never used for a request | Owns the tables. Not a superuser |
| the application role (`horus` in the compose file, `horus_app` below) | Every request | `SELECT`, `INSERT`, `UPDATE`, `DELETE` on the engine tables, minus `UPDATE` and `DELETE` on the append-only ones. No ownership, no `ALTER`, `TRUNCATE` or `TRIGGER`, no `CREATE` in the schema |

The single-role set-up (one role in `spring.datasource`, nothing under `horus.bpm.*.database`, no `db_owner_password` secret in the compose file) still works and is **not for production**: that one role owns the tables and can switch off the append-only triggers. It is for a laptop walkthrough only. The compose file in the repository uses two roles.

### Create the roles

Run once as a database administrator, connected to the database that will hold the tables. The script [`deploy/postgres/app-role-grants.sql`](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/deploy/postgres/app-role-grants.sql) creates both roles, makes the owner own the database and the `public` schema, and sets default rights on tables the owner creates later, which covers the host's own tables:

```bash
psql --no-psqlrc --set ON_ERROR_STOP=1 --dbname horus \
  -v database=horus -v owner=horus_owner -v app=horus_app \
  -v owner_password="$OWNER_PW" -v app_password="$APP_PW" \
  -f deploy/postgres/app-role-grants.sql
```

Take the passwords from your secret store. Neither role is a superuser, and `horus_app` is not a member of `horus_owner`.

### Point the host at both roles

The host connects as the application role. Give each engine the owner's connection and the name of the application role. The engine migrates as the owner, then grants the application role what it needs. The host's own Flyway takes `spring.flyway.*`.

| What | Variable (or property) |
|---|---|
| Requests | `HORUS_DB_URL`, `HORUS_DB_USERNAME`, `HORUS_DB_PASSWORD` (the application role) |
| The host's own migrations | `SPRING_FLYWAY_URL`, `SPRING_FLYWAY_USER`, `SPRING_FLYWAY_PASSWORD` (the owner) |
| Rule Engine | `HORUS_BPM_RULES_DATABASE_OWNER_URL`, `..._OWNER_USERNAME`, `..._OWNER_PASSWORD`, `..._APP_ROLE`, `..._ROLE_CHECK` |
| Process Engine | `HORUS_BPM_PROCESSES_DATABASE_OWNER_URL`, `..._OWNER_USERNAME`, `..._OWNER_PASSWORD`, `..._APP_ROLE`, `..._ROLE_CHECK` |

Set `ROLE_CHECK` to `enforce` in production: the host then refuses to start if its request role owns an engine table, holds `TRIGGER` on one or is a superuser. The default `warn` logs one warning at start-up; the `dev` profile skips the check. The compose file in the repository shows every variable, with the passwords read from mounted secret files.

Every copy of the host may carry the owner settings. A copy runs migrations at start-up under Flyway's lock, as before, and the owner's connection lives only for that run. If you would rather keep the owner's password off the running hosts, run one copy with the owner settings, wait for `/actuator/health` to report `UP`, and start the others without them and with `HORUS_BPM_RULES_MIGRATE_ON_START=false` and `HORUS_BPM_PROCESSES_MIGRATE_ON_START=false`. The decision log partition step then needs the owner: see [the partition step](#the-decision-log-partition-step).

### What the engine grants

After each migration run, as the owner, each engine applies a Flyway callback (`afterMigrate__app_role_grants.sql`). For its own tables (`rle_*`, `pre_*`) it grants the application role `SELECT`, `INSERT`, `UPDATE` and `DELETE`, revokes `TRUNCATE`, `REFERENCES`, `TRIGGER` and `CREATE` on the schema, and revokes `UPDATE` and `DELETE` on every table that has a `..._no_change` trigger. The callback does nothing when `..._APP_ROLE` is empty, and stops the migration with a clear message when the role does not exist.

### Check the grants

Run as `horus_owner`. Each query states its expected answer.

```sql
-- Expected: f | f | f | f
SELECT rolsuper, rolcreaterole, rolcreatedb, rolbypassrls FROM pg_roles WHERE rolname = 'horus_app';

-- Expected: 0 (the application role owns nothing)
SELECT count(*) FROM pg_class c JOIN pg_roles r ON r.oid = c.relowner
WHERE r.rolname = 'horus_app' AND c.relkind IN ('r', 'p', 'v', 'S');

-- Expected: f
SELECT pg_has_role('horus_app', 'horus_owner', 'MEMBER');

-- Expected: no rows (no TRIGGER right on any table)
SELECT c.relname FROM pg_class c WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('r', 'p')
  AND has_table_privilege('horus_app', c.oid, 'TRIGGER');

-- Expected: f (no CREATE in the schema)
SELECT has_schema_privilege('horus_app', 'public', 'CREATE');

-- Expected: no rows (no engine table can be changed or truncated by the application role)
SELECT c.relname FROM pg_class c WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('r', 'p')
  AND EXISTS (SELECT 1 FROM pg_trigger t WHERE t.tgrelid = c.oid AND t.tgname LIKE '%\_no\_change')
  AND (has_table_privilege('horus_app', c.oid, 'UPDATE') OR has_table_privilege('horus_app', c.oid, 'DELETE')
       OR has_table_privilege('horus_app', c.oid, 'TRUNCATE'))
ORDER BY 1;
```

Then try the attacks as `horus_app` (`psql -U horus_app -d horus`). Each must fail with SQLSTATE `42501`:

```
> ALTER TABLE pre_history DISABLE TRIGGER pre_history_no_change;
ERROR:  must be owner of table pre_history
> DROP TRIGGER rle_version_legacy_seal_guard ON rle_version;
ERROR:  must be owner of table rle_version
> ALTER TABLE rle_version DROP CONSTRAINT rle_version_approval_seal_check;
ERROR:  must be owner of table rle_version
> UPDATE rle_version_history SET tenant_id = tenant_id;
ERROR:  permission denied for table rle_version_history
> DELETE FROM pre_history;
ERROR:  permission denied for table pre_history
> TRUNCATE rle_decision;
ERROR:  permission denied for table rle_decision
```

The repository's integration test `TwoRoleDatabaseIT` runs these and 17 more statements against a real host started as the application role. Also audit DDL on `rle_*` and `pre_*` in the database log (`log_statement = 'ddl'`), so a change by the owner leaves a record.

### The decision log partition step

The engine's daily roll-over creates the monthly `rle_decision_YYYY_MM` partitions and drops expired ones. Both are DDL. With the owner settings in place the Rule Engine runs this step as `horus_owner`, on a connection it opens for the run and closes after it, so months are created and expired months are dropped and recorded in `rle_decision_log_drop` as before. The application role never runs DDL.

A host started without the owner settings but with a request role that owns nothing cannot run the step: the roll-over logs a warning, retries each day, and decisions go to `rle_decision_default` until the month exists (the alert [HorusDecisionLogDefaultPartitionRows](#horusdecisionlogdefaultpartitionrows) fires). Set the owner settings on at least one copy of the host.

## Backup, restore and recovery

The engines keep all state in your PostgreSQL database: no files, no queue, no cache that matters. A backup of the database is a backup of the engines, and a restored database needs no engine-specific step. The only thing kept outside the database is the [seal key](#seal-key-custody), if you use one. Back it up with the database.

The full guide, the scripted drill and the choice of backup method are in [`docs/DISASTER-RECOVERY.md`](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/docs/DISASTER-RECOVERY.md). Run the drill from a clone:

```bash
deploy/dr/drill.sh
```

It takes about 6 minutes the first time and 2 minutes after, and writes `dr-report.json`. The `DR drill` workflow runs it every night.

### What was measured

Two drills on a laptop (Docker Desktop, PostgreSQL 16, a dump of about 230 KB), 28 November 2026, backup taken 10 seconds before the failure.

| Measure | Result (drill 2) |
|---|---|
| Recovery point (RPO): failure minus the last decision that survived | 11.6 s; the 5 decisions written after the backup were lost, as designed |
| Fresh PostgreSQL up | 5.7 s |
| `pg_restore` | 0.3 s |
| Host start until healthy | 6.2 s |
| Recovery time (RTO): first decision served after the failure | 14.6 s |
| Seals, audit trail, in-flight instances, timers | all matched |

These numbers are for a tiny database. They show what the engines add, not what your restore costs.

### Size your recovery time

Recovery time is the sum of five steps. Only the middle one depends on your data.

| Step | Drill figure | How to get yours |
|---|---|---|
| 1. Detect and decide | not measured | Your alerting delay plus the time to decide to restore |
| 2. Provision a database server | 5.7 s | Time a new instance in your platform |
| 3. Restore the data | 0.3 s for 230 KB | **Measure it, see below** |
| 4. Start one host until healthy | 6.2 s | Time it on your platform |
| 5. First decision served | about 2.4 s after the host is healthy (14.6 s total minus steps 2 to 4) | Evaluate one decision |

Steps 2, 4 and 5 add up to about 14 seconds in the drill. Step 3 grows with the database.

**Method for step 3.**

1. Read the size you must restore:

```sql
SELECT pg_size_pretty(pg_database_size(current_database())) AS database,
       pg_size_pretty(pg_total_relation_size('rle_decision')) AS decision_log,
       pg_size_pretty(pg_total_relation_size('pre_history')) AS process_history;
```

2. Restore your latest real backup into a scratch server of the same size as production, and time it. For a dump:

```bash
time pg_restore -d horus_scratch -j 4 --no-owner latest.dump
```

For base backup plus WAL, time unpacking the base backup, then add the replay time of the WAL since it: WAL volume divided by the replay rate you see in the scratch run.

3. Compute your throughput: size restored divided by seconds taken, in GB per minute.
4. Estimate step 3 for any size as size divided by that throughput. Add 20% for growth since the test.
5. RTO is steps 1 to 5 added up. Compare it with the target the bank has set. If it is over, move to base backup with WAL archiving and replicas, or shorten the data to restore with retention.

Worked illustration, with invented figures: if your scratch restore of a 100 GB database took 30 minutes, throughput is about 3.3 GB per minute. At 150 GB the restore takes about 45 minutes; with 20% growth margin, 54 minutes. Add 14 seconds for steps 2, 4 and 5, plus your detection time.

Repeat the test after the database has grown by half, and after a change of storage or instance size. Record the date and result in the [rehearsal log](#rehearsal-log).

### After a restore

1. Start one host and wait for `/actuator/health` to report `UP`.
2. Evaluate one decision for each rule set you use. A refusal that names an integrity check means a seal does not match: stop, check the seal key, or restore an earlier backup.
3. Compare open instances and incidents in the cockpit with what you expect for the restore time.
4. Reconcile side effects. Calls your adapters made after the restore point left no trace in the database. Adapters that are idempotent by `attemptId` are safe to re-run. Consumers of published events must tolerate an event for state the restore removed.
5. Start the other replicas.

## Seal key custody

A host can sign each approved rule version with a key it holds outside the database (`ApprovalSealKey`, off by default). The seal is then `hmac-sha256:<keyId>:<hex>`. The key protects against someone with database write access who can recompute a plain hash. See [[Rule Engine: publish a version|Rule-Engine-Versioning-and-Publishing]], "Seal key and rotation".

**If you lose the key, every version sealed with it is refused and cannot be verified or re-sealed.** The seal check runs on every cold load and on every replay of a past decision, so those versions stop serving and their decisions cannot be replayed. The engine has no way to bless a version without the key: the old seal must verify before a re-seal. A database backup does not contain the key. Treat the key like a signing key.

### Custody rules

| Rule | Why |
|---|---|
| Generate each key with `openssl rand -base64 32` or your HSM. At least 32 bytes. Give it an id of up to 32 characters, such as `seal-2026-11` | 32 bytes is the minimum the seal accepts |
| Keep the live copy in your secrets manager and inject it into the host at start-up. Never put it in the database, the image, the repository or a log | The key must stay outside what an administrator of the database can read |
| Keep two sealed offline copies in two places, each opened by two people. Record who holds them | One copy is lost with its site; one person leaves |
| Back up the key when you create it, before any version is sealed with it, and again after every rotation | A version sealed before the backup cannot be verified if the key is lost |
| Keep every key that still seals a version until the [check below](#rotate-the-key) shows no version names it | Dropping it refuses those versions |
| Store the key and the database backup under different owners and different access | One breach then does not give both |
| Test the restore of the key: start a scratch host from the backup copy and evaluate a decision | An untested copy is not a backup |

### Back up the key

1. Write the key's id and value to your secrets manager as a new version.
2. Put two sealed offline copies in two locations. A sealed envelope in a safe, or an encrypted file whose passphrase is split between two people, both suit.
3. Note the key id, the date and the holders in the [rehearsal log](#rehearsal-log). Do not write the value in the log.
4. Check that the offline copy works: restore it on a scratch host and evaluate a decision on a sealed version.

### Rotate the key

Rotate on a schedule your security team sets, and at once if the key may have leaked.

1. Create and back up the new key (above). Give it a new id.
2. In the host, make the new key `current()` and list the old key in `previous()`. Restart. Every approved version still verifies, so nothing is refused.
3. Re-seal each approved version, published or retired, with `VersionService.reseal(ruleSet, version, options)`. A second person does it: not the approver, author or requester of that version. The old seal must verify first, so a version whose date was changed is refused with `SEAL_MISMATCH` and is not blessed. Each re-seal adds one `RESEALED` row to the history.
4. Check which key ids are still in use:

```sql
SELECT split_part(approval_seal, ':', 2) AS key_id, count(*)
FROM rle_version WHERE approval_seal LIKE 'hmac-sha256:%' GROUP BY 1;
```

Expected: only the new id.
5. Remove the old key from `previous()` and restart. Keep the old key in your offline backup until the next backup cycle has captured a database that has no seal with its id.

### If the key is lost

1. Stop. Do not edit `rle_version`: any change is tampering and breaks the history.
2. Look for a copy: the secrets manager's earlier versions, the offline copies, a scratch host from an older backup.
3. If it is found, put it back as `current()` or `previous()` and restart. The versions verify again.
4. If it is lost for good, the affected versions stay refused. The history, the decisions and their stored inputs and outputs remain readable. To serve the rule set again, import the rule set content (an export of the version, or the source file), request publication and approve it as a new version under a new key. Past decisions on the lost versions can no longer be replayed. Whether the bank accepts that, and how it explains it to an auditor, is a decision for the bank's security owner.

## Upgrade to a later release

This is the path from 1.0.0 to the next release. The upgrade test proves it on a populated database (`tools/upgrade-test/run.sh`, nightly in CI, see `tools/upgrade-test/README.md`). It stops the old host, then starts the new one on the same database, so Flyway runs the added migrations. It does not test a rolling upgrade with old and new copies running together, so stop every 1.0.0 copy before you start the first new one.

| What changes | Effect |
|---|---|
| Rule Engine migration `V2` | Adds the approval seal column, a check constraint and a trigger. Every version approved in 1.0.0 gets the marker `legacy:pre-seal`, once |
| Process Engine migration `V2` | Adds the definition approval record. Every approved definition gets its approval row |
| Existing data | Rows, history, in-flight instances, waiting tasks and timers are kept. The upgrade test checks every row and replays every logged decision |

### Steps

1. Read the release notes for the target version. They list every migration.
2. Take a backup and check that you can restore it ([Backup, restore and recovery](#backup-restore-and-recovery)). This is the way back: migrations are not reversed.
3. Run the migrations as the owner role (see [Database roles](#point-the-host-at-both-roles)), or let the first host do it with the default `migrate-on-start`.
4. With the two-role set-up there is nothing to run: each engine grants the application role its rights after every migration, as the owner. Run the [grant checks](#check-the-grants) afterwards. A table added by the host's own migrations gets its rights from the default privileges that `app-role-grants.sql` sets; tighten it by hand if it must be append-only.
5. Roll the hosts, one at a time, waiting for `UP` each time.
6. Run the checks below.

### Check the result

```sql
-- Both engines recorded V2 as successful. Expected: two rows, success = t
SELECT 'rle' AS engine, version, success FROM rle_schema_history WHERE version = '2'
UNION ALL SELECT 'pre', version, success FROM pre_schema_history WHERE version = '2';

-- No approved version has a missing seal. Expected: 0
SELECT count(*) FROM rle_version WHERE approved_at IS NOT NULL AND approval_seal IS NULL;

-- How many versions still carry the marker
SELECT count(*) AS legacy_marked FROM rle_version WHERE approval_seal = 'legacy:pre-seal';
```

Then evaluate one decision per rule set, and replay one logged decision from before the upgrade (`POST /api/bpm/rules/v1/decisions/{decisionId}:replay`). It returns the same outputs.

### The legacy pre-seal marker

A version approved before the seals existed has nothing to verify. The migration gives it the explicit marker `legacy:pre-seal`, so an approved version with a missing seal is never mistaken for one of these: the engine refuses a missing seal.

- **Without a seal key,** marked versions serve as before. They carry no protection against someone who can edit the database.
- **With a seal key,** marked versions and plain `sha256:` seals are refused unless the host opens `acceptUnkeyedSeals()`. This is the switch-on window: open it, re-seal every marked version with `VersionService.reseal` (a second person, one `RESEALED` row each), then set it back to `false` and restart. While it is open the host logs a WARN at start-up and for each version served on an unkeyed seal, and counts them in `horus_rle_seal_unkeyed_accepted_total`, which drives [HorusUnkeyedSealAccepted](#horusunkeyedsealaccepted).
- Nothing writes the marker after the migration: a trigger refuses it. An administrator can still drop the trigger, so use a key in production and re-seal the marked versions.

Count what is left to re-seal:

```sql
SELECT approval_seal = 'legacy:pre-seal' AS marked, count(*) FROM rle_version
WHERE approved_at IS NOT NULL GROUP BY 1;
```

### If the upgrade fails

A migration that fails leaves the engine's history with a failed row and the host does not start. Restore the backup from step 2 and report the failure with the host log. Do not edit the history table to continue.

## Load baselines

[`perf/README.md`](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/perf/README.md) holds the harness, the full table and the regression limits. Use these figures for a first sizing, and measure your own.

Measured on 28 November 2026, on one laptop (Apple M5, Docker Desktop), host limited to 2 CPUs and 1 GiB, PostgreSQL 16 limited to 1 CPU and 512 MiB, 16 client threads, no failed request in 182,824:

| Operation | per second | p50 ms | p95 ms |
|---|---:|---:|---:|
| Rule evaluate, logged | 2,795 | 3.2 | 18.0 |
| Process start that runs to its end | 140 | 93.1 | 294.6 |
| Process start that stops at a user task | 99 | 19.5 | 95.7 |
| Task complete, runs to the end | 100 | 67.5 | 99.2 |
| Message correlation, instance completes | 216 | 10.7 | 91.8 |
| List instances, 50 per page, about 13,700 instances | 333 | 1.0 | 2.7 |

How to use them:

- They are a floor for a dedicated server, not a ceiling: the load generator shared the machine, and the database had one CPU. Write figures vary between runs on a laptop: two runs of one commit gave 86 and 140 process starts a second.
- Write latencies are mostly queueing: p50 is about the thread count divided by the throughput. Add database CPU before adding hosts.
- Run the harness against your own stack and compare with these figures:

```bash
java perf/LoadHarness.java --base https://<host> --password-file <file> --mode baseline --out perf/results/run.json
python3 perf/check_regression.py perf/results/run.json
```

The check fails when error rate is above 1%, p95 is five times the baseline, or throughput is a tenth of it. It catches a lost index or a lock held across a call, not a few percent.

## Traces (OpenTelemetry)

Each step the Process Engine applies and each evaluation the Rule Engine runs can be a span in your trace. **Export is off by default.** A host that does not turn it on opens no connection to any collector, and a test checks that no connection goes to the default OTLP ports. Spans carry no business key, instance id, variable, input, output or exception message. See [[Traces|Tracing]] for the span names and attributes.

To turn it on, use an **https** OTLP endpoint. Traces name your processes and rule sets and the tenant (unless you turn the attribute off), so send them only over TLS to a collector you control.

```bash
export HORUS_TRACING_EXPORT_ENABLED=true
export HORUS_TRACING_SAMPLING=0.1
export MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT=https://collector.example.internal:4318/v1/traces
```

On Spring Boot 3.5 the endpoint variable is `MANAGEMENT_OTLP_TRACING_ENDPOINT`. Sampling is a fraction from 0 to 1. Start at 0.1.

Check:

1. After a restart, the host log has no warning about the exporter, and the collector receives spans for `horus.process.step` and `horus.rule.evaluate`.
2. With the variable unset, no connection is made: a test in the repository (`TracingExportOffIT`) fails if the host opens one to the default OTLP ports.
3. Set `horus.bpm.tracing.tenant-attribute: false` if the collector is shared between tenants.

If the collector is down, the engines carry on: a tracer that fails never fails or rolls back engine work.

## Verify a release artifact

Every release is built by one workflow, with a software bill of materials (SBOM) and signed build provenance. The release step fails, and no GitHub Release is created, when a file lacks its signed statement, or the statement does not match the file. You do not have to trust a download: check it.

You need the [GitHub CLI](https://cli.github.com/) and the release tag. The example is the cockpit starter jar of `v1.0.0`:

```bash
R=dhluong90/horus-Agentic-BPM
gh release download v1.0.0 --repo $R --pattern 'cockpit-spring-boot-starter-1.0.0.jar' --pattern SHA256SUMS
gh attestation verify cockpit-spring-boot-starter-1.0.0.jar --repo $R \
  --signer-workflow $R/.github/workflows/release.yml --source-ref refs/tags/v1.0.0
```

Expected: `Verification succeeded!` and exit status 0. A file that differs by one byte finds no statement, prints an HTTP 404 and exits non-zero. Reject that file.

Check the SBOM statement for the same file, and that its subjects are the jars in `SHA256SUMS`:

```bash
gh attestation verify cockpit-spring-boot-starter-1.0.0.jar --repo $R \
  --signer-workflow $R/.github/workflows/release.yml --source-ref refs/tags/v1.0.0 \
  --predicate-type https://cyclonedx.org/bom --format json \
  --jq '.[0].verificationResult.statement.subject[] | "\(.digest.sha256)  \(.name)"' | sort > sbom-subjects.txt
grep '\.jar$' SHA256SUMS | sed -E 's#  .*/#  #' | sort | diff - sbom-subjects.txt
```

Expected: no output from `diff`. Then check the checksum of every file you downloaded:

```bash
while read -r hash path; do name="${path##*/}"; if [ -f "$name" ]; then printf '%s  %s\n' "$hash" "$name"; fi; done < SHA256SUMS | shasum -a 256 -c -
```

Each file prints `<name>: OK`.

The bill of materials is `bom-release.json` in the Release (CycloneDX 1.6). Feed it to your own vulnerability scanner. A release is also scanned again every Monday, and a high or critical finding is fixed in a patch release, never by re-tagging.

Releases up to `v1.0.0` did not attest `SHA256SUMS` and `BUILD-INFO.json`. Details, the workflow's own checks and the exit codes are in [`docs/RELEASING.md`](https://github.com/dhluong90/horus-Agentic-BPM/blob/main/docs/RELEASING.md).

## Rehearsal log

A procedure is not ready until someone on your team has run it once. Copy this table into your own change record and fill it in. The repository's CI already rehearses the first three on a compose stack every night; that does not replace a run on your infrastructure.

| Procedure | Rehearsed on your infrastructure (date, who, result) |
|---|---|
| Backup and restore, with the recovery time you measured | |
| Upgrade from 1.0.0 on a copy of production data | |
| Release artifact verification | |
| Two-role database set-up and the grant checks | |
| Seal key backup, restore on a scratch host, and rotation | |
| Alert routing: one test alert reaches the on-call person | |
| One incident from [Common incidents](#common-incidents), for example a stuck timer | |

## Related

- [[Metrics, alerts and dashboards|Metrics-Alerts-and-Dashboards]]
- [[Troubleshooting]] for errors you see as an API caller
- [[Common error codes|Common-Error-Codes]]
- [[Multi-tenancy and security|Multi-Tenancy-and-Security]]
