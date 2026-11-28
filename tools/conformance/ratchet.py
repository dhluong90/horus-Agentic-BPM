#!/usr/bin/env python3
"""Conformance summary and ratchet for the DMN TCK and BPMN MIWG runs (#203, #204).

Usage:   ratchet.py check  RESULTS_DIR [--baseline FILE] [--own-suite XML ...]
         ratchet.py update RESULTS_DIR [--baseline FILE]

RESULTS_DIR holds dmn-tck-results.json (written by DmnTckConformanceRun) and bpmn-miwg-results.json (written by
MiwgRoundTripRun); either may be absent, and then that suite is not reported or checked.

check   prints a Markdown summary (the workflow appends it to the job summary): the pass rates, the change since the
        recorded baseline, the most common failure reasons, and the MIWG table per model. It exits 1 when a number is
        below the baseline (the ratchet), and 0 otherwise. A pass rate is never a reason to fail by itself.
update  rewrites the baseline from the results. Use it in a pull request that raises the numbers (or that moves a
        pin and says why); the reviewer reads the diff of the baseline file.

The ratchet compares like with like: DMN pass rates (passed / cases) and models read; MIWG counts of models that
are read, pass the semantic, diagram and schema check, and pass all three. A MIWG count is compared only when the
number of models is unchanged.
Standard library only. Exit: 0 ok, 1 below baseline, 2 usage error or unreadable input.
"""

from __future__ import annotations

import argparse
import collections
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

HERE = Path(__file__).resolve().parent
DEFAULT_BASELINE = HERE / "baseline.json"
MIWG_KEYS = ("read", "semantic", "diagram", "schema", "all")


def load(path: Path):
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else None


def pins() -> dict[str, str]:
    result = {}
    for line in (HERE / "pins.env").read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            result[key.strip()] = value.strip()
    return result


def baseline_from(dmn, miwg) -> dict:
    p = pins()
    out: dict = {"pins": {"dmnTck": p.get("DMN_TCK_COMMIT"), "miwg": p.get("MIWG_COMMIT")}}
    if dmn:
        out["dmnTck"] = {
            level: {k: v[k] for k in ("models", "modelsRead", "cases", "passed")}
            for level, v in dmn["levels"].items()
        }
    if miwg:
        out["bpmnMiwg"] = {k: miwg["summary"][k] for k in ("models",) + MIWG_KEYS}
    return out


def rate(passed: int, cases: int) -> str:
    return "n/a" if cases == 0 else f"{100.0 * passed / cases:.1f}%"


def check(dmn, miwg, base, own_suite) -> tuple[list[str], list[str]]:
    """Returns (markdown lines, failures)."""
    md: list[str] = ["## Conformance, daily run", ""]
    failures: list[str] = []
    current = pins()
    if base and base.get("pins", {}).get("dmnTck") not in (None, current.get("DMN_TCK_COMMIT")):
        md += ["> The DMN TCK pin differs from the one the baseline was recorded at; compare the rates, not the counts.", ""]
    if dmn:
        md += ["### DMN TCK, rule engine", "",
               f"Suite pinned at `{current.get('DMN_TCK_COMMIT', '?')[:12]}`. A case passes when the model imports, "
               "publishes and evaluates to the expected result node values.", "",
               "| Level | Models | Models read | Cases | Passed | Pass rate | Baseline | Change |",
               "|---|---:|---:|---:|---:|---:|---:|---|"]
        for level, v in dmn["levels"].items():
            b = (base or {}).get("dmnTck", {}).get(level)
            if b:
                change = f"{v['passed'] - b['passed']:+d} cases, {v['modelsRead'] - b['modelsRead']:+d} models read"
                baseline = rate(b["passed"], b["cases"])
                if v["cases"] and b["cases"] and v["passed"] * b["cases"] < b["passed"] * v["cases"]:
                    failures.append(f"DMN TCK {level}: pass rate {rate(v['passed'], v['cases'])} is below the baseline {baseline}")
                if v["modelsRead"] < b["modelsRead"] and v["models"] == b["models"]:
                    failures.append(f"DMN TCK {level}: models read {v['modelsRead']} is below the baseline {b['modelsRead']}")
            else:
                change, baseline = "no baseline", "none"
            md.append(f"| {level} | {v['models']} | {v['modelsRead']} | {v['cases']} | {v['passed']} | "
                      f"{rate(v['passed'], v['cases'])} | {baseline} | {change} |")
        reasons = collections.Counter(
            re.sub(r' id="[^"]*"', "", r["reason"]).split(" (")[0][:100] for r in dmn["results"] if r["status"] == "FAIL")
        if reasons:
            md += ["", "Most common failure reasons (cases):", ""]
            md += [f"- {n} x `{reason}`" for reason, n in reasons.most_common(8)]
        md += ["", "The per-case rows are in the `conformance-results` artifact (`dmn-tck-results.json`, and "
               "`tck_results.csv` in the TCK results format).", ""]
    if miwg:
        s = miwg["summary"]
        b = (base or {}).get("bpmnMiwg")
        md += ["### BPMN MIWG round trip, process engine", "",
               f"Suite pinned at `{current.get('MIWG_COMMIT', '?')[:12]}`. Each reference model is imported, "
               "exported and compared; the three checks are reported separately.", "",
               "| Check | Models | Baseline | Change |", "|---|---:|---:|---|"]
        labels = {"read": "Imported", "semantic": "Semantic equal", "diagram": "Diagram data equal",
                  "schema": "Export valid against the XSD", "all": "All three checks"}
        for key in MIWG_KEYS:
            if b:
                change = f"{s[key] - b[key]:+d}"
                if s["models"] == b["models"] and s[key] < b[key]:
                    failures.append(f"BPMN MIWG {labels[key].lower()}: {s[key]} of {s['models']} is below the baseline {b[key]}")
                md.append(f"| {labels[key]} | {s[key]} of {s['models']} | {b[key]} | {change} |")
            else:
                md.append(f"| {labels[key]} | {s[key]} of {s['models']} | none | no baseline |")
        md += ["", "| Model | Imported | Semantic | Diagram | Schema | First difference |", "|---|:-:|:-:|:-:|:-:|---|"]
        mark = {True: "pass", False: "fail"}
        for r in miwg["results"]:
            first = (r["readError"] or (r["semanticDiffs"] or r["diagramDiffs"] or [r["schemaError"]])[0] or "")
            first = first.replace("|", "/")[:140]
            md.append(f"| {r['model']} | {mark[r['read']]} | {mark[r['semantic']]} | {mark[r['diagram']]} | "
                      f"{mark[r['schema']]} | {first} |")
        md.append("")
    if own_suite:
        tests = failed = 0
        for path in own_suite:
            root = ET.parse(path).getroot()
            tests += int(root.get("tests", 0))
            failed += int(root.get("failures", 0)) + int(root.get("errors", 0))
        md += ["### Executable scenarios, the project's own suite", "",
               f"`ConformanceSuiteTest`: {tests - failed} of {tests} scenarios pass. Reported apart from the MIWG "
               "numbers: these are files written in this repository, not the MIWG reference models.", ""]
        if failed:
            failures.append(f"ConformanceSuiteTest: {failed} scenario(s) fail")
    md += ["### Attribution and licences", "",
           "DMN TCK test cases, (c) the DMN TCK contributors, https://github.com/dmn-tck/tck: the repository carries "
           "an Apache 2.0 licence file and its README offers the test cases under a Creative Commons "
           "Share-Alike-With-Attribution licence; the stricter reading applies. BPMN MIWG reference models, (c) the "
           "BPMN MIWG contributors, https://github.com/bpmn-miwg/bpmn-miwg-test-suite, Creative Commons Attribution "
           "3.0 Unported. Both are fetched at build time as test data, never committed and never shipped.", ""]
    if failures:
        md += ["### Ratchet: failed", ""] + [f"- {f}" for f in failures] + [""]
    else:
        md += ["### Ratchet: passed", "", "No number is below the recorded baseline.", ""]
    return md, failures


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", choices=["check", "update"])
    ap.add_argument("results", type=Path)
    ap.add_argument("--baseline", type=Path, default=DEFAULT_BASELINE)
    ap.add_argument("--own-suite", type=Path, nargs="*", default=[])
    args = ap.parse_args(argv)
    try:
        dmn = load(args.results / "dmn-tck-results.json")
        miwg = load(args.results / "bpmn-miwg-results.json")
    except (OSError, ValueError) as e:
        print(f"cannot read the results: {e}", file=sys.stderr)
        return 2
    if not dmn and not miwg:
        print(f"no results in {args.results}", file=sys.stderr)
        return 2
    if args.command == "update":
        new = baseline_from(dmn, miwg)
        old = load(args.baseline) or {}
        old.update(new)
        args.baseline.write_text(json.dumps(old, indent=2) + "\n", encoding="utf-8")
        print(f"baseline written to {args.baseline}")
        return 0
    try:
        base = load(args.baseline)
    except (OSError, ValueError) as e:
        print(f"cannot read the baseline: {e}", file=sys.stderr)
        return 2
    md, failures = check(dmn, miwg, base, [p for p in args.own_suite if p.exists()])
    print("\n".join(md))
    for f in failures:
        print(f"::error title=Conformance ratchet::{f}", file=sys.stderr)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
