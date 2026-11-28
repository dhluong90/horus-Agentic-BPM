# HA multi-replica soak test

Proof that a copy of the service can die, restart or stall, and the database can pause, without a case, a task, a timer or a
decision being lost or done twice (#655). It builds on the load harness of [`perf/`](../README.md) (#656): the same example host
(`examples/rule-and-process`, both engines and the cockpit), the same dev profile and demo users, the same operation names and the same
five kinds of work (rule evaluation, process start, task claim and complete, message correlation, list reads), plus timers and
double submits.

## What runs

| Part | What it is |
|---|---|
| Three copies | `horus-soak-host1` to `host3`, the image of `docker-compose.yml`, each with its own published port (18101 to 18103) |
| Load balancer | `SoakLb.java`: a layer 4 balancer (connection by connection, health check on `/actuator/health` every second). It never reads or replays a request, as a cloud network balancer. A request cut by a dying copy is a reset for the client |
| Pooler | PgBouncer in transaction mode, the image the pooler tests pin (`TR-ASY-04`). `--no-pooler` connects the copies to PostgreSQL directly |
| Database | PostgreSQL 16, the same pinned image as `docker-compose.yml`, `max_connections=100` |
| Driver | `SoakDriver.java`: load, faults, samples, checkpoints and the verdict, in one JDK-only program |

The copies are not given a session: the driver signs every call with HTTP Basic (the dev profile accepts it), so a copy that dies takes no
login with it. That costs about 55 ms of CPU for each call (the password hash, see `perf/README.md`), so latencies here are higher than the
baselines of `perf/baseline.json` and are **not** compared with them. The soak asserts correctness, not speed.

### Load

Flows start at a steady rate (`--flows-per-second`), chosen at random by weight:

| Flow | Share | Steps, each with its own `Idempotency-Key` |
|---|---:|---|
| `EVAL` | 34% | evaluate `card-fee` with a client correlation id |
| `CARD_GOLD` | 10% | card application that the engine runs to its end in one transaction |
| `TASK_FLOW` | 16% | card application that stops at the officer's task; read, claim, complete as a second user |
| `MSG_FLOW` | 14% | start `order-payment`, correlate `payment-received`, the case completes |
| `TIMER_FLOW` | 14% | start `payment-review`, claim and complete the review task; 70% then wait for the timer (one minute on the dev profile), 30% are settled by the payment message and the timer must be cancelled |
| `DOUBLE_SUBMIT` | 5% | the same keyed start sent twice at once through the balancer; one case must exist |
| `READS` | 7% | the cockpit list reads |

A call that fails (a reset, a 5xx, a 409 "same key still running") is replayed with the **same key and body** for up to 45 s. That is how
a real client treats a copy that died under it, and it is what makes the idempotency assertions mean something. The driver writes every flow to
a ledger before it sends the first request: what it meant to do and how far it got.

### Faults

One fault at a time, each followed by 30 s of quiet. CI mode runs this schedule (fractions of the load time):

| At | Fault |
|---:|---|
| 8% | `docker kill` (SIGKILL) `host1`, start it 10 s later |
| 20% | `docker restart` `host2` (SIGTERM, then start) |
| 32% | `docker pause` PostgreSQL for 8 s |
| 42% | kill `host3`, start it 10 s later |
| 56% | `docker pause` `host2` for 25 s (a stalled copy: no CPU, no answers, connections still open), then unpause |
| 70% | `docker stop` `host1`, start it 15 s later |
| 84% | pause PostgreSQL for 5 s |

Long mode chooses a fault at random every 5 to 15 minutes (kill, restart, stop, freeze up to 100 s, pause or restart PostgreSQL) and
prints the seed; `--seed N` repeats the sequence of choices. At most one copy is ever down, so the service never has zero copies.

### What is asserted

| Id | Assertion | How |
|---|---|---|
| `LOST-INSTANCES`, `ORPHAN-INSTANCES` | Every acknowledged start exists in `pre_instance`; nothing exists that the ledger did not send | ledger against the database, by business key and by id |
| `DUPLICATES` | No case, task, decision log row, history event or card application twice | `GROUP BY ... HAVING count(*) > 1` on the run's keys |
| `DOUBLE-SUBMIT` | Two parallel starts with one key answer with the same case | answers compared, one row in the database |
| `DECISION-LOG` | One `rle_decision` row for each acknowledged evaluation (by correlation id), none twice | |
| `IDEMPOTENT-REPLAY` | After the run, 150 acknowledged requests are sent again with their keys: same case or decision back, no new row; the same key with another body is refused (422 or 409) | counts before and after |
| `IN-FLIGHT-COMPLETE` | Every case whose steps were all acknowledged is `COMPLETED` after a drain (4 minutes, enough for a one-minute timer) with no help | |
| `ABANDONED-RECOVERABLE` | A case the client gave up on half way can be finished by an operator from what the database shows is open | |
| `TIMERS-EXACTLY-ONCE` | A timer case has one `TIMER_SCHEDULED`, one `TIMER_FIRED`, one `pre_timer` row in `DONE`, and ends at `expired`; a message case has no `TIMER_FIRED` and ends at `settled`; no `FAILED` timer, none overdue | history and `pre_timer` |
| `SEALS`, `SEALS-SERVE` | No decision with `EVALUATION_ERROR` (a version whose seal fails is refused there); every approved version holds a well-formed seal; each copy, after its restarts and a cold load, is up and evaluates the sealed version (a copy whose seal fails does not start: the engine refuses the version and the process definition that needs it) | decision log, `rle_version`, one call to each copy |
| `AUDIT-TRAIL` | Each case has one `INSTANCE_STARTED` before everything else, at most one `INSTANCE_ENDED`, nothing after it; a `COMPLETED` case has its end and a `RUNNING` case none; the append-only triggers on `pre_history` and `rle_decision` are all still enabled | `pre_history`, `pg_trigger` |
| `TENANT-ISOLATION` | Nothing of the run is in another tenant | |
| `ERROR-RATE` | At most 0.5% of calls fail after their replays | |
| `NO-ERROR-SPIKE` | In every 10 s bucket outside a fault window (and 30 s after it) at most 2% of attempts fail | `errors-by-10s.csv` |
| `RESOURCES-FLAT` | For each copy and each life of it (between restarts): resident memory, threads, file descriptors and connections to the pooler, middle half against last quarter, by median: memory under +20% and +64 MB, descriptors and threads under +25%, connections not above +5 and never above 30. PostgreSQL: application backends never above 22 behind the pooler (its cap is 20), no transaction idle for 60 s. Memory of PgBouncer and the balancer under +25% and +64 MB; of PostgreSQL (which follows the data written) not doubled and not +256 MB. No container killed for memory | `samples.csv` |
| `CHAOS-RAN` | Every copy was hit and the database was paused, so a green run is not a run with no faults | |

The checks run in the driver, not in a build, so a failed check names what broke and the report keeps the evidence. A verdict that cannot say
"fail" proves nothing, so `perf/soak/soak.sh selftest` runs one minute of load without faults and then damages the data on purpose: it deletes an
acknowledged case, adds a second `TIMER_FIRED`, switches off an append-only trigger, adds a second
decision log row and writes a well-formed but wrong seal on the approved versions, then restarts one copy (which must refuse the version
at start-up: the engine checks the seal on a cold load). It exits 0 only when `LOST-INSTANCES`, `DUPLICATES`, `TIMERS-EXACTLY-ONCE`,
`AUDIT-TRAIL`, `DECISION-LOG` and `SEALS-SERVE` all fail. A second row for one business key cannot be staged: the schema refuses it
(`uq_pre_instance_business_key`). The nightly workflow runs it before the soak.

A checkpoint every `--checkpoint` seconds (2 minutes in CI, 15 in a long run) repeats the cheap checks (duplicates, overdue timers) while the
load runs, so a long run that breaks at hour 3 says so at hour 3.

## Run it

```bash
perf/soak/soak.sh ci                     # about 15 minutes: build, 10 minutes of load with 7 faults, 4 minutes of drain, checks
perf/soak/soak.sh ci --no-pooler         # the same, copies connected to PostgreSQL directly
perf/soak/soak.sh selftest               # about 5 minutes: the checks must fail on damaged data
perf/soak/soak.sh long --hours 72        # a person launches this; faults at random, the seed is printed
perf/soak/soak.sh long --hours 24 --flows-per-second 5 --seed 42
perf/soak/soak.sh --down                 # remove a stack left behind by --keep or by an interrupted run
```

`--no-build` skips the image build when `horus-bpm/rule-and-process:local` is current. Needs Docker with Compose v2, Java 21 or later and openssl. It uses the compose project `horus-soak` and refuses to start while
containers named `horus-soak-*` exist. It removes what it started, also when a check fails.

Results go to `perf/results/soak/` (ignored by git):

| File | Content |
|---|---|
| `soak-report.md` | verdict, every check with its numbers, the faults, the operations, the resource table. This is the summary to read or to attach |
| `soak-report.json` | the same, for a script |
| `samples.csv` | memory, threads, descriptors, connections of every container every 10 s (30 s in long mode) |
| `errors-by-10s.csv` | calls, failed attempts and failed calls after replays in each 10 s |
| `ledger.csv` | every flow the client sent and how far it got, to look a case up |
| `lb-stats.json`, `containers-tail.log` | what the balancer accepted and the last 200 lines of each container |

The exit code is 0 when every check passed and 1 when one failed.

A long run keeps its ledger in memory (about 150 bytes a flow and 8 bytes a call): 72 hours at 3 flows a second is about 650,000 flows, well
inside the 2 GiB the script gives the driver. PostgreSQL grows with the work (the decision log and the history are append-only);
leave about 10 GB for 72 hours at that rate.

## In CI

`.github/workflows/soak-nightly.yml` runs `soak.sh ci` every night and on demand (a `long` run can be started by hand from the same
workflow with `mode: long` and `hours`, up to the 6 hour limit of a hosted job; days need a machine of your own and `soak.sh long`). It uploads
`perf/results/soak/`. A failed check fails the job.

## Where the operations meters plug in

The driver measures from outside (the client, `docker stats`, `/proc` of each copy, `pg_stat_activity`) because the example host exposes only
`/actuator/health` unless the operations pack (#659, [`docs/OBSERVABILITY.md`](../../docs/OBSERVABILITY.md)) is switched on. To add the engines' own
numbers, expose `health,prometheus` on a management port of the three copies in `docker-compose.soak.yml` and read it in `sampleOnce` of
`SoakDriver.java`, beside the series taken today. They would add:

| Meter of #659 | Adds to |
|---|---|
| `jvm_memory_used_bytes{area="heap"}`, `jvm_gc_pause_seconds` | RSS of the copy: heap growth against native growth |
| `hikaricp_connections_active`, `hikaricp_connections_pending` | connections to the pooler counted from the socket table |
| `horus_pre_instances_active`, `horus_pre_jobs_due_oldest_age` | the drain check: the engine's own count of open cases and the age of the oldest due timer |
| `horus_pre_timer_lag`, `horus_pre_step_duration`, `horus_rle_evaluations` | latency measured by the engine, to set beside the client's |
| `horus_rle_evaluation_errors{code}` | refused versions (seals) seen as they happen, not only in the decision log afterwards |

## Limits of what this proves

- One database, one machine: the faults are container faults. A network partition between the copies and PostgreSQL is simulated by pausing
  the database, not by dropping packets. A host crash is simulated by `kill`.
- The balancer is layer 4 and written for this test. It does not replay a request; a balancer that does (retry on a reset) would hide failures
  the client sees here. Keep that in mind when comparing with a production balancer.
- Timers use the dev profile's one-minute durations (`horus.demo.timers`). The lease of a timer job is 60 s; a copy is rarely killed in the
  few milliseconds it holds a claim, so a re-lease shows as `timers re-leased` in `TIMERS-EXACTLY-ONCE` only in long runs.
- Seal verification is the engine's own: a version that fails it is refused and writes an `EVALUATION_ERROR` row. The driver does not
  recompute seals, and no version is approved during the run.
