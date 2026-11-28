# Upgrade test from 1.0.0

Proves that a populated 1.0.0 database survives the upgrade to the commit under test (#658). It is a test of the
packaged deployment, not of the libraries: two builds of the example host `examples/rule-and-process`, one PostgreSQL 16.

```bash
tools/upgrade-test/run.sh            # from v1.0.0 to this checkout; run `git fetch --tags` first
tools/upgrade-test/run.sh v1.0.0     # the starting point can be any tag or commit
```

Needs Docker, Maven, Java 21 or later and python3. It takes about six minutes, most of it the two Maven builds and the
one-minute timer, so CI runs it nightly, for a release candidate tag, on a pull request that changes a migration, and on demand
(`.github/workflows/upgrade-test.yml`). It stops only the host and the PostgreSQL container it started.

## What it does

1. Builds the host from the old tag in a temporary worktree and from the checkout.
2. Starts PostgreSQL and the old host, and seeds through the REST API: a rule set with two approved versions and a third
   waiting for approval, 19 logged decisions, four applications waiting at an officer task (one claimed), four orders waiting
   for a payment message, two instances waiting at a one-minute timer, one completed instance, and the 29 demo process definitions.
3. Records every row of the version, version history, decision log, process history, definition and definition history tables.
4. Stops the old host and starts the new one on the same database, so Flyway runs the migrations added since.
5. Checks:

| Id | What is checked |
|---|---|
| UPG-01 | Every recorded row is still there and unchanged; waiting instances and tasks are untouched by the upgrade |
| UPG-02 | Flyway history keeps the old versions and records V2 of both engines as successful |
| UPG-03 | A version approved before the upgrade reads with its approver and hash |
| UPG-04 | A version approved before the seals existed carries the `legacy:pre-seal` marker, and no approved version has a missing seal |
| UPG-05 | Every logged decision replays to the same outputs and matched rules in the new JVM (the stored version is loaded and its seal checked); the version in force serves |
| UPG-06 | An approval that was pending at the upgrade can be approved afterwards, gets a real `sha256:` seal and is served; old decisions still replay on their own version |
| UPG-07 | The task claimed before the upgrade is still claimed; every task completes and the host's application becomes `APPROVED` |
| UPG-08 | Every waiting order receives its message and completes |
| UPG-09 | The timers pending at the upgrade fire in the new host and their instances complete |
| UPG-10 | The old audit rows are unchanged and new ones were appended; the history API shows every step; every approved process definition has its approval record; every seeded instance ends `COMPLETED` |

Exit code 0: all checks passed. 1: a check failed (the failing ids and values are printed). 2: the set-up failed (a host
did not start, a seed call was refused). Host logs of both builds are kept in the folder printed at the start.

To extend it: add seed data in `seed()`, record its rows in `ROW_QUERIES` or `snapshot()`, and add a check in `verify()`
under the next `UPG-` id.
