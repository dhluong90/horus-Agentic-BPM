# Production-readiness gate

The pilot goes live only when every item below is `pass` or `deferred`, each with its evidence, and the product owner has signed the list (the script reads the sign-off row at the end of this page). This page is the checklist; `tools/readiness-check.sh` checks what can be checked from the repository and GitHub, and prints the same table.

```
tools/readiness-check.sh            # prints the table, exits 0
tools/readiness-check.sh --strict   # exits 1 while any item is pending or waits for a decision
```

The script needs `git`, `python3` and an authenticated `gh`. It checks that the pull requests are merged, that the files and workflows exist, the latest run of each workflow on `main`, and the expiry date in `.grype.yaml`.

A workflow-backed item passes only if its latest run on `main` has conclusion `success`: a failed run makes it **fail**, no run leaves it **pending**. R1 passes only when `perf/baseline.json` holds three `runs` and a `median`. R3 shows RPO and RTO only if they are recorded in a drill report or in `DISASTER-RECOVERY.md`; the DR drill measured 11.6 s and 14.6 s, but those figures are not yet recorded in either place, so R3 stays pending until they are.

Statuses: **pass** (evidence on `main`), **pending** (work or evidence missing), **fail** (the latest workflow run on `main` did not succeed), **decision** (waits for the product owner), **deferred** (the product owner moved it to a later release).

## Items

| Id | Item | Evidence | Status on 28 Nov 2026 |
|---|---|---|---|
| R1 | Load baselines (#656) | PR #680, `perf/baseline.json`, workflow `perf-smoke.yml`. The baseline is a single run; record the median of three runs (review request on #680) | pending |
| R2 | Upgrade from 1.0.0 (#658) | PR #683, workflow `upgrade-test.yml`; legacy seal scope in PR #692 | pending until #692 is merged |
| R3 | Backup and DR drill (#657) | PR #690, [DISASTER-RECOVERY.md](DISASTER-RECOVERY.md), workflow `dr-drill.yml`. Drill result: RPO 11.6 s, RTO 14.6 s (to be recorded in the report or the doc) | pending |
| R4 | Metrics, alerts, dashboards (#659) | PR #681, [OBSERVABILITY.md](OBSERVABILITY.md), workflow `observability.yml` | pass if the latest main run succeeded |
| R5 | HA soak, short CI mode (#655) | PR #695, workflow `soak-nightly.yml`, `perf/soak/`; deterministic timer re-lease test in PR #698 | pending until #698 is merged |
| R6 | 72-hour soak on pilot infrastructure | Needs the pilot environment (D1) | pending |
| R7 | Operations runbook (#661) and support policy (#662) | PR #697 (`wiki/Operations-Runbook.md`), PR #696 (`wiki/Support-and-Versioning-Policy.md`) | pending until both are merged |
| R8 | Release gate: SBOM and signature | PR #677, `tools/release/verify-release-attestations.sh`, workflow `release.yml`; the script checks the files and the latest run, the release job verifies the attestations | pending until `release.yml` has a run on `main` |
| R9 | Penetration test | Moved to 1.2, after the pilot (owner decision, 28 Nov 2026) | deferred |
| R10 | Tomcat CVE exception (decision D3) | `.grype.yaml` entry expires 2026-12-11; the owner decides on extension | decision |
| R11 | The database application role does not own the tables (#700) | No test or check yet; needs a migration-role split and a test that the application role cannot alter or drop tables | pending |
| R12 | Secrets and key rotation | No evidence linked; state whether covered elsewhere or out of scope for the pilot | pending |
| R13 | Rollback of a bad release | Beyond the 1.0.0 upgrade test; no evidence linked | pending |
| R14 | Multi-tenant isolation test result | No evidence linked; state whether covered elsewhere or out of scope | pending |
| R15 | Audit and retention configuration | No evidence linked; depends on the retention figures in D2 | pending |

## Open owner decisions

| Id | Decision | Gates |
|---|---|---|
| D1 | Pilot infrastructure: which environment runs the 72-hour soak and the pilot | R6 |
| D2 | SLA, LTS and retention figures in the support and versioning policy | R7 |
| D3 | Extend the Tomcat exception past 2026-12-11, or upgrade when a fixed release exists | R10 (the same item, counted once) |

## Sign-off

| Role | Name | Date |
|---|---|---|
| Product owner | | |

Update the table above and the script together when an item changes. Record the owner's decision on D1 to D3 in the pull request that closes the item.
