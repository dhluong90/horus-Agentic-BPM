#!/usr/bin/env python3
"""Rule Engine benchmark report and gate (TR-PERF-01, TR-PERF-02, TR-PERF-13).

Reads the JSON result of the JMH run of examples/rule-engine-benchmarks and writes a
Markdown report: one row per benchmark with the sample count and the p50, p90, p99 and
p99.9 times. A budget turns a benchmark into a gate:

    TR-PERF-01  EvaluationBenchmark.evaluateFirstHit200Rows   p99 under 100 us   gating
    TR-PERF-02  CompileBenchmark.readAndCompile1000Rows       p99 under 500 ms   reported

A gating benchmark fails the run when its p99 is at or above the budget, or when it is
missing from the result. A reported benchmark over its budget is marked in the report only.

Usage:  python3 tools/benchmark-report.py RESULT.json [--out REPORT.md]
Tests:  python3 tools/test_benchmark_report.py
Exit status: 0 when every gating budget holds, 1 otherwise, 2 when the result cannot be read.
Standard library only; Python 3.9 or later.
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from pathlib import Path

PACKAGE = "io.github.dhluong90.bpm.examples.benchmarks."

# Microseconds and milliseconds per unit, for budgets written in either.
_TO_MICROS = {"ns/op": 0.001, "us/op": 1.0, "ms/op": 1000.0, "s/op": 1_000_000.0}


@dataclass(frozen=True)
class Budget:
    requirement: str
    benchmark: str
    p99_micros: float
    gating: bool
    label: str


BUDGETS = (
    Budget("TR-PERF-01", "EvaluationBenchmark.evaluateFirstHit200Rows", 100.0, True, "100 us"),
    Budget("TR-PERF-02", "CompileBenchmark.readAndCompile1000Rows", 500_000.0, False, "500 ms"),
)


@dataclass(frozen=True)
class Result:
    name: str
    unit: str
    samples: int
    percentiles: dict

    def micros(self, percentile: str) -> float:
        return float(self.percentiles[percentile]) * _TO_MICROS[self.unit]


def read_results(path: Path) -> tuple[list[Result], dict]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, list):
        raise ValueError("a JMH JSON result is a list of benchmarks")
    results = []
    jvm = {}
    for entry in data:
        metric = entry["primaryMetric"]
        unit = metric["scoreUnit"]
        if unit not in _TO_MICROS:
            raise ValueError(f"{entry['benchmark']}: unit {unit} is not a time per operation")
        if entry.get("mode") != "sample":
            raise ValueError(f"{entry['benchmark']}: mode {entry.get('mode')} has no percentiles; use SampleTime")
        name = entry["benchmark"].removeprefix(PACKAGE)
        samples = sum(int(count) for row in metric.get("rawDataHistogram", []) for iteration in row
                      for _, count in iteration)
        results.append(Result(name, unit, samples, metric["scorePercentiles"]))
        jvm = {"jdk": entry.get("jdkVersion", "?"), "vm": entry.get("vmName", "?"),
               "warmup": f"{entry.get('warmupIterations', '?')} x {entry.get('warmupTime', '?')}",
               "measurement": f"{entry.get('measurementIterations', '?')} x {entry.get('measurementTime', '?')}"}
    return results, jvm


def evaluate(results: list[Result]) -> tuple[list[str], list[str]]:
    """Returns the report rows of the budgets and the failures of gating budgets."""
    by_name = {r.name: r for r in results}
    rows, failures = [], []
    for budget in BUDGETS:
        result = by_name.get(budget.benchmark)
        kind = "gating" if budget.gating else "reported"
        if result is None:
            rows.append(f"| {budget.requirement} | `{budget.benchmark}` | p99 under {budget.label} | missing | {kind} |")
            if budget.gating:
                failures.append(f"{budget.requirement}: {budget.benchmark} is missing from the result")
            continue
        p99 = result.micros("99.0")
        held = p99 < budget.p99_micros
        verdict = "met" if held else "**over budget**"
        rows.append(f"| {budget.requirement} | `{budget.benchmark}` | p99 under {budget.label} | "
                    f"{format_time(p99)}, {verdict} | {kind} |")
        if budget.gating and not held:
            failures.append(f"{budget.requirement}: p99 of {budget.benchmark} is {format_time(p99)}, "
                            f"budget {budget.label}")
    return rows, failures


def format_time(micros: float) -> str:
    if micros >= 1000.0:
        return f"{micros / 1000.0:.2f} ms"
    return f"{micros:.2f} us"


def report(results: list[Result], jvm: dict) -> tuple[str, list[str]]:
    budget_rows, failures = evaluate(results)
    lines = [
        "## Rule Engine benchmarks",
        "",
        f"JDK {jvm.get('jdk', '?')} ({jvm.get('vm', '?')}); warm-up {jvm.get('warmup', '?')}, "
        f"measurement {jvm.get('measurement', '?')}; one call per sample, cached compiled table, "
        "no decision log write.",
        "",
        "| Requirement | Benchmark | Budget | Result | Kind |",
        "|---|---|---|---|---|",
        *budget_rows,
        "",
        "| Benchmark | Samples | p50 | p90 | p99 | p99.9 |",
        "|---|---|---|---|---|---|",
    ]
    for r in sorted(results, key=lambda r: r.name):
        lines.append(f"| `{r.name}` | {r.samples:,} | {format_time(r.micros('50.0'))} | "
                     f"{format_time(r.micros('90.0'))} | {format_time(r.micros('99.0'))} | "
                     f"{format_time(r.micros('99.9'))} |")
    lines.append("")
    return "\n".join(lines), failures


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("result", type=Path, help="JMH JSON result (-rf json)")
    parser.add_argument("--out", type=Path, help="write the Markdown report here as well")
    args = parser.parse_args(argv)
    try:
        results, jvm = read_results(args.result)
    except (OSError, ValueError, KeyError, TypeError) as e:
        print(f"Cannot read the benchmark result {args.result}: {e}", file=sys.stderr)
        return 2
    text, failures = report(results, jvm)
    print(text)
    if args.out:
        args.out.write_text(text, encoding="utf-8")
    for failure in failures:
        print(f"FAILED {failure}", file=sys.stderr)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
