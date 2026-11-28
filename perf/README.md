# Performance and load baselines

Measured numbers for sizing a deployment (#656). The harness is in this folder, so every figure here can be reproduced.

- `LoadHarness.java`: a closed-loop load generator for the JDK alone (`java perf/LoadHarness.java`, Java 21 or later).
  Each thread sends its next request when the last one answered. It signs in once per demo user with the host's form, as the
  cockpit does, then sends the session cookie and the CSRF header, so the numbers do not include the demo user store's password
  hashing (about 55 ms for each request with HTTP Basic). Latency is measured at the client, in milliseconds, after a warm-up
  that is discarded. A non-2xx answer counts as an error and is never dropped.
- `baseline.json`: the numbers below, and the limits `check_regression.py` applies.
- `check_regression.py`: compares a run with the baseline and exits 1 when it is much worse.
- `smoke.sh`: builds the compose stack, runs the short smoke load, runs the check and removes the stack.
- [`soak/`](soak/README.md): the HA soak test (#655), three copies behind a balancer and a pooler with kills, restarts and a paused database.
- `.github/workflows/perf-smoke.yml`: runs `smoke.sh` nightly, on demand and for a pull request that touches `perf/`.

## What is measured

The stack is `docker compose` as in [`docs/DEPLOYING.md`](../docs/DEPLOYING.md): the example host `examples/rule-and-process`
(Spring Boot 4, Temurin 21, dev profile, both engines and the cockpit) on PostgreSQL 16, each in its own container with the
compose limits (host 2 CPUs and 1 GiB, PostgreSQL 1 CPU and 512 MiB). Five scenarios run one after the other, each for
30 s after an 8 s warm-up, with 16 client threads (the cockpit lists use half as many):

| Scenario | What each iteration does |
|---|---|
| `rule-evaluate` | `POST /decisions/card-fee:evaluate`; each call writes a row in the decision log |
| `process-start` | `POST /api/cards/applications` for a gold customer: stores the application, starts `card-fee-review`, loads the profile, evaluates the rule set and approves, all in one transaction |
| `task-flow` | the same for a standard customer, which stops at the officer's task; then read the instance, claim the task and complete it as a second user (four eyes) |
| `message-flow` | start `order-payment`, which waits for a payment; then `POST /messages:correlate` delivers it and the instance completes |
| `cockpit-lists` | the list reads of the cockpit, one page of 50 each, over the data the earlier scenarios created |

## Baseline

Measured on 2026-11-28 at commit `2bcaf583`.

Machine: Apple M5, 10 cores, 24 GB, macOS 26 (Darwin 25.6), Docker Desktop; host container limited to 2 CPUs and 1 GiB, PostgreSQL 16 container to 1 CPU and 512 MiB (docker-compose.yml). The load generator ran on the same machine, outside the containers (Mac OS X aarch64, Java 25.0.4.1, 10 CPUs), so the figures are a
floor for a dedicated server, not a ceiling. The run created about 13,700 instances and 91,000 decisions before it ended; the list figures are measured over that data.

| Operation | Id | per second | p50 ms | p95 ms | p99 ms | errors |
|---|---|---:|---:|---:|---:|---:|
| Rule evaluate (card-fee, logged) | `rule.evaluate` | 2795 | 3.17 | 17.99 | 56.61 | 0 |
| Process start, runs to its end (card application, gold customer) | `process.start` | 140 | 93.12 | 294.63 | 391.43 | 0 |
| Process start, stops at a user task | `process.start.to-user-task` | 99 | 19.52 | 95.7 | 113.17 | 0 |
| Instance read | `instance.read` | 99 | 6.54 | 82.85 | 90.64 | 0 |
| Task claim | `task.claim` | 100 | 16.55 | 93.53 | 104.77 | 0 |
| Task complete (runs on to the end) | `task.complete` | 100 | 67.5 | 99.2 | 120.27 | 0 |
| Process start, stops at a message wait | `process.start.to-wait-state` | 216 | 11.62 | 92.43 | 103.56 | 0 |
| Message correlation (delivered, instance completes) | `message.correlate` | 216 | 10.74 | 91.77 | 103.14 | 0 |
| List instances, 50 per page | `list.instances` | 333 | 1.0 | 2.71 | 53.89 | 0 |
| List completed instances | `list.instances-completed` | 333 | 1.25 | 3.3 | 53.03 | 0 |
| List tasks of the caller | `list.tasks` | 333 | 3.07 | 53.79 | 64.07 | 0 |
| List process definitions | `list.definitions` | 333 | 1.17 | 3.05 | 54.72 | 0 |
| List incidents | `list.incidents` | 333 | 0.92 | 2.33 | 44.83 | 0 |
| List rule sets | `list.rule-sets` | 333 | 1.18 | 3.04 | 54.92 | 0 |
| List decision log, one rule set, last day | `list.decisions` | 333 | 1.89 | 6.24 | 59.56 | 0 |

What to read from it:

- A rule evaluation that writes its decision log row has a p50 of about 3 ms and runs at about 2,800 a second with 16 threads.
- Starting a process and completing a task each commit a handful of rows (instance, tokens, history, decision, host table). A start
  that runs to its end sustained 140 a second and a start that stops at a user task 99 a second, each followed by three reads and
  writes of the same instance. Those figures are with 16 threads on a database limited to one CPU, so the latencies are mostly
  queueing: p50 is about the thread count divided by the throughput. The causes of the limit were not profiled in this run.
- The list reads have a p50 of 1 to 3 ms and a p95 of 2 to 6 ms, except the task list (p95 about 54 ms), with about 13,700 instances and
  91,000 decisions in the tables. Every list shows a p99 of about 55 ms while the other scenarios are quiet, which is not explained here.
- No request failed in 182824 measured requests.
- The write figures vary between runs on a laptop. Two baseline runs of the same commit gave 86 and 140 process starts a second (p50 194 and 93 ms) and the same shape everywhere else; the first run is kept in the pull request. The regression limits (below) are five times wider than that spread.

## Run it

Reproduce the baseline (about five minutes after the first image build):

```bash
perf/smoke.sh --baseline          # builds and starts the stack, runs the full load, tears the stack down
```

or against a stack you already run, such as the one in `docs/DEPLOYING.md` with the `dev` profile and `HORUS_EXAMPLE=rule-and-process`:

```bash
java perf/LoadHarness.java --base http://127.0.0.1:8080 --password-file secrets/demo_password --mode baseline --out perf/results/run.json
```

`--threads`, `--duration` and `--warmup` change the load; `--mode smoke` is 4 threads for 5 s after a 2 s warm-up.

## The regression check

```bash
perf/smoke.sh                      # smoke load, then check_regression.py against perf/baseline.json
python3 perf/check_regression.py perf/results/run.json     # compare a run you already have
```

For every operation in the baseline a run must have an error rate of at most 1%, a p95 of at most five times the baseline p95
(never less than 100 ms) and a throughput of at least a tenth of the baseline, scaled by the ratio of threads. The limits are in `baseline.json`
under `regression`. They are wide because a smoke run is short, starts cold and shares a CI runner; they catch a large regression
(a lost index, a lock held across a call), not a few percent. To move a baseline, run `perf/smoke.sh --baseline` on a quiet machine,
copy the numbers into `baseline.json`, and give the machine and the commit in the pull request.
