#!/usr/bin/env python3
"""Tests of tools/benchmark-report.py (TR-PERF-01, TR-PERF-02) on hand-written JMH results. No JVM.

Run: python3 tools/test_benchmark_report.py   (or -v for each name)
"""

from __future__ import annotations

import importlib.util
import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

_SPEC = importlib.util.spec_from_file_location("benchmark_report", Path(__file__).with_name("benchmark-report.py"))
bench = importlib.util.module_from_spec(_SPEC)
sys.modules["benchmark_report"] = bench
_SPEC.loader.exec_module(bench)

PKG = "io.github.dhluong90.bpm.examples.benchmarks."


def entry(name: str, unit: str, p99: float, mode: str = "sample", samples: int = 1000) -> dict:
    percentiles = {"0.0": p99 / 4, "50.0": p99 / 3, "90.0": p99 / 2, "95.0": p99 / 1.5, "99.0": p99,
                   "99.9": p99 * 2, "100.0": p99 * 10}
    return {"benchmark": PKG + name, "mode": mode, "jdkVersion": "21.0.9", "vmName": "OpenJDK 64-Bit Server VM",
            "warmupIterations": 3, "warmupTime": "2 s", "measurementIterations": 5, "measurementTime": "2 s",
            "primaryMetric": {"scoreUnit": unit, "scorePercentiles": percentiles,
                              "rawDataHistogram": [[[[p99, samples]]]]}}


EVAL = "EvaluationBenchmark.evaluateFirstHit200Rows"
COMPILE = "CompileBenchmark.readAndCompile1000Rows"


class BenchmarkReportTest(unittest.TestCase):

    def run_report(self, data) -> tuple[int, str, str]:
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        result = Path(tmp.name) / "jmh.json"
        result.write_text(json.dumps(data) if not isinstance(data, str) else data, encoding="utf-8")
        out, err = io.StringIO(), io.StringIO()
        report = Path(tmp.name) / "report.md"
        with redirect_stdout(out), redirect_stderr(err):
            status = bench.main([str(result), "--out", str(report)])
        if status != 2:
            self.assertEqual(report.read_text(encoding="utf-8").strip(), out.getvalue().strip())
        return status, out.getvalue(), err.getvalue()

    def test_TR_PERF_01_a_p99_under_100_us_passes_and_is_reported(self):
        status, out, _ = self.run_report([entry(EVAL, "us/op", 8.83), entry(COMPILE, "ms/op", 26.0)])
        self.assertEqual(status, 0)
        self.assertIn("| TR-PERF-01 | `EvaluationBenchmark.evaluateFirstHit200Rows` | p99 under 100 us | 8.83 us, met"
                      " | gating |", out)
        self.assertIn("| `EvaluationBenchmark.evaluateFirstHit200Rows` | 1,000 |", out)
        self.assertIn("JDK 21.0.9", out)

    def test_TR_PERF_01_a_p99_of_100_us_or_more_fails(self):
        status, out, err = self.run_report([entry(EVAL, "us/op", 100.0), entry(COMPILE, "ms/op", 26.0)])
        self.assertEqual(status, 1)
        self.assertIn("**over budget**", out)
        self.assertIn("FAILED TR-PERF-01: p99 of EvaluationBenchmark.evaluateFirstHit200Rows is 100.00 us", err)

    def test_TR_PERF_01_a_p99_written_in_nanoseconds_is_converted(self):
        status, out, _ = self.run_report([entry(EVAL, "ns/op", 120_000.0)])
        self.assertEqual(status, 1)
        self.assertIn("120.00 us, **over budget**", out)

    def test_TR_PERF_01_a_missing_gating_benchmark_fails(self):
        status, out, err = self.run_report([entry(COMPILE, "ms/op", 26.0)])
        self.assertEqual(status, 1)
        self.assertIn("missing", out)
        self.assertIn("FAILED TR-PERF-01: EvaluationBenchmark.evaluateFirstHit200Rows is missing", err)

    def test_TR_PERF_02_a_slow_compile_is_marked_but_does_not_fail(self):
        status, out, err = self.run_report([entry(EVAL, "us/op", 9.0), entry(COMPILE, "ms/op", 750.0)])
        self.assertEqual(status, 0)
        self.assertIn("| TR-PERF-02 | `CompileBenchmark.readAndCompile1000Rows` | p99 under 500 ms | 750.00 ms,"
                      " **over budget** | reported |", out)
        self.assertEqual(err, "")

    def test_a_result_without_percentiles_is_refused(self):
        status, _, err = self.run_report([entry(EVAL, "us/op", 9.0, mode="avgt")])
        self.assertEqual(status, 2)
        self.assertIn("use SampleTime", err)

    def test_an_unreadable_result_is_refused(self):
        status, _, err = self.run_report("not json")
        self.assertEqual(status, 2)
        self.assertIn("Cannot read the benchmark result", err)


if __name__ == "__main__":
    unittest.main()
