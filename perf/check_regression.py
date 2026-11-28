#!/usr/bin/env python3
"""Fail when a load run is much worse than the baseline (perf/baseline.json).

  python3 perf/check_regression.py perf/results/run.json [--baseline perf/baseline.json]

The thresholds are generous on purpose: a smoke run is short and runs on a shared CI machine, so it catches a large
regression (an index lost, a lock held across a call, an accidental N+1), not a few percent. For every operation in the
baseline the run must have
  - an error rate of at most `maxErrorRate`;
  - a p95 of at most max(baseline p95 * p95Factor, p95FloorMs);
  - a throughput of at least baseline throughput * (run threads / baseline threads) / throughputFactor.
The factors are in the baseline file under "regression". Exit code 1 lists every breach; 2 is a usage error.
"""
import argparse
import json
import sys
from pathlib import Path


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("run")
    ap.add_argument("--baseline", default=str(Path(__file__).with_name("baseline.json")))
    args = ap.parse_args()
    run = json.loads(Path(args.run).read_text())
    base = json.loads(Path(args.baseline).read_text())
    rules = {"maxErrorRate": 0.01, "p95Factor": 5.0, "p95FloorMs": 100.0, "throughputFactor": 10.0}
    rules.update(base.get("regression", {}))
    scale = run["threads"] / base["threads"]
    problems, rows = [], []
    for name, b in base["operations"].items():
        r = run["operations"].get(name)
        if r is None:
            problems.append(f"{name}: no samples in this run")
            continue
        err_rate = r["errors"] / max(r["count"], 1)
        p95_limit = max(b["p95Ms"] * rules["p95Factor"], rules["p95FloorMs"])
        tp_limit = b["perSecond"] * scale / rules["throughputFactor"]
        ok = True
        if err_rate > rules["maxErrorRate"]:
            problems.append(f"{name}: error rate {err_rate:.1%} over {rules['maxErrorRate']:.1%} ({r.get('firstError')})")
            ok = False
        if r["p95Ms"] > p95_limit:
            problems.append(f"{name}: p95 {r['p95Ms']} ms over the limit {p95_limit:.1f} ms (baseline {b['p95Ms']} ms)")
            ok = False
        if r["perSecond"] < tp_limit:
            problems.append(f"{name}: {r['perSecond']}/s under the floor {tp_limit:.1f}/s (baseline {b['perSecond']}/s)")
            ok = False
        rows.append(f"{'ok  ' if ok else 'FAIL'} {name:30s} p95 {r['p95Ms']:8.2f} ms (limit {p95_limit:7.1f})  "
                    f"{r['perSecond']:8.1f}/s (floor {tp_limit:7.1f})  errors {r['errors']}/{r['count']}")
    print("\n".join(rows))
    if problems:
        print("\nRegression check FAILED:\n  " + "\n  ".join(problems))
        return 1
    print("\nRegression check passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
