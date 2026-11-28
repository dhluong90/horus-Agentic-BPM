# Production-readiness gate

The pilot goes live only when every item below is `pass` or `deferred`, each with its evidence, and the product owner signs the list. This page is the checklist; `tools/readiness-check.sh` checks what can be checked from the repository and GitHub, and prints the same table.

```
tools/readiness-check.sh            # prints the table, exits 0
tools/readiness-check.sh --strict   # exits 1 while any item is pending or waits for a decision
```

The script needs `git`, `python3` and an authenticated `gh`. It checks that the pull requests are merged, that the files and workflows exist, the last run of each workflow on `main`, and the expiry date in `.grype.yaml`.

Statuses: **pass** (evidence on `main`), **pending** (work or evidence missing), **decision** (waits for the product owner), **deferred** (the product owner moved it to a later release).

## Items

| Id | Item | Evidence | Status on 28 Nov 2026 |
|---|---|---|---|
| R1 | Load baselines (#656) | PR #680, `perf/baseline.json`, workflow `perf-smoke.yml`. The baseline is a single run; record the median of three runs (architect's request on #680) | pending |
| R2 | Upgrade from 1.0.0 (#658) | PR #683, workflow `upgrade-test.yml`; legacy seal scope in PR #692 | pending until #692 is merged |
| R3 | Backup and DR drill (#657) | PR #690, [DISASTER-RECOVERY.md](DISASTER-RECOVERY.md), workflow `dr-drill.yml`. Drill result: RPO 11.6 s, RTO 14.6 s | pass |
| R4 | Metrics, alerts, dashboards (#659) | PR #681, [OBSERVABILITY.md](OBSERVABILITY.md), workflow `observability.yml` | pass |
| R5 | HA soak, short CI mode (#655) | PR #695, workflow `soak-nightly.yml`, `perf/soak/`; deterministic timer re-lease test in PR #698 | pending until #698 is merged |
| R6 | 72-hour soak on pilot infrastructure | Needs the pilot environment (D1) | pending |
| R7 | Operations runbook (#661) and support policy (#662) | PR #697 (`wiki/Operations-Runbook.md`), PR #696 (`wiki/Support-and-Versioning-Policy.md`) | pending until both are merged |
| R8 | Release gate: SBOM and signature | PR #677, `tools/release/verify-release-attestations.sh`, workflow `release.yml` | pass |
| R9 | Penetration test | Moved to 1.2, after the pilot (owner decision, 28 Nov 2026) | deferred |
| R10 | Tomcat CVE exception | `.grype.yaml` entry expires 2026-12-11; the owner decides on extension (D3) | decision |
| R11 | The database application role does not own the tables | No test or check yet; needs a migration-role split and a test that the application role cannot alter or drop tables | pending |

## Open owner decisions

| Id | Decision | Gates |
|---|---|---|
| D1 | Pilot infrastructure: which environment runs the 72-hour soak and the pilot | R6 |
| D2 | SLA, LTS and retention figures in the support and versioning policy | R7 |
| D3 | Extend the Tomcat exception past 2026-12-11, or upgrade when a fixed release exists | R10 |

## Sign-off

| Role | Name | Date |
|---|---|---|
| Product owner | | |

Update the table above and the script together when an item changes. Record the owner's decision on D1 to D3 in the pull request that closes the item.
